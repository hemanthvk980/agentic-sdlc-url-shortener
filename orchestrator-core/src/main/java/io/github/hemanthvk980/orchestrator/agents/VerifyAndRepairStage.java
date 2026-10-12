package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.util.List;
import java.util.Map;

/**
 * Join point for the parallel implementation/test/docs branches.
 *
 * <p>The real check is the stage's exit gate (the build + test command). On the first attempt this
 * stage proposes nothing, so the gate simply verifies the merged result. If the gate fails, the engine
 * rolls back and retries with the build log as {@code previousFailure}; this stage then asks the model
 * for a minimal fix, which the gate re-verifies. Bounded by the node's retry policy — a build that
 * cannot be repaired in N attempts stops and goes back to a human.
 */
public final class VerifyAndRepairStage implements Stage {

    private static final String ROLE = """
            Role: Build doctor. The build/test command failed after a multi-agent change. Make the SMALLEST change that
            fixes the failure. Do not delete or weaken tests to make them pass; fix production code unless the test itself
            contradicts the API contract (then explain in notes). Full file content for each changed file.
            Output schema: {"diagnosis": string, "files": [{"path": string, "content": string, "reason": string}], "notes": string}
            """;

    private final String constraints;

    public VerifyAndRepairStage(String constraints) {
        this.constraints = constraints;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        if (ctx.previousFailure().isEmpty()) {
            return StageResult.ofSummary("verifying merged change set");
        }
        String log = AgentSupport.normalizeVolatile(ctx.previousFailure().get());
        StringBuilder prompt = new StringBuilder(AgentSupport.contextBlock(ctx, constraints));
        prompt.append("## Design (contract)\n").append(ctx.requireArtifact(DesignAgent.ARTIFACT)).append("\n\n");
        prompt.append("## Files mentioned in the failure\n");
        int included = 0;
        for (String f : ctx.workspace().listFiles()) {
            String name = f.substring(f.lastIndexOf('/') + 1);
            if (included < 8 && name.contains(".") && log.contains(name)) {
                included++;
                prompt.append("### ").append(f).append("\n```\n")
                        .append(AgentSupport.truncate(ctx.workspace().read(f).orElse(""), 12_000)).append("\n```\n");
            }
        }
        Map<String, Object> out = AgentSupport.askJson(ctx, "verify-repair", ROLE, prompt.toString());
        var changes = AgentSupport.toChanges(out, ctx.workspace(), new java.util.HashMap<>());
        List<String> testDeletes = changes.stream()
                .filter(c -> c.path().contains("/test/") && c.type() == io.github.hemanthvk980.orchestrator.engine.FileChange.Type.DELETE)
                .map(c -> c.path()).toList();
        StageResult.Builder b = StageResult.builder().changes(changes)
                .summary("repair attempt " + ctx.attempt() + ": " + JsonPath.str(out, "diagnosis", ""));
        if (changes.stream().anyMatch(c -> c.path().contains("/test/"))) {
            // Changing tests to get green is exactly the shortcut a human should see.
            b.approvalReason("repair modifies test files " + changes.stream().filter(c -> c.path().contains("/test/"))
                    .map(c -> c.path()).toList() + (testDeletes.isEmpty() ? "" : " (DELETES " + testDeletes + ")"));
        }
        return b.build();
    }
}
