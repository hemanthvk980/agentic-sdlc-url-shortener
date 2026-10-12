package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Topological ordering of the design's task list (Kahn); cycles are rejected. */
public final class TaskPlan {

    private TaskPlan() {}

    public static List<Map<String, Object>> order(List<Map<String, Object>> tasks) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        tasks.forEach(t -> byId.put(JsonPath.str(t, "id", ""), t));
        Map<String, Integer> indeg = new LinkedHashMap<>();
        byId.forEach((id, t) -> indeg.put(id, (int) JsonPath.strings(t, "dependsOn").stream().filter(byId::containsKey).count()));
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> ready = new ArrayList<>(indeg.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList());
        while (!ready.isEmpty()) {
            String id = ready.remove(0);
            out.add(byId.get(id));
            for (Map.Entry<String, Map<String, Object>> e : byId.entrySet()) {
                if (JsonPath.strings(e.getValue(), "dependsOn").contains(id) && indeg.merge(e.getKey(), -1, Integer::sum) == 0) {
                    ready.add(e.getKey());
                }
            }
        }
        if (out.size() != byId.size()) throw new IllegalArgumentException("task dependency cycle in design");
        return out;
    }

    public static List<Map<String, Object>> ofArea(List<Map<String, Object>> ordered, String area) {
        return ordered.stream().filter(t -> area.equals(t.get("area"))).toList();
    }
}
