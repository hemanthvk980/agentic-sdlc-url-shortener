package io.github.hemanthvk980.orchestrator.graph;

/**
 * Lifecycle of a single stage node.
 *
 * <pre>
 * PENDING ─► RUNNING ─► AWAITING_APPROVAL ─► SUCCEEDED
 *               │               │
 *               ├─(retry)◄──────┘ (changes requested)
 *               ▼
 *            FAILED ─► (dependents) SKIPPED
 *            ROLLED_BACK is recorded per attempt; CANCELLED on safe-stop.
 * </pre>
 */
public enum NodeStatus {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }

    /** A dependency in this state lets downstream nodes proceed. */
    public boolean satisfiesDependency() {
        return this == SUCCEEDED;
    }
}
