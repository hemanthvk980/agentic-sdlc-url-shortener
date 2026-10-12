package io.github.hemanthvk980.orchestrator.metrics;

import io.github.hemanthvk980.orchestrator.json.JsonWritable;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reliability metrics for one run.
 *
 * <ul>
 *   <li><b>MTTR</b>: for each node that failed at least once and later succeeded, time from first
 *       failure to recovery; reported as the mean across recoveries.</li>
 *   <li><b>End-to-end latency</b> is reported both raw and excluding time spent waiting on humans,
 *       so agent performance is not conflated with approver response time.</li>
 * </ul>
 */
public final class RunMetrics implements JsonWritable {

    private final Instant start = Instant.now();
    private volatile Instant end;

    private final Map<String, NodeStats> nodes = new ConcurrentHashMap<>();
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmFailures = new AtomicLong();
    private final AtomicLong llmLatencyMs = new AtomicLong();
    private final AtomicLong llmTokens = new AtomicLong();
    private final AtomicLong humanWaitMs = new AtomicLong();
    private final AtomicLong approvals = new AtomicLong();
    private final AtomicLong rejections = new AtomicLong();
    private final AtomicLong policyBlocks = new AtomicLong();
    private final AtomicLong replans = new AtomicLong();

    public NodeStats node(String id) {
        return nodes.computeIfAbsent(id, k -> new NodeStats());
    }

    public void llmCall(long latencyMs, long tokens, boolean ok) {
        llmCalls.incrementAndGet();
        llmLatencyMs.addAndGet(latencyMs);
        llmTokens.addAndGet(tokens);
        if (!ok) llmFailures.incrementAndGet();
    }

    public void humanWait(Duration d) { humanWaitMs.addAndGet(d.toMillis()); }
    public void approval(boolean approved) { (approved ? approvals : rejections).incrementAndGet(); }
    public void policyBlock() { policyBlocks.incrementAndGet(); }
    public void replan() { replans.incrementAndGet(); }
    public void finish() { end = Instant.now(); }

    public long totalRetries() { return nodes.values().stream().mapToLong(n -> n.retries.get()).sum(); }
    public long totalRollbacks() { return nodes.values().stream().mapToLong(n -> n.rollbacks.get()).sum(); }
    public long totalFallbacks() { return nodes.values().stream().mapToLong(n -> n.fallbacks.get()).sum(); }

    public long e2eMs() {
        return Duration.between(start, end == null ? Instant.now() : end).toMillis();
    }

    public double mttrMs() {
        return nodes.values().stream().filter(n -> n.recoveryMs.get() >= 0)
                .mapToLong(n -> n.recoveryMs.get()).average().orElse(0);
    }

    public long recoveries() {
        return nodes.values().stream().filter(n -> n.recoveryMs.get() >= 0).count();
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("e2eLatencyMs", e2eMs());
        m.put("humanWaitMs", humanWaitMs.get());
        m.put("e2eLatencyExcludingHumanMs", Math.max(0, e2eMs() - humanWaitMs.get()));
        m.put("retries", totalRetries());
        m.put("rollbacks", totalRollbacks());
        m.put("fallbacks", totalFallbacks());
        m.put("recoveries", recoveries());
        m.put("mttrMs", Math.round(mttrMs()));
        m.put("llmCalls", llmCalls.get());
        m.put("llmFailures", llmFailures.get());
        m.put("llmLatencyMs", llmLatencyMs.get());
        m.put("llmTokens", llmTokens.get());
        m.put("approvalsGranted", approvals.get());
        m.put("approvalsRejected", rejections.get());
        m.put("policyBlocks", policyBlocks.get());
        m.put("replans", replans.get());
        Map<String, Object> perNode = new LinkedHashMap<>();
        nodes.forEach((k, v) -> perNode.put(k, v.toJson()));
        m.put("nodes", perNode);
        return m;
    }

    public static final class NodeStats implements JsonWritable {
        public final AtomicLong attempts = new AtomicLong();
        public final AtomicLong retries = new AtomicLong();
        public final AtomicLong rollbacks = new AtomicLong();
        public final AtomicLong fallbacks = new AtomicLong();
        public final AtomicLong durationMs = new AtomicLong();
        public final AtomicLong recoveryMs = new AtomicLong(-1);
        private volatile Instant firstFailure;

        public void failed() {
            if (firstFailure == null) firstFailure = Instant.now();
        }

        public void succeeded() {
            if (firstFailure != null && recoveryMs.get() < 0) {
                recoveryMs.set(Duration.between(firstFailure, Instant.now()).toMillis());
            }
        }

        @Override
        public Object toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attempts", attempts.get());
            m.put("retries", retries.get());
            m.put("rollbacks", rollbacks.get());
            m.put("fallbacks", fallbacks.get());
            m.put("durationMs", durationMs.get());
            m.put("recoveryMs", recoveryMs.get());
            return m;
        }
    }
}
