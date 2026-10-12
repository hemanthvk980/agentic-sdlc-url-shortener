package io.github.hemanthvk980.orchestrator.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hemanthvk980.orchestrator.metrics.MetricsAggregator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmClientsTest {

    @TempDir Path tmp;

    private static LlmClient fixed(String text, AtomicInteger calls) {
        return new LlmClient() {
            @Override public LlmResponse complete(LlmRequest r) { calls.incrementAndGet(); return new LlmResponse(text, "fake-model", 5, 42); }
            @Override public String name() { return "fake"; }
        };
    }

    private static LlmClient failing(boolean retryable) {
        return new LlmClient() {
            @Override public LlmResponse complete(LlmRequest r) { throw new LlmException("down", retryable); }
            @Override public String name() { return "down"; }
        };
    }

    @Test
    void recordThenReplayIsDeterministicAndFailsClosedOnMiss() {
        AtomicInteger calls = new AtomicInteger();
        LlmRequest req = LlmRequest.json("design", "system", "prompt");
        RecordReplayLlmClient.recording(fixed("{\"ok\":true}", calls), tmp).complete(req);

        RecordReplayLlmClient replay = RecordReplayLlmClient.replaying(tmp);
        assertEquals("{\"ok\":true}", replay.complete(req).text());
        assertEquals(1, calls.get(), "replay never calls the model");

        LlmException miss = assertThrows(LlmException.class, () -> replay.complete(LlmRequest.json("design", "system", "changed prompt")));
        assertFalse(miss.retryable());
    }

    @Test
    void fallbackOnlyForRetryableErrors() {
        AtomicInteger secondary = new AtomicInteger();
        List<String> fallbacks = new ArrayList<>();
        LlmClient c = new FallbackLlmClient(failing(true), fixed("{}", secondary), (r, e) -> fallbacks.add(r.purpose()));
        assertEquals("{}", c.complete(LlmRequest.json("impl", "s", "p")).text());
        assertEquals(List.of("impl"), fallbacks);

        LlmClient auth = new FallbackLlmClient(failing(false), fixed("{}", secondary), (r, e) -> fallbacks.add("never"));
        assertThrows(LlmException.class, () -> auth.complete(LlmRequest.json("impl", "s", "p")));
        assertEquals(1, secondary.get(), "permanent errors (bad key) are not masked by fallback");
    }

    @Test
    void fixturesServeInSequenceAndSimulateFailures() throws Exception {
        Files.writeString(tmp.resolve("design.json"), "!FAIL");
        Files.writeString(tmp.resolve("design.2.json"), "{\"v\":2}");
        Files.writeString(tmp.resolve("implementation@T1.json"), "{\"t\":1}");
        FixtureLlmClient f = FixtureLlmClient.fromDirectory(tmp);
        LlmException e = assertThrows(LlmException.class, () -> f.complete(LlmRequest.json("design", "s", "p")));
        assertTrue(e.retryable());
        assertEquals("{\"v\":2}", f.complete(LlmRequest.json("design", "s", "p")).text());
        assertEquals("{\"v\":2}", f.complete(LlmRequest.json("design", "s", "p")).text(), "last fixture repeats");
        assertEquals("{\"t\":1}", f.complete(LlmRequest.json("implementation:T1", "s", "p")).text());
        assertThrows(LlmException.class, () -> f.complete(LlmRequest.json("unknown", "s", "p")));
    }

    @Test
    void aggregatesReliabilityMetricsAcrossRuns() throws Exception {
        write("r1", "SUCCEEDED", 1000, 2, 1, 1, 400);
        write("r2", "FAILED", 3000, 0, 0, 0, 0);
        Map<String, Object> m = MetricsAggregator.aggregate(tmp);
        assertEquals(2L, ((Number) m.get("runs")).longValue());
        assertEquals(0.5, (Double) m.get("successRate"), 1e-9);
        assertEquals(400L, ((Number) m.get("mttrMs")).longValue());
        assertEquals(1000L, ((Number) m.get("latencyP50Ms")).longValue());
        assertEquals(3000L, ((Number) m.get("latencyP95Ms")).longValue());
    }

    private void write(String id, String outcome, long latency, long retries, long rollbacks, long recoveries, long mttr) throws Exception {
        Path d = Files.createDirectories(tmp.resolve(id));
        Files.writeString(d.resolve("run.json"), "{\"outcome\":\"" + outcome + "\",\"metrics\":{\"e2eLatencyExcludingHumanMs\":" + latency
                + ",\"retries\":" + retries + ",\"rollbacks\":" + rollbacks + ",\"recoveries\":" + recoveries
                + ",\"mttrMs\":" + mttr + ",\"nodes\":{\"a\":{},\"b\":{}}}}");
    }
}
