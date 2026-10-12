package io.github.hemanthvk980.orchestrator.engine;

import io.github.hemanthvk980.orchestrator.graph.NodeStatus;
import io.github.hemanthvk980.orchestrator.graph.TaskGraph;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shared, cross-stage memory of a run. Every stage reads its inputs from here and the engine
 * records every output, read, decision and status transition, which is what makes the run stateful,
 * resumable for re-planning, and auditable. All mutators are thread-safe (parallel branches write).
 */
public final class RunState {

    public enum Outcome { RUNNING, SUCCEEDED, FAILED, STOPPED }

    private final String runId;
    private final String scenario;
    private final String requirement;
    private final Instant startedAt = Instant.now();

    private final Map<String, NodeStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();
    private final List<Artifact> artifactHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Map<String, String>> inputHashes = new ConcurrentHashMap<>();
    private final List<Decision> decisions = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<String, String> summaries = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final Map<String, String> clarifications = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, List<String>> feedback = new ConcurrentHashMap<>();
    private final Map<String, List<FileChange>> appliedChanges = new ConcurrentHashMap<>();
    private final Map<String, String> commits = new ConcurrentHashMap<>();
    private volatile Outcome outcome = Outcome.RUNNING;
    private volatile String outcomeReason = "";
    private volatile Instant finishedAt;

    public RunState(String runId, String scenario, String requirement, TaskGraph graph) {
        this.runId = runId;
        this.scenario = scenario;
        this.requirement = requirement;
        graph.nodes().forEach(n -> statuses.put(n.id(), NodeStatus.PENDING));
    }

    public String runId() { return runId; }
    public String scenario() { return scenario; }
    public String requirement() { return requirement; }
    public Instant startedAt() { return startedAt; }

    // ---- statuses
    public NodeStatus status(String nodeId) { return statuses.getOrDefault(nodeId, NodeStatus.PENDING); }
    public void setStatus(String nodeId, NodeStatus s) { statuses.put(nodeId, s); }
    public Map<String, NodeStatus> statuses() { return Map.copyOf(statuses); }

    // ---- artifacts & lineage
    public Artifact putArtifact(String name, String content, String producedBy, List<String> derivedFrom) {
        Artifact a = Artifact.of(name, content, producedBy, derivedFrom);
        artifacts.put(name, a);
        artifactHistory.add(a);
        return a;
    }

    public Optional<Artifact> artifact(String name) { return Optional.ofNullable(artifacts.get(name)); }
    public Map<String, Artifact> artifacts() { return Map.copyOf(artifacts); }
    public List<Artifact> artifactHistory() { synchronized (artifactHistory) { return List.copyOf(artifactHistory); } }

    /** Records that {@code nodeId} consumed {@code artifact} at its current hash. */
    public void recordRead(String nodeId, Artifact artifact) {
        inputHashes.computeIfAbsent(nodeId, k -> new ConcurrentHashMap<>()).put(artifact.name(), artifact.hash());
    }

    public Map<String, String> inputHashes(String nodeId) {
        return Map.copyOf(inputHashes.getOrDefault(nodeId, Map.of()));
    }

    public void clearInputs(String nodeId) { inputHashes.remove(nodeId); }

    /** Nodes whose recorded input hash for {@code artifactName} no longer matches the current artifact. */
    public Set<String> staleConsumersOf(String artifactName) {
        Set<String> stale = new LinkedHashSet<>();
        Artifact current = artifacts.get(artifactName);
        inputHashes.forEach((node, inputs) -> {
            String seen = inputs.get(artifactName);
            if (seen != null && (current == null || !seen.equals(current.hash()))) stale.add(node);
        });
        return stale;
    }

    // ---- decisions
    public void addDecision(Decision d) { decisions.add(d); }
    public List<Decision> decisions() { synchronized (decisions) { return List.copyOf(decisions); } }

    // ---- attempts, summaries, errors
    public int incrementAttempts(String nodeId) { return attempts.merge(nodeId, 1, Integer::sum); }
    public int attempts(String nodeId) { return attempts.getOrDefault(nodeId, 0); }
    public void setSummary(String nodeId, String s) { summaries.put(nodeId, s); }
    public String summary(String nodeId) { return summaries.getOrDefault(nodeId, ""); }
    public void setError(String nodeId, String e) { errors.put(nodeId, e); }
    public void clearError(String nodeId) { errors.remove(nodeId); }
    public Map<String, String> errors() { return Map.copyOf(errors); }

    // ---- human input
    public void addClarification(String question, String answer) { clarifications.put(question, answer); }
    public Map<String, String> clarifications() { synchronized (clarifications) { return new LinkedHashMap<>(clarifications); } }
    public void addFeedback(String nodeId, String text) {
        feedback.computeIfAbsent(nodeId, k -> Collections.synchronizedList(new ArrayList<>())).add(text);
    }
    public List<String> feedback(String nodeId) { return List.copyOf(feedback.getOrDefault(nodeId, List.of())); }

    // ---- workspace effects
    public void recordApplied(String nodeId, List<FileChange> changes, String commit) {
        appliedChanges.put(nodeId, List.copyOf(changes));
        if (commit != null) commits.put(nodeId, commit);
    }
    public Map<String, List<FileChange>> appliedChanges() { return Map.copyOf(appliedChanges); }
    public Map<String, String> commits() { return Map.copyOf(commits); }

    // ---- outcome
    public Outcome outcome() { return outcome; }
    public String outcomeReason() { return outcomeReason; }
    public Instant finishedAt() { return finishedAt; }
    public void finish(Outcome o, String reason) {
        this.outcome = o;
        this.outcomeReason = reason;
        this.finishedAt = Instant.now();
    }
    public void reopen() {
        this.outcome = Outcome.RUNNING;
        this.outcomeReason = "";
        this.finishedAt = null;
    }
}
