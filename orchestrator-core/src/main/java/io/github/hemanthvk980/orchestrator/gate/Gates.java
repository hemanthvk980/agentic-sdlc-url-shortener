package io.github.hemanthvk980.orchestrator.gate;

import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonException;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import io.github.hemanthvk980.orchestrator.workspace.CommandResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Library of reusable gates. */
public final class Gates {

    private Gates() {}

    public static Gate of(String name, java.util.function.Function<GateContext, GateResult> fn) {
        return new Gate() {
            @Override public String name() { return name; }
            @Override public GateResult evaluate(GateContext ctx) { return fn.apply(ctx); }
        };
    }

    /** The named artifact exists (upstream produced it / this stage produced it). */
    public static Gate artifactPresent(String artifact) {
        String name = "artifact-present:" + artifact;
        return of(name, ctx -> ctx.artifactOrNull(artifact) != null
                ? GateResult.pass(name, "found")
                : GateResult.fail(name, "missing artifact " + artifact));
    }

    /** The named artifact is a JSON object containing every required top-level key. */
    public static Gate jsonArtifactHasKeys(String artifact, String... keys) {
        String name = "schema:" + artifact;
        return of(name, ctx -> {
            String content = ctx.artifactOrNull(artifact);
            if (content == null) return GateResult.fail(name, "missing artifact " + artifact);
            try {
                Map<String, Object> obj = Json.parseObject(content);
                List<String> missing = new ArrayList<>();
                for (String k : keys) if (!obj.containsKey(k)) missing.add(k);
                return missing.isEmpty()
                        ? GateResult.pass(name, "all required keys present")
                        : GateResult.fail(name, "missing keys " + missing);
            } catch (JsonException e) {
                return GateResult.fail(name, "not valid JSON: " + e.getMessage());
            }
        });
    }

    /**
     * Requirements may not flow into design while any HIGH-impact ambiguity is unanswered. This is what
     * turns an ambiguous requirement into a human clarification checkpoint instead of a silent guess.
     */
    public static Gate noOpenHighImpactAmbiguities(String artifact) {
        String name = "ambiguities-resolved";
        return of(name, ctx -> {
            String content = ctx.artifactOrNull(artifact);
            if (content == null) return GateResult.fail(name, "missing " + artifact);
            List<String> open = new ArrayList<>();
            for (Map<String, Object> a : JsonPath.objects(Json.parseObject(content), "ambiguities")) {
                boolean high = "HIGH".equalsIgnoreCase(JsonPath.str(a, "impact", ""));
                boolean resolved = !JsonPath.str(a, "resolution", "").isBlank();
                if (high && !resolved) open.add(JsonPath.str(a, "question", "?"));
            }
            return open.isEmpty()
                    ? GateResult.pass(name, "no unresolved high-impact ambiguities")
                    : GateResult.fail(name, "unresolved: " + open);
        });
    }

    /** Runs a command in the workspace; passes on exit code 0. Used for build/test/coverage gates. */
    public static Gate commandSucceeds(String name, List<String> command, Duration timeout) {
        return of(name, ctx -> {
            CommandResult r = ctx.workspace().run(command, timeout);
            return r.exitCode() == 0
                    ? GateResult.pass(name, "exit 0 in " + r.durationMs() + "ms")
                    : GateResult.fail(name, "exit " + r.exitCode() + ":\n" + r.tail(60));
        });
    }

    /** Review findings: no unresolved HIGH/CRITICAL issue may pass to release. */
    public static Gate noBlockingFindings(String artifact) {
        String name = "review-no-blockers";
        return of(name, ctx -> {
            String content = ctx.artifactOrNull(artifact);
            if (content == null) return GateResult.fail(name, "missing " + artifact);
            Predicate<Map<String, Object>> blocking = f -> {
                String sev = JsonPath.str(f, "severity", "").toUpperCase();
                return sev.equals("HIGH") || sev.equals("CRITICAL");
            };
            List<String> blockers = JsonPath.objects(Json.parseObject(content), "findings").stream()
                    .filter(blocking)
                    .map(f -> JsonPath.str(f, "file", "?") + ": " + JsonPath.str(f, "issue", "?"))
                    .toList();
            return blockers.isEmpty()
                    ? GateResult.pass(name, "no HIGH/CRITICAL findings")
                    : GateResult.fail(name, "blocking findings: " + blockers);
        });
    }

    /** The tamper-evident audit chain verifies end to end. */
    public static Gate auditChainIntact() {
        String name = "audit-chain-intact";
        return of(name, ctx -> ctx.audit().verify()
                ? GateResult.pass(name, ctx.audit().size() + " events verified")
                : GateResult.fail(name, "audit log hash chain broken"));
    }
}
