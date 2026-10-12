package io.github.hemanthvk980.orchestrator.engine;

import io.github.hemanthvk980.orchestrator.approval.ApprovalDecision;
import io.github.hemanthvk980.orchestrator.approval.ApprovalRequest;
import io.github.hemanthvk980.orchestrator.approval.HumanGateway;
import io.github.hemanthvk980.orchestrator.audit.AuditLog;
import io.github.hemanthvk980.orchestrator.gate.Gate;
import io.github.hemanthvk980.orchestrator.gate.GateContext;
import io.github.hemanthvk980.orchestrator.gate.GateResult;
import io.github.hemanthvk980.orchestrator.graph.NodeStatus;
import io.github.hemanthvk980.orchestrator.graph.StageNode;
import io.github.hemanthvk980.orchestrator.graph.TaskGraph;
import io.github.hemanthvk980.orchestrator.llm.LlmClient;
import io.github.hemanthvk980.orchestrator.llm.LlmException;
import io.github.hemanthvk980.orchestrator.llm.ObservedLlmClient;
import io.github.hemanthvk980.orchestrator.metrics.RunMetrics;
import io.github.hemanthvk980.orchestrator.policy.PolicyEngine;
import io.github.hemanthvk980.orchestrator.policy.PolicyFinding;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * The execution engine. Walks the DAG, running every node whose dependencies have succeeded in
 * parallel (virtual threads), and wraps each stage in the governance pipeline:
 *
 * <pre>
 * entry gates → [attempt loop: execute → clarify? → policies → approval? → apply+exit gates (atomic,
 *               rollback on failure) → commit] → fallback when retries are exhausted → record lineage
 * </pre>
 *
 * Synchronisation points are the dependency edges themselves: a join node starts only when all of
 * its parents have succeeded. Workspace mutation is serialised (one node applies + verifies + commits
 * at a time) so a rollback can never discard a sibling branch's work; agent reasoning stays parallel.
 */
public final class Orchestrator {

    private final TaskGraph graph;
    private final RunState state;
    private final LlmClient llm;
    private final Workspace workspace;
    private final PolicyEngine policies;
    private final HumanGateway human;
    private final AuditLog audit;
    private final RunMetrics metrics;
    private final KillSwitch killSwitch;
    private final Duration maxWallClock;
    private final int maxClarificationRounds;
    /** ReentrantLock, not synchronized: blocking inside a monitor would pin virtual-thread carriers. */
    private final ReentrantLock workspaceLock = new ReentrantLock();

