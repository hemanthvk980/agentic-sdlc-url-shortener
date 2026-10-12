package io.github.hemanthvk980.orchestrator.cli;

import io.github.hemanthvk980.orchestrator.agents.RequirementsAgent;
import io.github.hemanthvk980.orchestrator.approval.ConsoleHumanGateway;
import io.github.hemanthvk980.orchestrator.approval.HumanGateway;
import io.github.hemanthvk980.orchestrator.approval.ScriptedHumanGateway;
import io.github.hemanthvk980.orchestrator.audit.AuditLog;
import io.github.hemanthvk980.orchestrator.engine.Orchestrator;
import io.github.hemanthvk980.orchestrator.engine.RunState;
import io.github.hemanthvk980.orchestrator.graph.TaskGraph;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import io.github.hemanthvk980.orchestrator.llm.FallbackLlmClient;
import io.github.hemanthvk980.orchestrator.llm.FixtureLlmClient;
import io.github.hemanthvk980.orchestrator.llm.GeminiClient;
import io.github.hemanthvk980.orchestrator.llm.LlmClient;
import io.github.hemanthvk980.orchestrator.llm.ObservedLlmClient;
import io.github.hemanthvk980.orchestrator.llm.RecordReplayLlmClient;
import io.github.hemanthvk980.orchestrator.metrics.MetricsAggregator;
import io.github.hemanthvk980.orchestrator.metrics.RunMetrics;
import io.github.hemanthvk980.orchestrator.policy.PolicyEngine;
import io.github.hemanthvk980.orchestrator.report.RunReporter;
import io.github.hemanthvk980.orchestrator.scenario.Scenario;
import io.github.hemanthvk980.orchestrator.scenario.SdlcPipeline;
import io.github.hemanthvk980.orchestrator.workspace.GitWorkspace;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line entry point.
 *
 * <pre>
 * run      --scenario scenarios/greenfield/scenario.json [--llm gemini|replay|offline] [--record]
 *          [--interactive] [--out runs] [--replan] [--repo-root .]
 * metrics  [--out runs]
 * export   [--out runs] [--target web/public/runs]
 * verify-audit &lt;runs/&lt;id&gt;/audit.jsonl&gt;
 * </pre>
 */
public final class OrchestratorCli {

