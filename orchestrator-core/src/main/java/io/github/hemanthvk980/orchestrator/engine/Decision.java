package io.github.hemanthvk980.orchestrator.engine;

import io.github.hemanthvk980.orchestrator.json.JsonWritable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in the decision log: who decided what, and why. Agents, humans and the engine itself all
 * write here, so the final report can answer "why does the system look like this?".
 */
public record Decision(String nodeId, Kind kind, String actor, String summary, String rationale, Instant at)
        implements JsonWritable {

    public enum Kind {
        ASSUMPTION,
        CLARIFICATION,
        DESIGN_CHOICE,
        HUMAN_APPROVAL,
        HUMAN_REJECTION,
        CHANGES_REQUESTED,
        POLICY,
        RETRY,
        FALLBACK,
        ROLLBACK,
        REPLAN,
        SAFE_STOP
    }

    public static Decision agent(String nodeId, Kind kind, String summary, String rationale) {
        return new Decision(nodeId, kind, "agent:" + nodeId, summary, rationale, Instant.now());
    }

    public static Decision system(String nodeId, Kind kind, String summary, String rationale) {
        return new Decision(nodeId, kind, "system", summary, rationale, Instant.now());
    }

    public static Decision human(String nodeId, Kind kind, String who, String summary, String rationale) {
        return new Decision(nodeId, kind, "human:" + who, summary, rationale, Instant.now());
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at.toString());
        m.put("node", nodeId);
        m.put("kind", kind);
        m.put("actor", actor);
        m.put("summary", summary);
        m.put("rationale", rationale);
        return m;
    }
}
