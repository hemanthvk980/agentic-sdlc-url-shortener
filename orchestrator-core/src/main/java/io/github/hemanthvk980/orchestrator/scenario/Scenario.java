package io.github.hemanthvk980.orchestrator.scenario;

import io.github.hemanthvk980.orchestrator.approval.ApprovalDecision;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A run definition loaded from {@code scenarios/<name>/scenario.json}: the requirement, its type
 * (greenfield / brownfield / ambiguous), workspace seeding (repo-relative paths copied in), scope, verification command, budgets and
 * the scripted human responses used for non-interactive runs.
 */
public record Scenario(
        String name,
        String type,
        String requirement,
        String constraints,
        List<String> seedPaths,
        List<String> allowedPaths,
        List<String> verifyCommand,
        List<String> offlineVerifyCommand,
        int verifyTimeoutSeconds,
        int maxLlmCalls,
        int maxMinutes,
        String approver,
        Map<String, List<ApprovalDecision>> scriptedApprovals,
        Map<String, String> scriptedAnswers,
        Path dir) {

    public static Scenario load(Path scenarioFile) throws IOException {
        Map<String, Object> j = Json.parseObject(Files.readString(scenarioFile));
        Map<String, Object> human = JsonPath.obj(j, "human");
        Map<String, List<ApprovalDecision>> approvals = new LinkedHashMap<>();
        JsonPath.obj(human, "approvals").forEach((node, v) -> {
            List<ApprovalDecision> list = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Object> items = v instanceof List<?> l ? (List<Object>) l : List.of(v);
            for (Object o : items) {
                String s = String.valueOf(o);
                String[] parts = s.split(":", 2);
                String comment = parts.length > 1 ? parts[1].trim() : "";
                list.add(new ApprovalDecision(ApprovalDecision.Outcome.valueOf(parts[0].trim()), "", comment));
            }
            approvals.put(node, list);
        });
        Map<String, String> answers = new LinkedHashMap<>();
        JsonPath.obj(human, "answers").forEach((k, v) -> answers.put(k, String.valueOf(v)));
        return new Scenario(
                JsonPath.str(j, "name", scenarioFile.getParent().getFileName().toString()),
                JsonPath.str(j, "type", "greenfield"),
                JsonPath.str(j, "requirement", ""),
                JsonPath.str(j, "constraints", ""),
                JsonPath.strings(j, "seedPaths"),
                JsonPath.strings(j, "allowedPaths"),
                JsonPath.strings(j, "verifyCommand"),
                JsonPath.strings(j, "offlineVerifyCommand"),
                ((Number) j.getOrDefault("verifyTimeoutSeconds", 600L)).intValue(),
                ((Number) j.getOrDefault("maxLlmCalls", 40L)).intValue(),
                ((Number) j.getOrDefault("maxMinutes", 30L)).intValue(),
                JsonPath.str(human, "approver", "reviewer"),
                approvals,
                answers,
                scenarioFile.getParent());
    }
}
