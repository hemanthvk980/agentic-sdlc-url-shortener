package io.github.hemanthvk980.orchestrator.engine;

import static io.github.hemanthvk980.orchestrator.TestStages.approvals;
import static io.github.hemanthvk980.orchestrator.TestStages.approveAll;
import static io.github.hemanthvk980.orchestrator.TestStages.flaky;
import static io.github.hemanthvk980.orchestrator.TestStages.node;
import static io.github.hemanthvk980.orchestrator.TestStages.ok;
import static io.github.hemanthvk980.orchestrator.TestStages.orchestrator;
import static io.github.hemanthvk980.orchestrator.TestStages.producing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hemanthvk980.orchestrator.approval.ApprovalDecision;
import io.github.hemanthvk980.orchestrator.gate.GateResult;
import io.github.hemanthvk980.orchestrator.gate.Gates;
import io.github.hemanthvk980.orchestrator.graph.NodeStatus;
import io.github.hemanthvk980.orchestrator.graph.RetryPolicy;
import io.github.hemanthvk980.orchestrator.graph.TaskGraph;
import io.github.hemanthvk980.orchestrator.llm.LlmRequest;
import io.github.hemanthvk980.orchestrator.llm.LlmResponse;
import io.github.hemanthvk980.orchestrator.llm.ObservedLlmClient;
import io.github.hemanthvk980.orchestrator.metrics.RunMetrics;
import io.github.hemanthvk980.orchestrator.policy.PolicyEngine;
import io.github.hemanthvk980.orchestrator.workspace.InMemoryWorkspace;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrchestratorTest {

    private static final RetryPolicy FAST3 = RetryPolicy.of(3, Duration.ofMillis(1));

    @Test
    void runsIndependentBranchesInParallelAndJoins() {
        // b and c must be inside execute() at the same time to pass the barrier — proves real parallelism.
        CyclicBarrier barrier = new CyclicBarrier(2);
        Stage meetAtBarrier = ctx -> {
            barrier.await(5, TimeUnit.SECONDS);
            return StageResult.ofSummary("met");
        };
        TaskGraph g = TaskGraph.of(List.of(
                node("a", ok("a")).build(),
                node("b", meetAtBarrier).dependsOn("a").build(),
                node("c", meetAtBarrier).dependsOn("a").build(),
                node("d", ok("joined")).dependsOn("b", "c").build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approveAll());

        RunState s = o.run();

        assertEquals(RunState.Outcome.SUCCEEDED, s.outcome());
        g.nodes().forEach(n -> assertEquals(NodeStatus.SUCCEEDED, s.status(n.id()), n.id()));
        assertTrue(o.audit().verify());
    }

    @Test
    void retriesTransientFailuresAndMeasuresRecovery() {
        AtomicInteger calls = new AtomicInteger();
        TaskGraph g = TaskGraph.of(List.of(node("x", flaky(2, calls)).retry(FAST3).build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approveAll());

        RunState s = o.run();

        assertEquals(RunState.Outcome.SUCCEEDED, s.outcome());
        assertEquals(3, calls.get());
        assertEquals(3, s.attempts("x"));
        RunMetrics m = o.metrics();
        assertEquals(2, m.totalRetries());
        assertEquals(1, m.recoveries());
    }

    @Test
    void fallsBackWhenRetriesAreExhaustedAndSkipsNothing() {
        AtomicInteger calls = new AtomicInteger();
        TaskGraph g = TaskGraph.of(List.of(
                node("x", flaky(99, calls)).retry(RetryPolicy.of(2, Duration.ofMillis(1))).fallback(ok("fallback worked")).build(),
                node("y", ok("y")).dependsOn("x").build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approveAll());

        RunState s = o.run();

        assertEquals(RunState.Outcome.SUCCEEDED, s.outcome());
        assertEquals("fallback worked", s.summary("x"));
        assertEquals(1, o.metrics().totalFallbacks());
        assertTrue(s.decisions().stream().anyMatch(d -> d.kind() == Decision.Kind.FALLBACK));
    }

    @Test
    void failureSkipsDownstreamButIndependentBranchesFinish() {
        AtomicInteger calls = new AtomicInteger();
        TaskGraph g = TaskGraph.of(List.of(
                node("bad", flaky(99, calls)).retry(RetryPolicy.of(2, Duration.ofMillis(1))).build(),
                node("after-bad", ok("never")).dependsOn("bad").build(),
                node("after-after", ok("never")).dependsOn("after-bad").build(),
                node("independent", ok("fine")).build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approveAll());

        RunState s = o.run();

        assertEquals(RunState.Outcome.FAILED, s.outcome());
        assertEquals(NodeStatus.FAILED, s.status("bad"));
        assertEquals(NodeStatus.SKIPPED, s.status("after-bad"));
        assertEquals(NodeStatus.SKIPPED, s.status("after-after"));
        assertEquals(NodeStatus.SUCCEEDED, s.status("independent"));
        assertEquals(2, calls.get(), "retries are bounded");
    }

    @Test
    void exitGateFailureRollsBackTheWorkspaceThenRetrySucceeds() {
        InMemoryWorkspace ws = new InMemoryWorkspace();
        AtomicInteger calls = new AtomicInteger();
        Stage writer = ctx -> {
            int n = calls.incrementAndGet();
            return StageResult.builder()
                    .change(new FileChange("src/App.java", FileChange.Type.ADD, n == 1 ? "BROKEN" : "class App {}", "impl"))
                    .summary("attempt " + n).build();
        };
        TaskGraph g = TaskGraph.of(List.of(node("impl", writer).retry(FAST3)
                .exitGate(Gates.of("compiles", c -> c.workspace().read("src/App.java").orElse("").contains("BROKEN")
                        ? GateResult.fail("compiles", "syntax error in App.java")
                        : GateResult.pass("compiles", "ok")))
                .build()));
        Orchestrator o = orchestrator(g, ws, approveAll());

        RunState s = o.run();

        assertEquals(RunState.Outcome.SUCCEEDED, s.outcome());
        assertEquals("class App {}", ws.read("src/App.java").orElseThrow());
        assertEquals(1, o.metrics().totalRollbacks());
        assertTrue(s.decisions().stream().anyMatch(d -> d.kind() == Decision.Kind.ROLLBACK));
        assertTrue(o.audit().events().stream().anyMatch(e -> "workspace.rollback".equals(e.get("type"))));
    }

    @Test
    void stagesSeeThePreviousFailureForSelfRepair() {
        AtomicInteger calls = new AtomicInteger();
        StringBuilder seen = new StringBuilder();
        Stage s = ctx -> {
            ctx.previousFailure().ifPresent(seen::append);
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("NullPointer in LinkService:42");
            return StageResult.ofSummary("fixed");
        };
        Orchestrator o = orchestrator(TaskGraph.of(List.of(node("x", s).retry(FAST3).build())), new InMemoryWorkspace(), approveAll());
        o.run();
        assertTrue(seen.toString().contains("LinkService:42"));
    }

    @Test
    void policyBlockRejectsChangeAndAgentGetsToCorrectIt() {
        InMemoryWorkspace ws = new InMemoryWorkspace();
        AtomicInteger calls = new AtomicInteger();
        String fakeKey = "AIza" + "Sy" + "x".repeat(33);
        Stage leaky = ctx -> {
            boolean first = calls.incrementAndGet() == 1;
            String yml = first ? "gemini.key: " + fakeKey : "gemini.key: ${GEMINI_API_KEY}";
            return StageResult.builder().change(new FileChange("src/main/resources/application.yml", FileChange.Type.ADD, yml, "cfg"))
                    .summary("config").build();
        };
        Orchestrator o = orchestrator(TaskGraph.of(List.of(node("impl", leaky).retry(FAST3).build())), ws, approveAll(),
                PolicyEngine.defaults(List.of("src/")));

        RunState s = o.run();

        assertEquals(RunState.Outcome.SUCCEEDED, s.outcome());
        assertEquals(2, calls.get());
        assertFalse(ws.read("src/main/resources/application.yml").orElseThrow().contains("AIza"));
        assertTrue(s.decisions().stream().anyMatch(d -> d.kind() == Decision.Kind.POLICY));
    }

    @Test
    void writesOutsideScopeAreBlockedUntilRetriesRunOut() {
        Stage escape = ctx -> StageResult.builder()
                .change(new FileChange("../../etc/cron.d/x", FileChange.Type.ADD, "* * * * * rm -rf /", "evil")).build();
        Orchestrator o = orchestrator(TaskGraph.of(List.of(node("impl", escape).retry(RetryPolicy.of(2, Duration.ofMillis(1))).build())),
                new InMemoryWorkspace(), approveAll(), PolicyEngine.defaults(List.of("src/")));
        RunState s = o.run();
        assertEquals(NodeStatus.FAILED, s.status("impl"));
        assertTrue(s.errors().get("impl").contains("policy"));
    }

    @Test
    void humanRejectionFailsTheNodeAndStopsDownstream() {
        TaskGraph g = TaskGraph.of(List.of(
                node("design", producing("design.json", "{}")).requiresApproval(true).build(),
                node("impl", ok("never")).dependsOn("design").build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(),
                approvals(Map.of("design", List.of(ApprovalDecision.rejected("", "wrong approach"))), Map.of()));

        RunState s = o.run();

        assertEquals(RunState.Outcome.FAILED, s.outcome());
        assertEquals(NodeStatus.FAILED, s.status("design"));
        assertEquals(NodeStatus.SKIPPED, s.status("impl"));
        assertTrue(s.decisions().stream().anyMatch(d -> d.kind() == Decision.Kind.HUMAN_REJECTION && d.actor().contains("scripted")));
    }

    @Test
    void approvalIsFailClosedWhenNoHumanDecisionExists() {
        TaskGraph g = TaskGraph.of(List.of(node("release", ok("r")).requiresApproval(true).build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approvals(Map.of(), Map.of()));
        assertEquals(NodeStatus.FAILED, o.run().status("release"));
    }

    @Test
    void changesRequestedReExecutesWithReviewerFeedback() {
        AtomicInteger calls = new AtomicInteger();
        StringBuilder feedbackSeen = new StringBuilder();
        Stage design = ctx -> {
            calls.incrementAndGet();
            feedbackSeen.append(String.join(";", ctx.humanFeedback()));
            return StageResult.builder().artifact("design.json", "{\"v\":" + calls.get() + "}").build();
        };
        TaskGraph g = TaskGraph.of(List.of(node("design", design).requiresApproval(true).maxRevisions(2).build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approvals(Map.of("design", List.of(
                ApprovalDecision.changesRequested("", "use Redis for rate limiting"),
                ApprovalDecision.approved("", "lgtm"))), Map.of()));

        RunState s = o.run();

        assertEquals(RunState.Outcome.SUCCEEDED, s.outcome());
        assertEquals(2, calls.get());
        assertTrue(feedbackSeen.toString().contains("use Redis"));
        assertEquals("{\"v\":2}", s.artifact("design.json").orElseThrow().content());
    }

    @Test
    void blockingQuestionsTriggerClarificationAndReplanOfTheStage() {
        Stage req = ctx -> {
            if (ctx.clarifications().isEmpty()) {
                return StageResult.builder().question("Should links expire by default?").build();
            }
            return StageResult.builder().artifact("requirements.json", "{\"expiry\":\"" + ctx.clarifications().values().iterator().next() + "\"}").build();
        };
        TaskGraph g = TaskGraph.of(List.of(node("requirements", req).build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approvals(Map.of(), Map.of("expire", "no, never by default")));

        RunState s = o.run();

        assertEquals(RunState.Outcome.SUCCEEDED, s.outcome());
        assertTrue(s.artifact("requirements.json").orElseThrow().content().contains("never by default"));
        assertTrue(s.decisions().stream().anyMatch(d -> d.kind() == Decision.Kind.CLARIFICATION));
        assertEquals(1, s.attempts("requirements") - 1, "clarification round is not counted as a failure");
    }

    @Test
    void unansweredQuestionsFailClosed() {
        Stage req = ctx -> StageResult.builder().question("Which database?").build();
        Orchestrator o = orchestrator(TaskGraph.of(List.of(node("requirements", req).build())), new InMemoryWorkspace(),
                approvals(Map.of(), Map.of()));
        assertEquals(NodeStatus.FAILED, o.run().status("requirements"));
    }

    @Test
    void entryGateBlocksExecution() {
        AtomicInteger calls = new AtomicInteger();
        TaskGraph g = TaskGraph.of(List.of(node("design", flaky(0, calls)).entryGate(Gates.artifactPresent("requirements.json")).build()));
        RunState s = orchestrator(g, new InMemoryWorkspace(), approveAll()).run();
        assertEquals(NodeStatus.FAILED, s.status("design"));
        assertEquals(0, calls.get(), "stage must not run when its entry gate fails");
    }

    @Test
    void killSwitchSafeStopsTheRun() {
        TaskGraph g = TaskGraph.of(List.of(
                node("a", ctx -> { throw new KillSwitch.SafeStopException("operator pressed stop"); }).build(),
                node("b", ok("never")).dependsOn("a").build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approveAll());
        o.killSwitch().trip("operator pressed stop");
        RunState s = o.run();
        assertEquals(RunState.Outcome.STOPPED, s.outcome());
        assertEquals(NodeStatus.CANCELLED, s.status("b"));
    }

    @Test
    void llmBudgetExhaustionTripsSafeStop() {
        Stage chatty = ctx -> {
            for (int i = 0; i < 10; i++) ctx.llm().complete(LlmRequest.json("chatty", "s", "p" + i));
            return StageResult.ofSummary("done");
        };
        TaskGraph g = TaskGraph.of(List.of(node("chatty", chatty).build(), node("next", ok("x")).dependsOn("chatty").build()));
        RunState state = new RunState("budget", "t", "r", g);
        RunMetrics metrics = new RunMetrics();
        var audit = io.github.hemanthvk980.orchestrator.audit.AuditLog.inMemory("budget");
        var llm = new ObservedLlmClient(new io.github.hemanthvk980.orchestrator.llm.LlmClient() {
            @Override public LlmResponse complete(LlmRequest r) { return new LlmResponse("{}", "fake", 1, 10); }
            @Override public String name() { return "fake"; }
        }, audit, metrics, 3, 2);
        Orchestrator o = Orchestrator.builder().graph(g).state(state).workspace(new InMemoryWorkspace()).human(approveAll())
                .llm(llm).audit(audit).metrics(metrics).build();

        RunState s = o.run();

        assertEquals(RunState.Outcome.STOPPED, s.outcome());
        assertTrue(s.outcomeReason().contains("budget"));
        assertEquals(NodeStatus.CANCELLED, s.status("next"));
    }

    @Test
    void replanInvalidatesOnlyConsumersOfTheChangedArtifact() {
        AtomicInteger designRuns = new AtomicInteger();
        AtomicInteger indexRuns = new AtomicInteger();
        Stage design = ctx -> {
            designRuns.incrementAndGet();
            return StageResult.builder().artifact("design.json", "design for " + ctx.requireArtifact("req.json")).build();
        };
        Stage index = ctx -> {
            indexRuns.incrementAndGet();
            return StageResult.builder().artifact("index.json", "files").build();
        };
        TaskGraph g = TaskGraph.of(List.of(
                node("req", producing("req.json", "v1")).build(),
                node("index", index).build(),
                node("design", design).dependsOn("req", "index").build(),
                node("impl", ctx -> StageResult.builder().artifact("impl.json", ctx.requireArtifact("design.json")).build())
                        .dependsOn("design").build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approveAll());
        o.run();
        assertEquals("design for v1", o.state().artifact("design.json").orElseThrow().content());

        List<String> invalidated = o.replan("req.json", "v2", "stakeholder changed scope", "human:pm");
        assertEquals(List.of("design", "impl"), invalidated);
        RunState s = o.run();

        assertEquals(RunState.Outcome.SUCCEEDED, s.outcome());
        assertEquals(2, designRuns.get());
        assertEquals(1, indexRuns.get(), "untouched branch is not re-executed");
        assertEquals("design for v2", s.artifact("impl.json").orElseThrow().content());
        assertEquals(1, o.metrics().toJson() instanceof Map<?, ?> m ? ((Number) m.get("replans")).intValue() : -1);
    }

    @Test
    void lineageRecordsWhichInputHashesShapedEachArtifact() {
        TaskGraph g = TaskGraph.of(List.of(
                node("req", producing("req.json", "R")).build(),
                node("design", ctx -> StageResult.builder().artifact("design.json", ctx.requireArtifact("req.json") + "→D").build())
                        .dependsOn("req").build()));
        Orchestrator o = orchestrator(g, new InMemoryWorkspace(), approveAll());
        RunState s = o.run();
        Artifact req = s.artifact("req.json").orElseThrow();
        Artifact design = s.artifact("design.json").orElseThrow();
        assertEquals(List.of(req.hash()), design.derivedFrom());
        assertEquals("design", design.producedBy());
    }
}