    private Orchestrator(Builder b) {
        this.graph = b.graph;
        this.state = b.state;
        this.llm = b.llm;
        this.workspace = b.workspace;
        this.policies = b.policies;
        this.human = b.human;
        this.audit = b.audit;
        this.metrics = b.metrics;
        this.killSwitch = b.killSwitch;
        this.maxWallClock = b.maxWallClock;
        this.maxClarificationRounds = b.maxClarificationRounds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RunState state() { return state; }
    public RunMetrics metrics() { return metrics; }
    public AuditLog audit() { return audit; }
    public KillSwitch killSwitch() { return killSwitch; }
    public TaskGraph graph() { return graph; }

    // =====================================================================================  run loop

    public RunState run() {
        Map<String, Object> startData = new LinkedHashMap<>();
        startData.put("scenario", state.scenario());
        startData.put("llm", llm == null ? "none" : llm.name());
        startData.put("layers", graph.layers());
        startData.put("policies", policies.policies().stream().map(p -> p.id()).toList());
        audit.record("run.started", null, startData);

        Instant deadline = Instant.now().plus(maxWallClock);
        Set<String> inFlight = new HashSet<>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<String> done = new ExecutorCompletionService<>(exec);
            while (true) {
                if (Instant.now().isAfter(deadline)) killSwitch.trip("wall-clock budget " + maxWallClock + " exceeded");
                if (!killSwitch.isTripped()) {
                    propagateSkips();
                    for (StageNode n : readyNodes(inFlight)) {
                        inFlight.add(n.id());
                        done.submit(() -> { executeNode(n); return n.id(); });
                    }
                }
                if (inFlight.isEmpty()) break;
                Future<String> f = done.poll(500, TimeUnit.MILLISECONDS);
                if (f != null) inFlight.remove(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killSwitch.trip("orchestrator interrupted");
        } catch (ExecutionException e) {
            killSwitch.trip("engine error: " + e.getCause());
        }

        if (killSwitch.isTripped()) {
            graph.nodes().forEach(n -> {
                if (state.status(n.id()) == NodeStatus.PENDING) state.setStatus(n.id(), NodeStatus.CANCELLED);
            });
            state.addDecision(Decision.system(null, Decision.Kind.SAFE_STOP, "Run safe-stopped", killSwitch.reason()));
            state.finish(RunState.Outcome.STOPPED, killSwitch.reason());
        } else if (graph.nodes().stream().allMatch(n -> state.status(n.id()) == NodeStatus.SUCCEEDED)) {
            state.finish(RunState.Outcome.SUCCEEDED, "all stages succeeded");
        } else {
            String failed = graph.nodes().stream().filter(n -> state.status(n.id()) == NodeStatus.FAILED)
                    .map(StageNode::id).collect(Collectors.joining(", "));
            state.finish(RunState.Outcome.FAILED, "failed stages: " + failed);
        }
        metrics.finish();
        Map<String, Object> endData = new LinkedHashMap<>();
        endData.put("outcome", state.outcome());
        endData.put("reason", state.outcomeReason());
        endData.put("metrics", metrics.toJson());
        audit.record("run.finished", null, endData);
        return state;
    }

    /** Nodes that are PENDING, not in flight, and whose dependencies have all succeeded. */
    private List<StageNode> readyNodes(Set<String> inFlight) {
        List<StageNode> ready = new ArrayList<>();
        for (StageNode n : graph.nodes()) {
            if (inFlight.contains(n.id()) || state.status(n.id()) != NodeStatus.PENDING) continue;
            if (n.dependsOn().stream().allMatch(d -> state.status(d).satisfiesDependency())) ready.add(n);
        }
        return ready;
    }

    /** A pending node with a failed/skipped/cancelled ancestor can never run: mark it SKIPPED. */
    private void propagateSkips() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StageNode n : graph.nodes()) {
                if (state.status(n.id()) != NodeStatus.PENDING) continue;
                for (String d : n.dependsOn()) {
                    NodeStatus ds = state.status(d);
                    if (ds == NodeStatus.FAILED || ds == NodeStatus.SKIPPED || ds == NodeStatus.CANCELLED) {
                        state.setStatus(n.id(), NodeStatus.SKIPPED);
                        audit.record("node.skipped", n.id(), Map.of("because", d + " is " + ds));
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    // ==============================================================================  node execution

    private void executeNode(StageNode node) {
        RunMetrics.NodeStats ns = metrics.node(node.id());
        long t0 = System.nanoTime();
        transition(node, NodeStatus.RUNNING, Map.of("phase", node.phase()));
        try {
            for (Gate g : node.entryGates()) {
                GateResult r = evaluateGate(g, new GateContext(node.id(), state, workspace, audit, null), "entry");
                if (!r.passed()) {
                    fail(node, ns, "entry gate '" + g.name() + "' failed: " + r.detail());
                    return;
                }
            }
            runAttempts(node, ns);
        } catch (KillSwitch.SafeStopException e) {
            transition(node, NodeStatus.CANCELLED, Map.of("reason", e.getMessage()));
        } catch (RuntimeException e) {
            fail(node, ns, "unexpected engine error: " + e);
        } finally {
            ns.durationMs.addAndGet((System.nanoTime() - t0) / 1_000_000);
        }
    }

    private void runAttempts(StageNode node, RunMetrics.NodeStats ns) {
        String failure = null;
        boolean onFallback = false;
        int attempt = 0;
        int clarifyRounds = 0;
        int revisions = 0;

        while (true) {
            killSwitch.throwIfStopped();
            attempt++;
            if (attempt > node.retry().maxAttempts()) {
                if (node.fallback() != null && !onFallback) {
                    onFallback = true;
                    attempt = 1;
                    ns.fallbacks.incrementAndGet();
                    state.addDecision(Decision.system(node.id(), Decision.Kind.FALLBACK,
                            "Switched to fallback strategy", "primary exhausted retries: " + abbreviate(failure)));
                    audit.record("node.fallback", node.id(), Map.of("lastFailure", abbreviate(failure)));
                } else {
                    fail(node, ns, "exhausted " + node.retry().maxAttempts() + " attempt(s)"
                            + (onFallback ? " including fallback" : "") + ": " + failure);
                    return;
                }
            }
            sleep(node.retry().backoffBefore(attempt));
            state.incrementAttempts(node.id());
            ns.attempts.incrementAndGet();
            if (failure != null) ns.retries.incrementAndGet();
            state.clearInputs(node.id());

            StageContext ctx = new StageContext(node.id(), attempt, onFallback, state, llm, workspace, killSwitch, failure);
            Map<String, Object> attemptData = new LinkedHashMap<>();
            attemptData.put("attempt", attempt);
            attemptData.put("fallback", onFallback);
            audit.record("node.attempt", node.id(), attemptData);

            // ---- 1. execute the stage (agent or tool)
            StageResult result;
            try {
                result = (onFallback ? node.fallback() : node.stage()).execute(ctx);
            } catch (KillSwitch.SafeStopException e) {
                throw e;
            } catch (ObservedLlmClient.BudgetExceededException e) {
                killSwitch.trip(e.getMessage());
                throw new KillSwitch.SafeStopException(e.getMessage());
            } catch (LlmException e) {
                failure = "model error: " + e.getMessage();
                attemptFailed(node, ns, failure);
                if (!e.retryable()) attempt = node.retry().maxAttempts(); // don't hammer a permanent error
                continue;
            } catch (Exception e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                attemptFailed(node, ns, failure);
                continue;
            }

            // ---- 2. blocking questions → human clarification → re-plan this stage
            if (!result.questions().isEmpty()) {
                if (clarifyRounds >= maxClarificationRounds) {
                    fail(node, ns, "still ambiguous after " + clarifyRounds + " clarification round(s): " + result.questions());
                    return;
                }
                clarifyRounds++;
                Map<String, String> answers = askHuman(node, result.questions());
                if (answers.isEmpty()) {
                    fail(node, ns, "human did not answer blocking questions: " + result.questions());
                    return;
                }
                metrics.replan();
                state.addDecision(Decision.system(node.id(), Decision.Kind.REPLAN,
                        "Re-executing with " + answers.size() + " clarification(s)", "ambiguity resolved by human"));
                audit.record("node.replan", node.id(), Map.of("reason", "clarifications received"));
                attempt--; // clarification is not a failure
                failure = null;
                continue;
            }

            // ---- 3. policy guardrails
            PolicyEngine.Evaluation eval = policies.evaluate(node.id(), result.changes(), workspace);
            if (!eval.findings().isEmpty()) {
                audit.record("policy.evaluated", node.id(), Map.of("findings", eval.findings()));
            }
            if (eval.blocked()) {
                metrics.policyBlock();
                String why = eval.ofSeverity(PolicyFinding.Severity.BLOCK).stream()
                        .map(f -> f.policy() + " @ " + f.path() + ": " + f.message()).collect(Collectors.joining("; "));
                state.addDecision(Decision.system(node.id(), Decision.Kind.POLICY, "Change set blocked by policy", why));
                failure = "policy violation (your previous output was rejected; fix this): " + why;
                attemptFailed(node, ns, failure);
                continue;
            }

            // ---- 4. human approval checkpoint for high-impact output
            List<String> reasons = new ArrayList<>();
            if (node.requiresApproval()) reasons.add("stage '" + node.title() + "' requires human sign-off");
            eval.ofSeverity(PolicyFinding.Severity.REQUIRE_APPROVAL)
                    .forEach(f -> reasons.add(f.policy() + " @ " + f.path() + ": " + f.message()));
            reasons.addAll(result.approvalReasons());
            if (!reasons.isEmpty()) {
                ApprovalDecision d = askApproval(node, result, reasons);
                switch (d.outcome()) {
                    case APPROVED -> { /* proceed */ }
                    case REJECTED -> {
                        fail(node, ns, "rejected by " + d.approver() + ": " + d.comment());
                        return;
                    }
                    case CHANGES_REQUESTED -> {
                        if (revisions >= node.maxRevisions()) {
                            fail(node, ns, "revision limit reached; last request: " + d.comment());
                            return;
                        }
                        revisions++;
                        state.addFeedback(node.id(), d.comment());
                        failure = "reviewer requested changes: " + d.comment();
                        attempt--; // a revision round is bounded separately from failure retries
                        transition(node, NodeStatus.RUNNING, Map.of("revision", revisions));
                        continue;
                    }
                }
                transition(node, NodeStatus.RUNNING, Map.of("approved", true));
            }

            // ---- 5. apply + exit gates + commit, atomically w.r.t. the workspace
            String gateFailure = null;
            String commit = null;
            workspaceLock.lock();
            try {
                killSwitch.throwIfStopped();
                String checkpoint = workspace.head();
                try {
                    workspace.apply(result.changes());
                    GateContext gctx = new GateContext(node.id(), state, workspace, audit, result);
                    for (Gate g : node.exitGates()) {
                        GateResult r = evaluateGate(g, gctx, "exit");
                        if (!r.passed()) {
                            gateFailure = "exit gate '" + g.name() + "' failed: " + r.detail();
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    gateFailure = "applying changes failed: " + e.getMessage();
                }
                if (gateFailure != null) {
                    workspace.resetTo(checkpoint);
                    ns.rollbacks.incrementAndGet();
                    state.addDecision(Decision.system(node.id(), Decision.Kind.ROLLBACK,
                            "Rolled workspace back to " + abbreviate(checkpoint), gateFailure));
                    audit.record("workspace.rollback", node.id(), Map.of("to", checkpoint, "reason", abbreviate(gateFailure)));
                } else if (!result.changes().isEmpty()) {
                    commit = workspace.commit("[" + state.runId() + "] " + node.id() + ": " + firstLine(result.summary()));
                    audit.record("workspace.commit", node.id(), Map.of("commit", commit, "files", result.changes()));
                }
            } finally {
                workspaceLock.unlock();
            }
            if (gateFailure != null) {
                failure = gateFailure;
                attemptFailed(node, ns, failure);
                continue;
            }

            // ---- 6. success: persist outputs with lineage
            List<String> derivedFrom = new ArrayList<>(new LinkedHashSet<>(state.inputHashes(node.id()).values()));
            for (Map.Entry<String, String> a : result.artifacts().entrySet()) {
                Artifact art = state.putArtifact(a.getKey(), a.getValue(), node.id(), derivedFrom);
                audit.record("artifact.produced", node.id(), Map.of("artifact", art));
            }
            result.decisions().forEach(state::addDecision);
            state.recordApplied(node.id(), result.changes(), commit);
            state.setSummary(node.id(), result.summary());
            state.clearError(node.id());
            ns.succeeded();
            transition(node, NodeStatus.SUCCEEDED, Map.of("summary", abbreviate(result.summary()), "attempts", attempt));
            return;
        }
    }

    // ==================================================================================  re-planning

    /**
     * An upstream output changed after downstream stages consumed it (e.g. a human edited the
     * requirements, or an upstream agent was re-run). Every stage that read the old version, plus
     * everything downstream of those, is invalidated and will re-execute on the next {@link #run()};
     * untouched branches keep their results. Returns the invalidated node ids.
     */
    public List<String> replan(String artifactName, String newContent, String reason, String actor) {
        state.putArtifact(artifactName, newContent, actor, List.of());
        Set<String> invalid = new LinkedHashSet<>();
        for (String consumer : state.staleConsumersOf(artifactName)) {
            invalid.add(consumer);
            invalid.addAll(graph.transitiveDependents(consumer));
        }
        List<String> ordered = graph.nodes().stream().map(StageNode::id).filter(invalid::contains).toList();
        for (String id : ordered) {
            state.setStatus(id, NodeStatus.PENDING);
            audit.record("node.invalidated", id, Map.of("artifact", artifactName));
        }
        metrics.replan();
        state.addDecision(new Decision(null, Decision.Kind.REPLAN, actor,
                "Upstream artifact '" + artifactName + "' changed; invalidated " + ordered, reason, Instant.now()));
        audit.record("run.replan", null, Map.of("artifact", artifactName, "invalidated", ordered, "reason", reason));
        state.reopen();
        return ordered;
    }

    // ======================================================================================= helpers

    private Map<String, String> askHuman(StageNode node, List<String> questions) {
        transition(node, NodeStatus.AWAITING_APPROVAL, Map.of("questions", questions));
        Instant t = Instant.now();
        Map<String, String> answers = human.clarify(state.runId(), node.id(), questions);
        metrics.humanWait(Duration.between(t, Instant.now()));
        answers.forEach((q, a) -> {
            state.addClarification(q, a);
            state.addDecision(Decision.human(node.id(), Decision.Kind.CLARIFICATION, "human", q, a));
        });
        audit.record("human.clarified", node.id(), Map.of("answers", answers));
        transition(node, NodeStatus.RUNNING, Map.of());
        return answers;
    }

    private ApprovalDecision askApproval(StageNode node, StageResult result, List<String> reasons) {
        transition(node, NodeStatus.AWAITING_APPROVAL, Map.of("reasons", reasons));
        List<String> paths = result.changes().stream().map(c -> c.type() + " " + c.path()).toList();
        ApprovalRequest req = new ApprovalRequest(state.runId(), node.id(), node.title(), reasons, paths,
                result.summary(), preview(result));
        Instant t = Instant.now();
        ApprovalDecision d = human.requestApproval(req);
        metrics.humanWait(Duration.between(t, Instant.now()));
        metrics.approval(d.outcome() == ApprovalDecision.Outcome.APPROVED);
        Decision.Kind kind = switch (d.outcome()) {
            case APPROVED -> Decision.Kind.HUMAN_APPROVAL;
            case REJECTED -> Decision.Kind.HUMAN_REJECTION;
            case CHANGES_REQUESTED -> Decision.Kind.CHANGES_REQUESTED;
        };
        state.addDecision(Decision.human(node.id(), kind, d.approver(), d.outcome() + " — " + String.join(" | ", reasons),
                d.comment()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", d.outcome());
        data.put("approver", d.approver());
        data.put("comment", d.comment());
        data.put("reasons", reasons);
        audit.record("approval.decided", node.id(), data);
        return d;
    }

    private static String preview(StageResult r) {
        StringBuilder sb = new StringBuilder();
        for (FileChange c : r.changes()) {
            sb.append("── ").append(c.type()).append(' ').append(c.path()).append(" (").append(c.lineCount()).append(" lines)\n");
            if (c.content() != null) c.content().lines().limit(12).forEach(l -> sb.append("   ").append(l).append('\n'));
            if (sb.length() > 6000) break;
        }
        if (r.changes().isEmpty()) {
            r.artifacts().forEach((k, v) -> sb.append("── artifact ").append(k).append('\n')
                    .append(v.length() > 1500 ? v.substring(0, 1500) + "…" : v).append('\n'));
        }
        return sb.toString();
    }

    private GateResult evaluateGate(Gate g, GateContext ctx, String kind) {
        GateResult r;
        try {
            r = g.evaluate(ctx);
        } catch (RuntimeException e) {
            r = GateResult.fail(g.name(), "gate error (fail-closed): " + e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("gate", g.name());
        data.put("kind", kind);
        data.put("passed", r.passed());
        data.put("detail", abbreviate(r.detail()));
        audit.record("gate.evaluated", ctx.nodeId(), data);
        return r;
    }

    private void attemptFailed(StageNode node, RunMetrics.NodeStats ns, String failure) {
        ns.failed();
        state.setError(node.id(), failure);
        state.addDecision(Decision.system(node.id(), Decision.Kind.RETRY, "Attempt failed", abbreviate(failure)));
        audit.record("node.attempt.failed", node.id(), Map.of("error", abbreviate(failure)));
    }

    private void fail(StageNode node, RunMetrics.NodeStats ns, String why) {
        ns.failed();
        state.setError(node.id(), why);
        transition(node, NodeStatus.FAILED, Map.of("error", abbreviate(why)));
    }

    private void transition(StageNode node, NodeStatus to, Map<String, Object> data) {
        NodeStatus from = state.status(node.id());
        state.setStatus(node.id(), to);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("from", from);
        d.put("to", to);
        d.putAll(data);
        audit.record("node.status", node.id(), d);
    }

    private static void sleep(Duration d) {
        if (d.isZero()) return;
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KillSwitch.SafeStopException("interrupted during backoff");
        }
    }

    static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 1500 ? s.substring(0, 1500) + "…" : s;
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) return "changes";
        String l = s.lines().findFirst().orElse("changes");
        return l.length() > 100 ? l.substring(0, 100) : l;
    }

    // ======================================================================================= builder

    public static final class Builder {
        private TaskGraph graph;
        private RunState state;
        private LlmClient llm;
        private Workspace workspace;
        private PolicyEngine policies = new PolicyEngine(List.of());
        private HumanGateway human;
        private AuditLog audit;
        private RunMetrics metrics = new RunMetrics();
        private KillSwitch killSwitch = new KillSwitch();
        private Duration maxWallClock = Duration.ofMinutes(30);
        private int maxClarificationRounds = 2;

        public Builder graph(TaskGraph g) { this.graph = g; return this; }
        public Builder state(RunState s) { this.state = s; return this; }
        public Builder llm(LlmClient l) { this.llm = l; return this; }
        public Builder workspace(Workspace w) { this.workspace = w; return this; }
        public Builder policies(PolicyEngine p) { this.policies = p; return this; }
        public Builder human(HumanGateway h) { this.human = h; return this; }
        public Builder audit(AuditLog a) { this.audit = a; return this; }
        public Builder metrics(RunMetrics m) { this.metrics = m; return this; }
        public Builder killSwitch(KillSwitch k) { this.killSwitch = k; return this; }
        public Builder maxWallClock(Duration d) { this.maxWallClock = d; return this; }
        public Builder maxClarificationRounds(int n) { this.maxClarificationRounds = n; return this; }

        public Orchestrator build() {
            if (graph == null || state == null || workspace == null || human == null) {
                throw new IllegalStateException("graph, state, workspace and human gateway are required");
            }
            if (audit == null) audit = AuditLog.inMemory(state.runId());
            return new Orchestrator(this);
        }
    }
}
