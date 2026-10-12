package io.github.hemanthvk980.orchestrator.approval;

public record ApprovalDecision(Outcome outcome, String approver, String comment) {

    public enum Outcome { APPROVED, REJECTED, CHANGES_REQUESTED }

    public static ApprovalDecision approved(String who, String comment) {
        return new ApprovalDecision(Outcome.APPROVED, who, comment);
    }

    public static ApprovalDecision rejected(String who, String comment) {
        return new ApprovalDecision(Outcome.REJECTED, who, comment);
    }

    public static ApprovalDecision changesRequested(String who, String comment) {
        return new ApprovalDecision(Outcome.CHANGES_REQUESTED, who, comment);
    }
}
