package io.github.hemanthvk980.orchestrator.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-recorded human responses (from the scenario file) so a run is reproducible in CI and demos.
 * Actor is recorded as "&lt;name&gt;(scripted)" so the audit trail never pretends a live human approved.
 *
 * <p>Fail-closed: a stage with no scripted decision is REJECTED, and an unscripted question is left
 * unanswered (which keeps the ambiguity gate shut).
 */
public final class ScriptedHumanGateway implements HumanGateway {

    private final String approver;
    private final Map<String, List<ApprovalDecision>> decisionsByNode;
    private final Map<String, String> answers;
    private final List<ApprovalRequest> seen = Collections.synchronizedList(new ArrayList<>());

    public ScriptedHumanGateway(String approver, Map<String, List<ApprovalDecision>> decisionsByNode,
                                Map<String, String> answersByQuestionKeyword) {
        this.approver = approver + "(scripted)";
        this.decisionsByNode = new LinkedHashMap<>();
        decisionsByNode.forEach((k, v) -> this.decisionsByNode.put(k, new ArrayList<>(v)));
        this.answers = Map.copyOf(answersByQuestionKeyword);
    }

    @Override
    public synchronized ApprovalDecision requestApproval(ApprovalRequest request) {
        seen.add(request);
        List<ApprovalDecision> queue = decisionsByNode.getOrDefault(request.nodeId(),
                decisionsByNode.getOrDefault("*", List.of()));
        if (queue.isEmpty()) {
            return ApprovalDecision.rejected(approver, "no scripted approval for " + request.nodeId() + " (fail-closed)");
        }
        ApprovalDecision d = queue.size() > 1 ? queue.remove(0) : queue.get(0);
        return new ApprovalDecision(d.outcome(), approver, d.comment());
    }

    /** Matches each question against scripted keywords (case-insensitive) so answers survive rewording. */
    @Override
    public Map<String, String> clarify(String runId, String nodeId, List<String> questions) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String q : questions) {
            String lq = q.toLowerCase();
            answers.entrySet().stream()
                    .filter(e -> lq.contains(e.getKey().toLowerCase()))
                    .findFirst()
                    .ifPresent(e -> out.put(q, e.getValue()));
        }
        return out;
    }

    public List<ApprovalRequest> seenRequests() {
        return List.copyOf(seen);
    }
}
