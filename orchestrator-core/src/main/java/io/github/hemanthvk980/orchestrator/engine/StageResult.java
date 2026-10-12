package io.github.hemanthvk980.orchestrator.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a stage hands back to the engine.
 *
 * @param artifacts       named outputs (stored, hashed, lineage-tracked)
 * @param changes         proposed file changes (policy-checked, maybe approval-gated, then applied)
 * @param decisions       assumptions/choices the agent made, for the decision log
 * @param questions       blocking questions for a human; non-empty means "I cannot proceed safely
 *                        without clarification" and triggers a clarification round + re-execution
 * @param approvalReasons extra reasons this specific output needs human approval (stage-detected risk)
 * @param summary         short human-readable summary for the report
 */
public record StageResult(Map<String, String> artifacts, List<FileChange> changes, List<Decision> decisions,
                          List<String> questions, List<String> approvalReasons, String summary) {

    public StageResult {
        artifacts = Map.copyOf(artifacts);
        changes = List.copyOf(changes);
        decisions = List.copyOf(decisions);
        questions = List.copyOf(questions);
        approvalReasons = List.copyOf(approvalReasons);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static StageResult ofSummary(String summary) {
        return builder().summary(summary).build();
    }

    public static final class Builder {
        private final Map<String, String> artifacts = new LinkedHashMap<>();
        private final List<FileChange> changes = new ArrayList<>();
        private final List<Decision> decisions = new ArrayList<>();
        private final List<String> questions = new ArrayList<>();
        private final List<String> approvalReasons = new ArrayList<>();
        private String summary = "";

        public Builder artifact(String name, String content) { artifacts.put(name, content); return this; }
        public Builder change(FileChange c) { changes.add(c); return this; }
        public Builder changes(List<FileChange> c) { changes.addAll(c); return this; }
        public Builder decision(Decision d) { decisions.add(d); return this; }
        public Builder question(String q) { questions.add(q); return this; }
        public Builder approvalReason(String r) { approvalReasons.add(r); return this; }
        public Builder summary(String s) { this.summary = s; return this; }

        public StageResult build() {
            return new StageResult(artifacts, changes, decisions, questions, approvalReasons, summary);
        }
    }
}
