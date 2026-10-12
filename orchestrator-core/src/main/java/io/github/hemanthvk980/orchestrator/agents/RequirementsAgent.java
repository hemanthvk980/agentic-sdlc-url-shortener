package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.Decision;
import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalises a raw requirement into an engineering specification and — crucially — separates what is
 * known from what is ambiguous. HIGH-impact ambiguities become blocking questions for a human;
 * lower-impact ones become explicit, logged assumptions with a proposed default.
 */
public final class RequirementsAgent implements Stage {

    public static final String ARTIFACT = "requirements.json";

    private static final String ROLE = """
            Role: Requirements analyst.
            Turn the requirement into a precise engineering problem statement. Identify every ambiguity and rate its impact:
            HIGH = a wrong guess would change the API contract, data model, security posture or cost materially;
            MEDIUM/LOW = a sensible default exists and is cheap to change later.
            If a human clarification answers an ambiguity, copy the answer into its "resolution" field.
            Output schema:
            {
              "problemStatement": string,
              "functionalRequirements": [{"id": "FR1", "text": string}],
              "nonFunctionalRequirements": [{"id": "NFR1", "category": "performance|security|reliability|observability|maintainability", "text": string}],
              "acceptanceCriteria": [{"id": "AC1", "given": string, "when": string, "then": string}],
              "ambiguities": [{"question": string, "impact": "HIGH|MEDIUM|LOW", "proposedDefault": string, "resolution": string}],
              "assumptions": [string],
              "outOfScope": [string]
            }
            """;

    private final String constraints;

    public RequirementsAgent(String constraints) {
        this.constraints = constraints;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        Map<String, Object> spec = AgentSupport.askJson(ctx, "requirements", ROLE, AgentSupport.contextBlock(ctx, constraints));

        // Engine-side reconciliation: never trust the model to have applied human answers.
        Map<String, String> clar = ctx.clarifications();
        StageResult.Builder out = StageResult.builder();
        List<Map<String, Object>> ambiguities = JsonPath.objects(spec, "ambiguities");
        for (Map<String, Object> a : ambiguities) {
            String q = JsonPath.str(a, "question", "");
            String answer = matchAnswer(q, clar);
            if (answer != null) a.put("resolution", answer);
            boolean resolved = !JsonPath.str(a, "resolution", "").isBlank();
            String impact = JsonPath.str(a, "impact", "LOW").toUpperCase();
            if (!resolved && impact.equals("HIGH")) {
                out.question(q + (a.get("proposedDefault") != null ? "  [proposed default: " + a.get("proposedDefault") + "]" : ""));
            } else if (!resolved) {
                a.put("resolution", "ASSUMED: " + JsonPath.str(a, "proposedDefault", "(none)"));
                out.decision(Decision.agent(ctx.nodeId(), Decision.Kind.ASSUMPTION, q,
                        "impact " + impact + "; default applied: " + JsonPath.str(a, "proposedDefault", "(none)")));
            }
        }
        for (String assumption : JsonPath.strings(spec, "assumptions")) {
            out.decision(Decision.agent(ctx.nodeId(), Decision.Kind.ASSUMPTION, assumption, "stated by requirements agent"));
        }
        Map<String, Object> normalized = new LinkedHashMap<>(spec);
        normalized.put("ambiguities", ambiguities);
        return out.artifact(ARTIFACT, Json.pretty(normalized))
                .summary(JsonPath.list(spec, "functionalRequirements").size() + " functional requirements, "
                        + JsonPath.list(spec, "acceptanceCriteria").size() + " acceptance criteria, "
                        + ambiguities.size() + " ambiguities")
                .build();
    }

    /** Questions are matched exactly, or by containment (the model may rephrase on re-execution). */
    static String matchAnswer(String question, Map<String, String> clarifications) {
        String q = normalize(question);
        for (Map.Entry<String, String> e : clarifications.entrySet()) {
            String k = normalize(e.getKey());
            if (k.equals(q) || k.contains(q) || q.contains(k)) return e.getValue();
        }
        return null;
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s*\\[proposed default:.*]$", "").toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
    }
}
