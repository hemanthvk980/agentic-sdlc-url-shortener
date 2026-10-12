package io.github.hemanthvk980.orchestrator.report;

import io.github.hemanthvk980.orchestrator.engine.Artifact;
import io.github.hemanthvk980.orchestrator.engine.Decision;
import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.engine.Orchestrator;
import io.github.hemanthvk980.orchestrator.engine.RunState;
import io.github.hemanthvk980.orchestrator.graph.StageNode;
import io.github.hemanthvk980.orchestrator.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the reviewable evidence of a run: {@code run.json} (machine-readable), {@code report.md}
 * (human-readable, with a Mermaid DAG coloured by outcome), and every artifact with its lineage.
 * The audit log is written live by {@link io.github.hemanthvk980.orchestrator.audit.AuditLog}.
 */
public final class RunReporter {

    private RunReporter() {}

    public static void write(Orchestrator o, Path outDir, String llmName) throws IOException {
        RunState s = o.state();
        Files.createDirectories(outDir.resolve("artifacts"));
        for (Artifact a : s.artifacts().values()) {
            Files.writeString(outDir.resolve("artifacts").resolve(a.name()), a.content(), StandardCharsets.UTF_8);
        }

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", s.runId());
        run.put("scenario", s.scenario());
        run.put("llm", llmName);
        run.put("outcome", s.outcome());
        run.put("outcomeReason", s.outcomeReason());
        run.put("startedAt", s.startedAt().toString());
        run.put("finishedAt", s.finishedAt() == null ? null : s.finishedAt().toString());
        run.put("requirement", s.requirement());
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (StageNode n : o.graph().nodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", n.title());
            m.put("phase", n.phase());
            m.put("dependsOn", n.dependsOn());
            m.put("status", s.status(n.id()));
            m.put("attempts", s.attempts(n.id()));
            m.put("summary", s.summary(n.id()));
            m.put("error", s.errors().get(n.id()));
            m.put("inputs", s.inputHashes(n.id()));
            m.put("commit", s.commits().get(n.id()));
            m.put("changes", s.appliedChanges().getOrDefault(n.id(), List.of()));
            nodes.put(n.id(), m);
        }
        run.put("nodes", nodes);
        run.put("artifacts", s.artifactHistory());
        run.put("decisions", s.decisions());
        run.put("clarifications", s.clarifications());
        run.put("metrics", o.metrics().toJson());
        run.put("auditEvents", o.audit().size());
        run.put("auditChainValid", o.audit().verify());
        Files.writeString(outDir.resolve("run.json"), Json.pretty(run), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("report.md"), markdown(o, llmName), StandardCharsets.UTF_8);
    }

    static String markdown(Orchestrator o, String llmName) {
        RunState s = o.state();
        StringBuilder md = new StringBuilder();
        md.append("# Run report: `").append(s.runId()).append("`\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Scenario | ").append(s.scenario()).append(" |\n");
        md.append("| Outcome | **").append(s.outcome()).append("** — ").append(esc(s.outcomeReason())).append(" |\n");
        md.append("| Model | ").append(esc(llmName)).append(" |\n");
        md.append("| Audit chain | ").append(o.audit().verify() ? "verified ✅" : "BROKEN ❌").append(" (")
                .append(o.audit().size()).append(" events) |\n\n");

        md.append("## Requirement\n\n> ").append(s.requirement().strip().replace("\n", "\n> ")).append("\n\n");
        md.append("## Execution graph\n\n```mermaid\n").append(o.graph().toMermaid(s.statuses())).append("```\n\n");

        md.append("## Stages\n\n| Stage | Status | Attempts | Summary |\n|---|---|---|---|\n");
        for (StageNode n : o.graph().nodes()) {
            String err = s.errors().get(n.id());
            md.append("| ").append(n.id()).append(" | ").append(s.status(n.id())).append(" | ").append(s.attempts(n.id()))
                    .append(" | ").append(esc(s.summary(n.id()))).append(err != null ? " ⚠ " + esc(trim(err, 200)) : "")
                    .append(" |\n");
        }

        Map<String, Object> m = cast(o.metrics().toJson());
        md.append("\n## Reliability metrics\n\n| Metric | Value |\n|---|---|\n");
        for (String k : List.of("e2eLatencyMs", "e2eLatencyExcludingHumanMs", "humanWaitMs", "retries", "rollbacks",
                "fallbacks", "recoveries", "mttrMs", "llmCalls", "llmFailures", "policyBlocks", "approvalsGranted",
                "approvalsRejected", "replans")) {
            md.append("| ").append(k).append(" | ").append(m.get(k)).append(" |\n");
        }

        if (!s.clarifications().isEmpty()) {
            md.append("\n## Human clarifications\n\n");
            s.clarifications().forEach((q, a) -> md.append("- **Q:** ").append(q).append("\n  **A:** ").append(a).append('\n'));
        }

        md.append("\n## Decision log\n\n| Stage | Kind | Actor | Decision | Rationale |\n|---|---|---|---|---|\n");
        for (Decision d : s.decisions()) {
            md.append("| ").append(d.nodeId() == null ? "—" : d.nodeId()).append(" | ").append(d.kind()).append(" | ")
                    .append(esc(d.actor())).append(" | ").append(esc(trim(d.summary(), 160))).append(" | ")
                    .append(esc(trim(d.rationale(), 220))).append(" |\n");
        }

        md.append("\n## Artifact lineage\n\n| Artifact | Hash | Produced by | Derived from |\n|---|---|---|---|\n");
        for (Artifact a : s.artifactHistory()) {
            md.append("| ").append(a.name()).append(" | `").append(a.shortHash()).append("` | ").append(a.producedBy())
                    .append(" | ").append(a.derivedFrom().stream().map(h -> "`" + h.substring(0, 12) + "`").toList()).append(" |\n");
        }

        md.append("\n## Files changed\n\n");
        s.appliedChanges().forEach((node, changes) -> {
            if (changes.isEmpty()) return;
            md.append("- **").append(node).append("** (commit `").append(trim(s.commits().getOrDefault(node, "-"), 12)).append("`)\n");
            for (FileChange c : changes) md.append("  - ").append(c.type()).append(" `").append(c.path()).append("`\n");
        });
        return md.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
