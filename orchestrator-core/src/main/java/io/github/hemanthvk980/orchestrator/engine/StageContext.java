package io.github.hemanthvk980.orchestrator.engine;

import io.github.hemanthvk980.orchestrator.llm.LlmClient;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a stage may see and use. Reads go through {@link #artifact(String)} so the engine can
 * record exactly which upstream outputs (at which hash) shaped this stage — the basis of lineage and
 * of targeted re-planning. Stages have no write access to state; they return a {@link StageResult}.
 */
public final class StageContext {

    private final String nodeId;
    private final int attempt;
    private final boolean fallback;
    private final RunState state;
    private final LlmClient llm;
    private final Workspace workspace;
    private final KillSwitch killSwitch;
    private final String previousFailure;

    public StageContext(String nodeId, int attempt, boolean fallback, RunState state, LlmClient llm,
                        Workspace workspace, KillSwitch killSwitch, String previousFailure) {
        this.nodeId = nodeId;
        this.attempt = attempt;
        this.fallback = fallback;
        this.state = state;
        this.llm = llm;
        this.workspace = workspace;
        this.killSwitch = killSwitch;
        this.previousFailure = previousFailure;
    }

    public String nodeId() { return nodeId; }
    public String runId() { return state.runId(); }
    public int attempt() { return attempt; }
    public boolean isFallback() { return fallback; }
    public String requirement() { return state.requirement(); }
    public LlmClient llm() { return llm; }
    public Workspace workspace() { return workspace; }

    /** Failure message from the previous attempt (gate output, build log...), for self-repair prompts. */
    public Optional<String> previousFailure() { return Optional.ofNullable(previousFailure); }

    public Optional<String> artifact(String name) {
        return state.artifact(name).map(a -> {
            state.recordRead(nodeId, a);
            return a.content();
        });
    }

    public String requireArtifact(String name) {
        return artifact(name).orElseThrow(() -> new IllegalStateException(
                "stage '" + nodeId + "' requires artifact '" + name + "' which has not been produced"));
    }

    public Map<String, String> clarifications() { return state.clarifications(); }
    public List<String> humanFeedback() { return state.feedback(nodeId); }

    /** Cooperative cancellation point for long-running stages. */
    public void checkpointCancellation() {
        killSwitch.throwIfStopped();
    }
}
