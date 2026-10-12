package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonException;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import io.github.hemanthvk980.orchestrator.llm.LlmRequest;
import io.github.hemanthvk980.orchestrator.llm.LlmResponse;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared prompt assembly and output handling for LLM-backed stages. */
public final class AgentSupport {

    /** Common rules prepended to every agent's system prompt. */
    public static final String BASE_RULES = """
            You are one agent in a governed software-delivery pipeline. Other agents and humans depend on your output.
            Rules:
            - Respond with a single JSON object only, matching the requested schema exactly. No markdown fences.
            - Never invent facts about the existing codebase: only reference files that appear in the inputs.
            - Never put secrets, API keys or passwords in output; reference environment variables instead.
            - State assumptions explicitly rather than silently guessing.
            - Prefer the simplest design that satisfies the requirements; production quality, no placeholders or TODOs.
            """;

    private AgentSupport() {}

    /** Calls the model and parses its JSON, tolerating fences/commentary around the object. */
    public static Map<String, Object> askJson(StageContext ctx, String purpose, String role, String prompt) {
        ctx.checkpointCancellation();
        LlmResponse r = ctx.llm().complete(LlmRequest.json(purpose, BASE_RULES + "\n" + role, prompt));
        try {
            return Json.extractObject(r.text());
        } catch (JsonException e) {
            throw new IllegalStateException("model returned invalid JSON for " + purpose + ": " + e.getMessage(), e);
        }
    }

    /** Standard context block: requirement, constraints, human clarifications, reviewer feedback, last failure. */
    public static String contextBlock(StageContext ctx, String constraints) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original requirement\n").append(ctx.requirement().strip()).append("\n\n");
        if (constraints != null && !constraints.isBlank()) {
            sb.append("## Engineering constraints (non-negotiable)\n").append(constraints.strip()).append("\n\n");
        }
        Map<String, String> clar = ctx.clarifications();
        if (!clar.isEmpty()) {
            sb.append("## Human clarifications (authoritative)\n");
            clar.forEach((q, a) -> sb.append("- Q: ").append(q).append("\n  A: ").append(a).append('\n'));
            sb.append('\n');
        }
        List<String> fb = ctx.humanFeedback();
        if (!fb.isEmpty()) {
            sb.append("## Reviewer feedback on your previous output (address all of it)\n");
            fb.forEach(f -> sb.append("- ").append(f).append('\n'));
            sb.append('\n');
        }
        ctx.previousFailure().ifPresent(f -> sb.append("## Your previous attempt failed — fix this\n")
                .append(truncate(normalizeVolatile(f), 4000)).append("\n\n"));
        return sb.toString();
    }

    /** Converts a {@code files:[{path,content,reason}]} array into proposed changes. */
    public static List<FileChange> toChanges(Map<String, Object> out, Workspace ws, Map<String, String> overlay) {
        List<FileChange> changes = new ArrayList<>();
        for (Map<String, Object> f : JsonPath.objects(out, "files")) {
            String path = Workspace.normalize(JsonPath.str(f, "path", ""));
            String content = JsonPath.str(f, "content", null);
            boolean delete = Boolean.TRUE.equals(f.get("delete"));
            if (delete) {
                changes.add(new FileChange(path, FileChange.Type.DELETE, null, JsonPath.str(f, "reason", "")));
                overlay.remove(path);
                continue;
            }
            if (content == null) throw new IllegalStateException("file " + path + " has no content");
            boolean exists = overlay.containsKey(path) || ws.read(path).isPresent();
            changes.add(FileChange.upsert(path, content, JsonPath.str(f, "reason", ""), exists));
            overlay.put(path, content);
        }
        return changes;
    }

    /** Collapses repeated writes to the same path, keeping the last (tasks may refine earlier files). */
    public static List<FileChange> dedupe(List<FileChange> changes) {
        Map<String, FileChange> byPath = new java.util.LinkedHashMap<>();
        for (FileChange c : changes) {
            FileChange prev = byPath.get(c.path());
            FileChange.Type type = prev != null && prev.type() == FileChange.Type.ADD && c.type() != FileChange.Type.DELETE
                    ? FileChange.Type.ADD : c.type();
            byPath.remove(c.path());
            byPath.put(c.path(), new FileChange(c.path(), type, c.content(), c.reason()));
        }
        return List.copyOf(byPath.values());
    }

    /**
     * Removes run-specific noise (timings, absolute workspace paths) from tool output before it goes
     * into a prompt. Keeps prompts deterministic for record/replay and avoids leaking local paths.
     */
    public static String normalizeVolatile(String s) {
        if (s == null) return "";
        return s.replaceAll("\\S*/workspace/", "")
                .replaceAll("\\b\\d+(\\.\\d+)?\\s?(ms|s)\\b", "<t>");
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…[truncated " + (s.length() - max) + " chars]";
    }
}