    private OrchestratorCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        Map<String, String> opts = parse(args);
        int code = switch (args[0]) {
            case "run" -> run(opts);
            case "metrics" -> {
                System.out.println(Json.pretty(MetricsAggregator.aggregate(Path.of(opts.getOrDefault("out", "runs")))));
                yield 0;
            }
            case "export" -> {
                exportForWeb(Path.of(opts.getOrDefault("out", "runs")), Path.of(opts.getOrDefault("target", "web/public/runs")));
                yield 0;
            }
            case "verify-audit" -> {
                boolean ok = AuditLog.verifyFile(Path.of(args[1]));
                System.out.println(ok ? "audit chain OK" : "audit chain BROKEN");
                yield ok ? 0 : 1;
            }
            default -> { usage(); yield 2; }
        };
        System.exit(code);
    }

    static int run(Map<String, String> opts) throws IOException {
        Scenario sc = Scenario.load(Path.of(required(opts, "scenario")));
        Path repoRoot = Path.of(opts.getOrDefault("repo-root", ".")).toAbsolutePath().normalize();
        String mode = opts.getOrDefault("llm", "offline");
        Path outRoot = Path.of(opts.getOrDefault("out", "runs"));
        String runId = sc.name() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outDir = outRoot.resolve(runId);
        Files.createDirectories(outDir);

        // Workspace: a fresh git repo per run, optionally seeded from an existing module (brownfield).
        GitWorkspace ws = GitWorkspace.open(outDir.resolve("workspace"), "agent/" + runId);
        if (!sc.seedPaths().isEmpty()) {
            for (String p : sc.seedPaths()) copyTree(repoRoot.resolve(p), ws.root().resolve(p));
            ws.commit("seed: existing codebase " + sc.seedPaths());
        }
        String base = ws.head();

        AuditLog audit = new AuditLog(runId, outDir.resolve("audit.jsonl"));
        audit.addListener(OrchestratorCli::printEvent);
        RunMetrics metrics = new RunMetrics();

        LlmClient raw = switch (mode) {
            case "gemini" -> {
                LlmClient primary = GeminiClient.fromEnv("gemini-2.5-flash");
                LlmClient secondary = new GeminiClient(System.getenv("GEMINI_API_KEY"),
                        System.getenv().getOrDefault("GEMINI_FALLBACK_MODEL", "gemini-2.5-flash-lite"), Duration.ofSeconds(180));
                LlmClient withFallback = new FallbackLlmClient(primary, secondary, (req, e) ->
                        audit.record("llm.fallback", req.purpose(), Map.of("error", String.valueOf(e.getMessage()))));
                yield opts.containsKey("record")
                        ? RecordReplayLlmClient.recording(withFallback, sc.dir().resolve("recordings")) : withFallback;
            }
            case "replay" -> RecordReplayLlmClient.replaying(sc.dir().resolve("recordings"));
            case "offline" -> FixtureLlmClient.fromDirectory(sc.dir().resolve("fixtures"));
            default -> throw new IllegalArgumentException("unknown --llm " + mode);
        };
        LlmClient llm = new ObservedLlmClient(raw, audit, metrics, sc.maxLlmCalls(), 3);

        HumanGateway human = opts.containsKey("interactive")
                ? new ConsoleHumanGateway(System.getProperty("user.name", "human"))
                : new ScriptedHumanGateway(sc.approver(), sc.scriptedApprovals(), sc.scriptedAnswers());

        RunState[] stateRef = new RunState[1];
        // Offline fixtures can't be compiled meaningfully, so offline runs use the scenario's structural check.
        List<String> verify = mode.equals("offline") && !sc.offlineVerifyCommand().isEmpty()
                ? absolutize(sc.offlineVerifyCommand(), repoRoot) : sc.verifyCommand();
        TaskGraph graph = SdlcPipeline.build(sc, base, () -> stateRef[0].decisions(), verify);
        RunState state = new RunState(runId, sc.name(), sc.requirement(), graph);
        stateRef[0] = state;

        Orchestrator o = Orchestrator.builder()
                .graph(graph).state(state).llm(llm).workspace(ws)
                .policies(PolicyEngine.defaults(sc.allowedPaths()))
                .human(human).audit(audit).metrics(metrics)
                .maxWallClock(Duration.ofMinutes(sc.maxMinutes()))
                .build();

        System.out.println("▶ run " + runId + " | scenario=" + sc.name() + " (" + sc.type() + ") | llm=" + llm.name());
        System.out.println("  plan layers: " + graph.layers());
        o.run();

        if (opts.containsKey("replan") && state.outcome() == RunState.Outcome.SUCCEEDED) {
            demonstrateReplan(o, sc);
        }

        RunReporter.write(o, outDir, llm.name());
        Files.writeString(outDir.resolve("changes.patch"), ws.diff(base, ws.head()));
        System.out.println();
        System.out.println("■ " + state.outcome() + " — " + state.outcomeReason());
        System.out.println("  report:    " + outDir.resolve("report.md"));
        System.out.println("  audit:     " + outDir.resolve("audit.jsonl") + " (chain " + (audit.verify() ? "verified" : "BROKEN") + ")");
        System.out.println("  workspace: " + ws.root() + " (branch agent/" + runId + ")");
        return state.outcome() == RunState.Outcome.SUCCEEDED ? 0 : 1;
    }

    /**
     * Copies each run's run.json (and report.md) into the web app's static folder with an index, so the
     * public site can show the evidence read-only. Audit logs and workspaces are not published.
     */
    static void exportForWeb(Path runsDir, Path target) throws IOException {
        List<Map<String, Object>> index = new ArrayList<>();
        Files.createDirectories(target);
        if (Files.isDirectory(runsDir)) {
            try (var s = Files.list(runsDir)) {
                for (Path dir : s.filter(Files::isDirectory).sorted().toList()) {
                    Path run = dir.resolve("run.json");
                    if (!Files.isRegularFile(run)) continue;
                    Map<String, Object> r = Json.parseObject(Files.readString(run));
                    Path out = Files.createDirectories(target.resolve(dir.getFileName()));
                    Files.copy(run, out.resolve("run.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("runId", r.get("runId"));
                    e.put("scenario", r.get("scenario"));
                    e.put("outcome", r.get("outcome"));
                    e.put("llm", r.get("llm"));
                    index.add(e);
                }
            }
        }
        Files.writeString(target.resolve("index.json"), Json.pretty(index));
        System.out.println("exported " + index.size() + " run(s) to " + target);
    }

    /**
     * Simulates an upstream change after the run (a stakeholder amends the requirements). The engine
     * invalidates only the stages that consumed the old requirements and everything downstream of
     * them, then re-runs just that subgraph — repo-index is untouched.
     */
    @SuppressWarnings("unchecked")
    private static void demonstrateReplan(Orchestrator o, Scenario sc) throws IOException {
        Path patchFile = sc.dir().resolve("replan-patch.json");
        if (!Files.isRegularFile(patchFile)) return;
        Map<String, Object> patch = Json.parseObject(Files.readString(patchFile));
        Map<String, Object> req = Json.parseObject(o.state().artifact(RequirementsAgent.ARTIFACT).orElseThrow().content());
        for (String key : List.of("functionalRequirements", "acceptanceCriteria", "assumptions")) {
            List<Object> merged = new ArrayList<>(JsonPath.list(req, key));
            merged.addAll(JsonPath.list(patch, key));
            req.put(key, merged);
        }
        System.out.println();
        System.out.println("↻ upstream change: " + patch.get("reason"));
        List<String> invalidated = o.replan(RequirementsAgent.ARTIFACT, Json.pretty(req),
                String.valueOf(patch.get("reason")), "human:" + sc.approver() + "(scripted)");
        System.out.println("  invalidated: " + invalidated);
        o.run();
    }

    @SuppressWarnings("unchecked")
    private static void printEvent(Map<String, Object> e) {
        String type = (String) e.get("type");
        Map<String, Object> d = (Map<String, Object>) (Map<?, ?>) e.get("data");
        String node = e.get("node") == null ? "" : String.valueOf(e.get("node"));
        String line = switch (type) {
            case "node.status" -> String.format("  %-18s %s → %s%s", node, d.get("from"), d.get("to"),
                    d.containsKey("error") ? "  ⚠ " + first(d.get("error")) : d.containsKey("summary") ? "  " + first(d.get("summary")) : "");
            case "node.attempt.failed" -> String.format("  %-18s ✗ attempt failed: %s", node, first(d.get("error")));
            case "gate.evaluated" -> Boolean.TRUE.equals(d.get("passed")) ? null
                    : String.format("  %-18s ⛔ %s gate %s: %s", node, d.get("kind"), d.get("gate"), first(d.get("detail")));
            case "policy.evaluated" -> String.format("  %-18s 🛡 policy findings: %s", node, ((List<?>) d.get("findings")).size());
            case "approval.decided" -> String.format("  %-18s 👤 %s by %s %s", node, d.get("outcome"), d.get("approver"),
                    d.get("comment") == null || d.get("comment").toString().isBlank() ? "" : "— " + d.get("comment"));
            case "human.clarified" -> String.format("  %-18s 👤 clarifications: %s", node, ((Map<?, ?>) d.get("answers")).size());
            case "workspace.rollback" -> String.format("  %-18s ↩ rollback to %s", node, first(d.get("to")));
            case "workspace.commit" -> String.format("  %-18s ✔ commit %s", node, first(d.get("commit")).substring(0, Math.min(10, first(d.get("commit")).length())));
            case "node.fallback" -> String.format("  %-18s ⤵ fallback engaged", node);
            case "node.replan", "run.replan" -> String.format("  %-18s ↻ re-plan: %s", node, d);
            case "node.skipped" -> String.format("  %-18s ⏭ skipped (%s)", node, d.get("because"));
            default -> null;
        };
        if (line != null) System.out.println(line);
    }

    private static String first(Object o) {
        if (o == null) return "";
        String s = o.toString().lines().filter(l -> !l.isBlank() && !l.matches("^exit \\d+:$"))
                .findFirst().orElse(o.toString().lines().findFirst().orElse(""));
        return s.length() > 140 ? s.substring(0, 140) + "…" : s;
    }

    /** Repo-relative script paths (e.g. scenarios/common/x.sh) must still resolve inside the workspace. */
    private static List<String> absolutize(List<String> cmd, Path repoRoot) {
        return cmd.stream().map(a -> a.startsWith("scenarios/") ? repoRoot.resolve(a).toString() : a).toList();
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        if (!Files.isDirectory(from)) throw new IOException("seed path not found: " + from.toAbsolutePath());
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName().toString();
                if (name.equals("build") || name.equals(".gradle") || name.equals("node_modules") || name.equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String k = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(k, args[++i]);
            else m.put(k, "true");
        }
        return new LinkedHashMap<>(m);
    }

    private static String required(Map<String, String> opts, String k) {
        String v = opts.get(k);
        if (v == null) throw new IllegalArgumentException("--" + k + " is required");
        return v;
    }

    private static void usage() {
        System.err.println("""
                usage:
                  run --scenario <scenario.json> [--llm offline|gemini|replay] [--record] [--interactive] [--replan]
                      [--out runs] [--repo-root .]
                  metrics [--out runs]
                  export  [--out runs] [--target web/public/runs]
                  verify-audit <audit.jsonl>
                env: GEMINI_API_KEY (required for --llm gemini), GEMINI_MODEL, GEMINI_FALLBACK_MODEL
                """);
    }
}
