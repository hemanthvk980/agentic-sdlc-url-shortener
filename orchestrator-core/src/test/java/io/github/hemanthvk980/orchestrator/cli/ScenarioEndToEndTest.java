package io.github.hemanthvk980.orchestrator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hemanthvk980.orchestrator.audit.AuditLog;
import io.github.hemanthvk980.orchestrator.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the three assignment scenarios end to end in offline mode (hand-written fixtures, real git
 * workspace, real policies/gates/approvals/audit). Requires {@code git} and {@code sh} on PATH.
 */
class ScenarioEndToEndTest {

    @TempDir Path out;

    private Map<String, Object> run(String scenario, boolean replan) throws Exception {
        Map<String, String> opts = new java.util.HashMap<>(Map.of(
                "scenario", "../scenarios/" + scenario + "/scenario.json",
                "llm", "offline",
                "out", out.toString(),
                "repo-root", ".."));
        if (replan) opts.put("replan", "true");
        assertEquals(0, OrchestratorCli.run(opts), scenario + " should succeed");
        Path dir;
        try (Stream<Path> s = Files.list(out)) {
            dir = s.filter(p -> p.getFileName().toString().startsWith(scenario)).findFirst().orElseThrow();
        }
        assertTrue(AuditLog.verifyFile(dir.resolve("audit.jsonl")), "audit chain must verify");
        assertTrue(Files.readString(dir.resolve("report.md")).contains("SUCCEEDED"));
        assertTrue(Files.size(dir.resolve("changes.patch")) > 0);
        return Json.parseObject(Files.readString(dir.resolve("run.json")));
    }

    @SuppressWarnings("unchecked")
    private static long metric(Map<String, Object> run, String key) {
        return ((Number) ((Map<String, Object>) run.get("metrics")).get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> decisions(Map<String, Object> run) {
        return (List<Map<String, Object>>) run.get("decisions");
    }

    @Test
    void greenfield_policyBlockThenRepairLoop() throws Exception {
        Map<String, Object> run = run("greenfield", false);
        assertEquals(1, metric(run, "policyBlocks"), "hard-coded password is blocked once, then fixed");
        assertEquals(1, metric(run, "rollbacks"), "broken build is rolled back once, then repaired");
        assertEquals(3, metric(run, "approvalsGranted"));
    }

    @Test
    void brownfield_hallucinationGuardAndRiskAcceptance() throws Exception {
        Map<String, Object> run = run("brownfield", false);
        assertTrue(metric(run, "retries") >= 1, "impact analysis referencing a non-existent file is retried");
        assertTrue(decisions(run).stream().anyMatch(d -> "review".equals(d.get("node")) && "HUMAN_APPROVAL".equals(d.get("kind"))),
                "HIGH review finding escalated to a human who accepted the risk");
    }

    @Test
    void ambiguous_clarificationChangesRequestedAndTargetedReplan() throws Exception {
        Map<String, Object> run = run("ambiguous", true);
        assertTrue(decisions(run).stream().anyMatch(d -> "CLARIFICATION".equals(d.get("kind"))));
        assertTrue(decisions(run).stream().anyMatch(d -> "CHANGES_REQUESTED".equals(d.get("kind"))));
        assertTrue(metric(run, "replans") >= 2, "clarification re-plan + upstream-change re-plan");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> nodes = (Map<String, Map<String, Object>>) (Map<?, ?>) run.get("nodes");
        assertEquals(1L, ((Number) nodes.get("repo-index").get("attempts")).longValue(), "unaffected branch not re-run");
    }
}
