package io.github.hemanthvk980.orchestrator;

import io.github.hemanthvk980.orchestrator.approval.ApprovalDecision;
import io.github.hemanthvk980.orchestrator.approval.ScriptedHumanGateway;
import io.github.hemanthvk980.orchestrator.engine.Orchestrator;
import io.github.hemanthvk980.orchestrator.engine.RunState;
import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.graph.StageNode;
import io.github.hemanthvk980.orchestrator.graph.TaskGraph;
import io.github.hemanthvk980.orchestrator.policy.PolicyEngine;
import io.github.hemanthvk980.orchestrator.workspace.InMemoryWorkspace;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Helpers for building small graphs of scripted stages in tests. */
public final class TestStages {

    private TestStages() {}

    public static Stage ok(String summary) {
        return ctx -> StageResult.ofSummary(summary);
    }

    public static Stage producing(String artifact, String content) {
        return ctx -> StageResult.builder().artifact(artifact, content).summary("produced " + artifact).build();
    }

    /** Fails the first {@code failures} attempts, then succeeds. */
    public static Stage flaky(int failures, AtomicInteger calls) {
        return ctx -> {
            int n = calls.incrementAndGet();
            if (n <= failures) throw new IllegalStateException("transient failure #" + n);
            return StageResult.ofSummary("succeeded on call " + n);
        };
    }

    public static StageNode.Builder node(String id, Stage s) {
        return StageNode.builder(id, s).title(id);
    }

    public static Orchestrator orchestrator(TaskGraph g, InMemoryWorkspace ws, ScriptedHumanGateway human) {
        return orchestrator(g, ws, human, PolicyEngine.defaults(List.of()));
    }

    public static Orchestrator orchestrator(TaskGraph g, InMemoryWorkspace ws, ScriptedHumanGateway human, PolicyEngine p) {
        RunState state = new RunState("test-run", "test", "requirement text", g);
        return Orchestrator.builder().graph(g).state(state).workspace(ws).human(human).policies(p)
                .maxWallClock(Duration.ofSeconds(30)).build();
    }

    public static ScriptedHumanGateway approveAll() {
        return new ScriptedHumanGateway("tester", Map.of("*", List.of(ApprovalDecision.approved("", "ok"))), Map.of());
    }

    public static ScriptedHumanGateway approvals(Map<String, List<ApprovalDecision>> byNode, Map<String, String> answers) {
        return new ScriptedHumanGateway("tester", byNode, answers);
    }
}
