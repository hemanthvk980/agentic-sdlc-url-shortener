package io.github.hemanthvk980.orchestrator.engine;

import io.github.hemanthvk980.orchestrator.json.JsonWritable;
import java.util.LinkedHashMap;
import java.util.Map;

/** A file modification proposed by an agent. Nothing touches the workspace until policies and approvals pass. */
public record FileChange(String path, Type type, String content, String reason) implements JsonWritable {

    public enum Type { ADD, MODIFY, DELETE }

    public static FileChange upsert(String path, String content, String reason, boolean exists) {
        return new FileChange(path, exists ? Type.MODIFY : Type.ADD, content, reason);
    }

    public int lineCount() {
        return content == null ? 0 : (int) content.lines().count();
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("type", type);
        m.put("lines", lineCount());
        m.put("reason", reason);
        return m;
    }
}
