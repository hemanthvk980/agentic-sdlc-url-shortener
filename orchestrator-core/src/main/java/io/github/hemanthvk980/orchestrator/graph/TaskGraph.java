package io.github.hemanthvk980.orchestrator.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, validated dependency graph (a DAG). Construction fails fast on unknown dependencies,
 * duplicate ids or cycles, so an invalid plan can never start executing.
 */
public final class TaskGraph {

    private final Map<String, StageNode> nodes;
    private final Map<String, List<String>> dependents;
    private final List<List<String>> layers;

    private TaskGraph(Map<String, StageNode> nodes) {
        this.nodes = Map.copyOf(nodes);
        this.dependents = buildDependents(nodes);
        this.layers = computeLayers(nodes);
    }

    public static TaskGraph of(Collection<StageNode> nodeList) {
        Map<String, StageNode> map = new LinkedHashMap<>();
        for (StageNode n : nodeList) {
            if (map.put(n.id(), n) != null) throw new GraphValidationException("duplicate node id: " + n.id());
        }
        for (StageNode n : nodeList) {
            for (String d : n.dependsOn()) {
                if (!map.containsKey(d)) {
                    throw new GraphValidationException("node '" + n.id() + "' depends on unknown node '" + d + "'");
                }
                if (d.equals(n.id())) throw new GraphValidationException("node '" + n.id() + "' depends on itself");
            }
        }
        return new TaskGraph(map);
    }

    public StageNode node(String id) {
        StageNode n = nodes.get(id);
        if (n == null) throw new IllegalArgumentException("unknown node " + id);
        return n;
    }

    public Collection<StageNode> nodes() {
        List<StageNode> ordered = new ArrayList<>();
        for (List<String> layer : layers) for (String id : layer) ordered.add(nodes.get(id));
        return ordered;
    }

    public boolean contains(String id) { return nodes.containsKey(id); }

    /** Execution layers: every node in layer i depends only on nodes in layers &lt; i (parallelizable). */
    public List<List<String>> layers() { return layers; }

    public List<String> directDependents(String id) { return dependents.getOrDefault(id, List.of()); }

    /** All nodes reachable downstream of {@code id} (excluding itself), in topological order. */
    public List<String> transitiveDependents(String id) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(directDependents(id));
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (seen.add(n)) queue.addAll(directDependents(n));
        }
        List<String> ordered = new ArrayList<>();
        for (List<String> layer : layers) for (String n : layer) if (seen.contains(n)) ordered.add(n);
        return ordered;
    }

    private static Map<String, List<String>> buildDependents(Map<String, StageNode> nodes) {
        Map<String, List<String>> deps = new HashMap<>();
        for (StageNode n : nodes.values()) {
            for (String d : n.dependsOn()) deps.computeIfAbsent(d, k -> new ArrayList<>()).add(n.id());
        }
        deps.replaceAll((k, v) -> List.copyOf(v));
        return deps;
    }

    /** Kahn's algorithm, grouped by depth. Detects cycles. */
    private static List<List<String>> computeLayers(Map<String, StageNode> nodes) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (StageNode n : nodes.values()) indegree.put(n.id(), n.dependsOn().size());
        Map<String, List<String>> dependents = buildDependents(nodes);

        List<List<String>> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        indegree.forEach((id, deg) -> { if (deg == 0) current.add(id); });
        int visited = 0;
        List<String> layer = current;
        while (!layer.isEmpty()) {
            result.add(List.copyOf(layer));
            visited += layer.size();
            List<String> next = new ArrayList<>();
            for (String id : layer) {
                for (String dep : dependents.getOrDefault(id, List.of())) {
                    if (indegree.merge(dep, -1, Integer::sum) == 0) next.add(dep);
                }
            }
            layer = next;
        }
        if (visited != nodes.size()) {
            List<String> cyclic = indegree.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
            throw new GraphValidationException("dependency cycle detected among: " + cyclic);
        }
        return List.copyOf(result);
    }

    /** Mermaid flowchart of the graph (embedded in run reports so reviewers can see the plan). */
    public String toMermaid(Map<String, NodeStatus> statuses) {
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        for (StageNode n : nodes()) {
            String label = n.title() + (n.requiresApproval() ? " 🔒" : "");
            sb.append("  ").append(safe(n.id())).append("[\"").append(label.replace("\"", "'")).append("\"]\n");
            for (String d : n.dependsOn()) sb.append("  ").append(safe(d)).append(" --> ").append(safe(n.id())).append('\n');
        }
        if (statuses != null) {
            sb.append("  classDef ok fill:#d4edda,stroke:#28a745\n");
            sb.append("  classDef bad fill:#f8d7da,stroke:#dc3545\n");
            sb.append("  classDef skip fill:#eeeeee,stroke:#999999\n");
            for (StageNode n : nodes()) {
                NodeStatus st = statuses.get(n.id());
                String cls = st == null ? null : switch (st) {
                    case SUCCEEDED -> "ok";
                    case FAILED, CANCELLED -> "bad";
                    case SKIPPED -> "skip";
                    default -> null;
                };
                if (cls != null) sb.append("  class ").append(safe(n.id())).append(' ').append(cls).append('\n');
            }
        }
        return sb.toString();
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
