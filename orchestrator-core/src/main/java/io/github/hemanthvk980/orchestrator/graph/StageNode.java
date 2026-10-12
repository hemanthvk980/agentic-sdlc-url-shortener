package io.github.hemanthvk980.orchestrator.graph;

import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.gate.Gate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A node in the SDLC dependency graph: the work ({@link Stage}) plus the governance wrapped around
 * it — entry/exit gates, retry bounds, an optional fallback, and whether a human must approve it.
 */
public record StageNode(
        String id,
        String title,
        SdlcPhase phase,
        List<String> dependsOn,
        Stage stage,
        Stage fallback,
        List<Gate> entryGates,
        List<Gate> exitGates,
        RetryPolicy retry,
        boolean requiresApproval,
        int maxRevisions) {

    public StageNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stage, "stage");
        dependsOn = List.copyOf(dependsOn);
        entryGates = List.copyOf(entryGates);
        exitGates = List.copyOf(exitGates);
        retry = retry == null ? RetryPolicy.NONE : retry;
        title = title == null ? id : title;
        phase = phase == null ? SdlcPhase.IMPLEMENTATION : phase;
    }

    public static Builder builder(String id, Stage stage) {
        return new Builder(id, stage);
    }

    public static final class Builder {
        private final String id;
        private final Stage stage;
        private String title;
        private SdlcPhase phase;
        private final List<String> dependsOn = new ArrayList<>();
        private Stage fallback;
        private final List<Gate> entryGates = new ArrayList<>();
        private final List<Gate> exitGates = new ArrayList<>();
        private RetryPolicy retry = RetryPolicy.NONE;
        private boolean requiresApproval;
        private int maxRevisions = 1;

        private Builder(String id, Stage stage) {
            this.id = id;
            this.stage = stage;
        }

        public Builder title(String t) { this.title = t; return this; }
        public Builder phase(SdlcPhase p) { this.phase = p; return this; }
        public Builder dependsOn(String... ids) { dependsOn.addAll(List.of(ids)); return this; }
        public Builder fallback(Stage f) { this.fallback = f; return this; }
        public Builder entryGate(Gate g) { entryGates.add(g); return this; }
        public Builder exitGate(Gate g) { exitGates.add(g); return this; }
        public Builder retry(RetryPolicy r) { this.retry = r; return this; }
        public Builder requiresApproval(boolean b) { this.requiresApproval = b; return this; }
        public Builder maxRevisions(int n) { this.maxRevisions = n; return this; }

        public StageNode build() {
            return new StageNode(id, title, phase, dependsOn, stage, fallback, entryGates, exitGates, retry,
                    requiresApproval, maxRevisions);
        }
    }
}
