package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.Decision;
import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.gate.Gate;
import io.github.hemanthvk980.orchestrator.gate.GateResult;
import io.github.hemanthvk980.orchestrator.gate.Gates;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Architecture/design + task decomposition. Produces API contract, data model changes, ADRs, a risk
 * register and an ordered task list (with dependencies) that the implementation, test and docs agents
 * execute. This is the main human sign-off point before any code is written.
 */
public final class DesignAgent implements Stage {

    public static final String ARTIFACT = "design.json";

    private static final String ROLE = """
            Role: Software architect. Produce a design that a team could implement without further questions.
            Decompose the work into small tasks (each touching a few files) with explicit dependencies.
            Each task has an area: "impl" (production code/config/migrations), "test" (tests only) or "docs".
            Tests must be derivable from the API contract and acceptance criteria alone (they are written in parallel with the code).
            Output schema:
            {
              "summary": string,
              "components": [{"name": string, "responsibility": string, "files": [string]}],
              "api": [{"method": string, "path": string, "request": string, "responses": [string], "notes": string}],
              "dataModel": [{"entity": string, "change": string, "migration": string}],
              "adrs": [{"title": string, "decision": string, "alternatives": [string], "consequences": string}],
              "risks": [{"risk": string, "likelihood": "LOW|MEDIUM|HIGH", "impact": "LOW|MEDIUM|HIGH", "mitigation": string}],
              "tasks": [{"id": "T1", "title": string, "area": "impl|test|docs", "files": [string], "dependsOn": [string], "acceptance": [string]}]
            }
            """;

    private final String constraints;

    public DesignAgent(String constraints) {
        this.constraints = constraints;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        StringBuilder prompt = new StringBuilder(AgentSupport.contextBlock(ctx, constraints));
        prompt.append("## Normalised requirements\n").append(ctx.requireArtifact(RequirementsAgent.ARTIFACT)).append("\n\n");
        prompt.append("## Impact analysis\n").append(ctx.requireArtifact(ImpactAnalysisAgent.ARTIFACT)).append("\n\n");
        prompt.append("## Existing files\n");
        ctx.workspace().listFiles().stream().limit(300).forEach(f -> prompt.append("- ").append(f).append('\n'));

        Map<String, Object> design = AgentSupport.askJson(ctx, "design", ROLE, prompt.toString());
        StageResult.Builder out = StageResult.builder().artifact(ARTIFACT, Json.pretty(design));
        for (Map<String, Object> adr : JsonPath.objects(design, "adrs")) {
            out.decision(Decision.agent(ctx.nodeId(), Decision.Kind.DESIGN_CHOICE, JsonPath.str(adr, "title", "ADR"),
                    JsonPath.str(adr, "decision", "") + " | alternatives: " + JsonPath.strings(adr, "alternatives")
                            + " | consequences: " + JsonPath.str(adr, "consequences", "")));
        }
        long highRisks = JsonPath.objects(design, "risks").stream()
                .filter(r -> "HIGH".equalsIgnoreCase(JsonPath.str(r, "impact", ""))).count();
        if (highRisks > 0) out.approvalReason(highRisks + " HIGH-impact risk(s) in the design risk register");
        return out.summary(JsonPath.list(design, "tasks").size() + " tasks, " + JsonPath.list(design, "api").size()
                + " API operations, " + JsonPath.list(design, "adrs").size() + " ADRs").build();
    }

    /** Exit gate: the task plan is itself a valid DAG with known areas and at least one implementation task. */
    public static Gate taskPlanValid() {
        String name = "task-plan-valid";
        return Gates.of(name, ctx -> {
            String content = ctx.artifactOrNull(ARTIFACT);
            if (content == null) return GateResult.fail(name, "missing design");
            List<Map<String, Object>> tasks = JsonPath.objects(Json.parseObject(content), "tasks");
            if (tasks.isEmpty()) return GateResult.fail(name, "design has no tasks");
            Set<String> ids = new HashSet<>();
            List<String> problems = new ArrayList<>();
            for (Map<String, Object> t : tasks) {
                if (!ids.add(JsonPath.str(t, "id", ""))) problems.add("duplicate task id " + t.get("id"));
                String area = JsonPath.str(t, "area", "");
                if (!Set.of("impl", "test", "docs").contains(area)) problems.add(t.get("id") + " has unknown area '" + area + "'");
            }
            for (Map<String, Object> t : tasks) {
                for (String d : JsonPath.strings(t, "dependsOn")) {
                    if (!ids.contains(d)) problems.add(t.get("id") + " depends on unknown " + d);
                }
            }
            if (tasks.stream().noneMatch(t -> "impl".equals(t.get("area")))) problems.add("no impl tasks");
            try {
                TaskPlan.order(tasks);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
            return problems.isEmpty() ? GateResult.pass(name, tasks.size() + " tasks form a valid DAG")
                    : GateResult.fail(name, String.join("; ", problems));
        });
    }

    public static Gate schema() {
        return Gates.jsonArtifactHasKeys(ARTIFACT, "summary", "api", "tasks", "risks", "adrs");
    }
}
