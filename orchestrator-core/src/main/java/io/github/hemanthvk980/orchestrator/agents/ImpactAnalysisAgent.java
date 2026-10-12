package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.gate.Gate;
import io.github.hemanthvk980.orchestrator.gate.GateResult;
import io.github.hemanthvk980.orchestrator.gate.Gates;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brownfield codebase reasoning: given the normalised requirements and the factual repo index, which
 * modules, files, APIs and data flows does this change touch, and what could regress? For a greenfield
 * repo this is answered deterministically without spending a model call.
 */
public final class ImpactAnalysisAgent implements Stage {

    public static final String ARTIFACT = "impact-analysis.json";

    private static final String ROLE = """
            Role: Senior engineer doing change-impact analysis on an existing codebase.
            Use ONLY the repository index and file excerpts provided. Every path in impactedFiles MUST exist in the index.
            Output schema:
            {
              "summary": string,
              "impactedFiles": [{"path": string, "why": string}],
              "newFiles": [{"path": string, "why": string}],
              "impactedApis": [{"method": string, "path": string, "change": "new|modified|behaviour-change", "backwardCompatible": boolean}],
              "dataFlowChanges": [string],
              "schemaChange": boolean,
              "regressionRisks": [{"area": string, "risk": string, "testFocus": string}]
            }
            """;

    private final String constraints;

    public ImpactAnalysisAgent(String constraints) {
        this.constraints = constraints;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        String indexJson = ctx.requireArtifact(RepoIndexStage.ARTIFACT);
        String requirements = ctx.requireArtifact(RequirementsAgent.ARTIFACT);
        Map<String, Object> index = Json.parseObject(indexJson);

        if (Boolean.TRUE.equals(index.get("greenfield"))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", "Greenfield: no existing source code, nothing to regress.");
            out.put("impactedFiles", List.of());
            out.put("newFiles", List.of());
            out.put("impactedApis", List.of());
            out.put("dataFlowChanges", List.of());
            out.put("schemaChange", true);
            out.put("regressionRisks", List.of());
            return StageResult.builder().artifact(ARTIFACT, Json.pretty(out))
                    .summary("greenfield — skipped model call (deterministic)").build();
        }

        StringBuilder prompt = new StringBuilder(AgentSupport.contextBlock(ctx, constraints));
        prompt.append("## Normalised requirements\n").append(requirements).append("\n\n");
        prompt.append("## Repository index\n").append(indexJson).append("\n\n");
        prompt.append("## Source excerpts (controllers, services, entities, migrations)\n");
        int budget = 60_000;
        for (String f : JsonPath.strings(index, "files")) {
            boolean relevant = f.endsWith(".java") && !f.contains("/test/") || f.endsWith(".sql") || f.endsWith("application.yml");
            if (!relevant) continue;
            String src = ctx.workspace().read(f).orElse("");
            if (src.length() > budget) break;
            budget -= src.length();
            prompt.append("### ").append(f).append("\n```\n").append(src).append("\n```\n");
        }
        Map<String, Object> out = AgentSupport.askJson(ctx, "impact-analysis", ROLE, prompt.toString());
        return StageResult.builder().artifact(ARTIFACT, Json.pretty(out))
                .summary(JsonPath.list(out, "impactedFiles").size() + " impacted files, "
                        + JsonPath.list(out, "newFiles").size() + " new files, schemaChange="
                        + out.get("schemaChange"))
                .build();
    }

    /** Exit gate: every impacted file the model names must actually exist (hallucination guard). */
    public static Gate impactedFilesExist() {
        String name = "impacted-files-exist";
        return Gates.of(name, ctx -> {
            String content = ctx.artifactOrNull(ARTIFACT);
            if (content == null) return GateResult.fail(name, "missing " + ARTIFACT);
            List<String> missing = new ArrayList<>();
            for (Map<String, Object> f : JsonPath.objects(Json.parseObject(content), "impactedFiles")) {
                String p = JsonPath.str(f, "path", "");
                if (ctx.workspace().read(p).isEmpty()) missing.add(p);
            }
            return missing.isEmpty()
                    ? GateResult.pass(name, "all impacted files exist")
                    : GateResult.fail(name, "model referenced files that do not exist: " + missing);
        });
    }
}
