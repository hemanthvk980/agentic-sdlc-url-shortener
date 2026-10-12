package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.List;

/** A guardrail evaluated against every proposed change set before it may touch the workspace. */
public interface Policy {

    String id();

    List<PolicyFinding> evaluate(String nodeId, List<FileChange> changes, Workspace workspace);
}
