package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the design's tasks for one area (impl | test | docs), one model call per task in dependency
 * order. Each call sees the design, its task, and the current content of the files it touches —
 * including edits proposed by earlier tasks in this stage (an in-memory overlay), so tasks build on
 * each other. All edits are returned together as one change set for policy checks and approval.
 *
 * <p>Three instances run in parallel (implementation, tests, docs); they share the design as their
 * contract and are synchronised again at the verify stage.
 */
public final class TaskExecutionAgent implements Stage {

    private final String area;
    private final String role;
    private final String constraints;
    private final int maxTasks;

    private TaskExecutionAgent(String area, String role, String constraints, int maxTasks) {
        this.area = area;
        this.role = role;
        this.constraints = constraints;
        this.maxTasks = maxTasks;
    }

    public static TaskExecutionAgent implementation(String constraints) {
        return new TaskExecutionAgent("impl", """
                Role: Senior Java/Spring engineer implementing ONE task from the design.
                Write complete, compilable, production-quality files (full file content, not diffs). Follow the API contract exactly.
                Validate inputs, handle errors with proper HTTP status codes, no secrets in code, configuration via environment.
                """ + SCHEMA, constraints, 12);
    }

    public static TaskExecutionAgent tests(String constraints) {
        return new TaskExecutionAgent("test", """
                Role: Test engineer writing tests for ONE task, from the API contract and acceptance criteria.
                Write JUnit 5 tests (unit + Spring Boot integration with MockMvc where relevant). Cover happy paths, validation
                failures, edge cases and every acceptance criterion referenced. Full file content.
                """ + SCHEMA, constraints, 8);
    }

    public static TaskExecutionAgent docs(String constraints) {
        return new TaskExecutionAgent("docs", """
                Role: Technical writer producing developer documentation for ONE task: README sections, API reference with
                example requests/responses, operational notes, CHANGELOG entry. Accurate to the design; no marketing.
                """ + SCHEMA, constraints, 4);
    }

    private static final String SCHEMA = """
            Output schema:
            {"files": [{"path": string, "content": string, "reason": string}], "notes": string}
            Use repository-relative paths. Only include files you create or change.
            """;

    @Override
    public StageResult execute(StageContext ctx) {
        Map<String, Object> design = Json.parseObject(ctx.requireArtifact(DesignAgent.ARTIFACT));
        String requirements = ctx.requireArtifact(RequirementsAgent.ARTIFACT);
        List<Map<String, Object>> tasks = TaskPlan.ofArea(TaskPlan.order(JsonPath.objects(design, "tasks")), area);
        if (tasks.size() > maxTasks) {
            throw new IllegalStateException(area + " has " + tasks.size() + " tasks; limit is " + maxTasks
                    + " — the design should be split into smaller increments");
        }

        Map<String, String> overlay = new HashMap<>();
        List<FileChange> all = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Object> designForPrompt = new LinkedHashMap<>(design);
        String context = AgentSupport.contextBlock(ctx, constraints);

        for (Map<String, Object> task : tasks) {
            ctx.checkpointCancellation();
            StringBuilder prompt = new StringBuilder(context);
            prompt.append("## Requirements\n").append(requirements).append("\n\n");
            prompt.append("## Design (contract)\n").append(Json.pretty(designForPrompt)).append("\n\n");
            prompt.append("## YOUR TASK\n").append(Json.pretty(task)).append("\n\n");
            prompt.append("## Current content of files this task touches\n");
            for (String f : JsonPath.strings(task, "files")) {
                String current = overlay.containsKey(f) ? overlay.get(f) : ctx.workspace().read(f).orElse(null);
                prompt.append("### ").append(f).append(current == null ? " (new file)\n" : "\n```\n" + current + "\n```\n");
            }
            prompt.append("\n## All files in repository\n");
            ctx.workspace().listFiles().stream().limit(300).forEach(f -> prompt.append("- ").append(f).append('\n'));
            overlay.keySet().stream().sorted().forEach(f -> prompt.append("- ").append(f).append(" (pending in this change set)\n"));

            String purpose = ctx.nodeId() + ":" + JsonPath.str(task, "id", "T?");
            Map<String, Object> out = AgentSupport.askJson(ctx, purpose, role, prompt.toString());
            all.addAll(AgentSupport.toChanges(out, ctx.workspace(), overlay));
            String n = JsonPath.str(out, "notes", "");
            if (!n.isBlank()) notes.add(task.get("id") + ": " + n);
        }

        List<FileChange> changes = AgentSupport.dedupe(all);
        enforceAreaScope(changes);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("area", area);
        report.put("tasks", tasks.stream().map(t -> t.get("id") + " " + t.get("title")).toList());
        report.put("files", changes);
        report.put("notes", notes);
        return StageResult.builder()
                .changes(changes)
                .artifact(ctx.nodeId() + "-report.json", Json.pretty(report))
                .summary(tasks.size() + " " + area + " task(s) → " + changes.size() + " file(s)")
                .build();
    }

    /**
     * Parallel branches must not write each other's files (that would make results depend on
     * scheduling order). Each area owns a disjoint slice of the tree.
     */
    private void enforceAreaScope(List<FileChange> changes) {
        List<String> violations = new ArrayList<>();
        for (FileChange c : changes) {
            String p = c.path();
            boolean isTest = p.contains("/test/") || p.startsWith("test/");
            boolean isDoc = p.endsWith(".md") || p.startsWith("docs/");
            boolean ok = switch (area) {
                case "test" -> isTest;
                case "docs" -> isDoc && !isTest;
                default -> !isTest && !isDoc;
            };
            if (!ok) violations.add(p);
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("area '" + area + "' may not write " + violations
                    + " (impl: production code/config; test: */test/*; docs: *.md, docs/)");
        }
    }
}
