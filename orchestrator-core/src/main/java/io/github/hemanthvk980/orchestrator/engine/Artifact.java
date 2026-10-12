package io.github.hemanthvk980.orchestrator.engine;

import io.github.hemanthvk980.orchestrator.json.JsonWritable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named, content-addressed output of a stage (requirements spec, design, test plan, report...).
 *
 * <p>{@code derivedFrom} holds the hashes of the artifacts the producing stage read. That is the
 * decision lineage: any artifact can be traced back through every input that shaped it, and a
 * change to an upstream hash identifies exactly what must be re-planned.
 */
public record Artifact(String name, String content, String hash, String producedBy, List<String> derivedFrom,
                       Instant createdAt) implements JsonWritable {

    public static Artifact of(String name, String content, String producedBy, List<String> derivedFrom) {
        return new Artifact(name, content, Hashing.sha256(content), producedBy, List.copyOf(derivedFrom), Instant.now());
    }

    public String shortHash() {
        return hash.substring(0, 12);
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("hash", hash);
        m.put("producedBy", producedBy);
        m.put("derivedFrom", derivedFrom);
        m.put("createdAt", createdAt.toString());
        m.put("bytes", content.length());
        return m;
    }
}
