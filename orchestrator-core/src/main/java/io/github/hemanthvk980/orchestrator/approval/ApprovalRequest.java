package io.github.hemanthvk980.orchestrator.approval;

import java.util.List;

/**
 * What a human sees at a checkpoint: why approval is needed, what will change, and the agent's own
 * summary. Kept small and concrete so the approver can make a real decision rather than rubber-stamp.
 */
public record ApprovalRequest(String runId, String nodeId, String title, List<String> reasons,
                              List<String> changedPaths, String summary, String diffPreview) {
}
