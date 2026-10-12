package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.util.List;
import java.util.Map;

/**
 * Independent reviewer: sees only the requirements, the design and the final diff — not the other
 * agents' reasoning — and checks acceptance-criteria coverage, correctness and security. HIGH/CRITICAL
 * findings do not silently fail the run: they are escalated to a human who decides whether to accept
 * the risk (recorded) or reject.
 */
public final class ReviewAgent implements Stage {

    public static final String ARTIFACT = "review.json";

    private static final String ROLE = """
            Role: Independent senior code reviewer and security reviewer. You did not write this code.
            Check: every acceptance criterion is implemented AND tested; API matches the design; input validation;
            error handling; injection/SSRF/open-redirect risks; secrets; concurrency; maintainability.
            Severity: CRITICAL (exploitable/data loss), HIGH (requirement not met or likely production bug), MEDIUM, LOW.
            Output schema:
            {
              "verdict": "APPROVE|APPROVE_WITH_COMMENTS|REQUEST_CHANGES",
              "coverage": [{"criterion": "AC1", "implemented": boolean, "tested": boolean, "evidence": string}],
              "findings": [{"severity": "CRITICAL|HIGH|MEDIUM|LOW", "file": string, "issue": string, "suggestion": string}],
              "summary": string
            }
            """;

    private final String baseRevision;

    /** @param baseRevision the workspace revision before this run, so the reviewer sees only this run's diff */
    public ReviewAgent(String baseRevision) {
        this.baseRevision = baseRevision;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        String diff = ctx.workspace().diff(baseRevision, ctx.workspace().head());
        String prompt = "## Requirements\n" + ctx.requireArtifact(RequirementsAgent.ARTIFACT)
                + "\n\n## Design\n" + ctx.requireArtifact(DesignAgent.ARTIFACT)
                + "\n\n## Diff under review\n" + AgentSupport.truncate(diff, 120_000);
        Map<String, Object> review = AgentSupport.askJson(ctx, "review", ROLE, prompt);

        StageResult.Builder b = StageResult.builder().artifact(ARTIFACT, Json.pretty(review));
        List<Map<String, Object>> blocking = JsonPath.objects(review, "findings").stream()
                .filter(f -> List.of("HIGH", "CRITICAL").contains(JsonPath.str(f, "severity", "").toUpperCase()))
                .toList();
        long uncovered = JsonPath.objects(review, "coverage").stream()
                .filter(c -> !Boolean.TRUE.equals(c.get("implemented")) || !Boolean.TRUE.equals(c.get("tested")))
                .count();
        for (Map<String, Object> f : blocking) {
            b.approvalReason("review " + f.get("severity") + " @ " + f.get("file") + ": " + f.get("issue"));
        }
        if (uncovered > 0) b.approvalReason(uncovered + " acceptance criteria not fully implemented+tested");
        return b.summary(JsonPath.str(review, "verdict", "?") + " — " + JsonPath.list(review, "findings").size()
                + " findings (" + blocking.size() + " blocking), " + uncovered + " criteria not fully covered").build();
    }
}
