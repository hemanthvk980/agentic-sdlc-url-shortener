package io.github.hemanthvk980.orchestrator.approval;

import java.util.List;
import java.util.Map;

/**
 * The single channel through which the system asks a human for anything. Implementations: console
 * (interactive), scripted (recorded human answers for reproducible demos/CI) and deny-all (unattended
 * runs fail closed rather than self-approve).
 */
public interface HumanGateway {

    ApprovalDecision requestApproval(ApprovalRequest request);

    /** Ask blocking clarification questions; returns question → answer (missing answer = unanswered). */
    Map<String, String> clarify(String runId, String nodeId, List<String> questions);
}
