package io.github.hemanthvk980.orchestrator.gate;

import io.github.hemanthvk980.orchestrator.audit.AuditLog;
import io.github.hemanthvk980.orchestrator.engine.RunState;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;

/** Inputs available to a gate. {@code result} is null for entry gates. */
public record GateContext(String nodeId, RunState state, Workspace workspace, AuditLog audit, StageResult result) {

    /** Artifact content as of now: the stage's fresh output (exit gates) takes precedence over stored state. */
    public String artifactOrNull(String name) {
        if (result != null && result.artifacts().containsKey(name)) return result.artifacts().get(name);
        return state.artifact(name).map(a -> a.content()).orElse(null);
    }
}
