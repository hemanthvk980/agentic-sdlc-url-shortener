package io.github.hemanthvk980.orchestrator.llm;

import io.github.hemanthvk980.orchestrator.audit.AuditLog;
import io.github.hemanthvk980.orchestrator.engine.Hashing;
import io.github.hemanthvk980.orchestrator.metrics.RunMetrics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decorator that makes every model call observable and bounded: audit event (prompt/response hashes,
 * not raw text, to keep the log small and free of sensitive content), metrics, a per-run call budget
 * (runaway agents trip safe-stop), and a concurrency limit (provider rate limits).
 */
public final class ObservedLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final AuditLog audit;
    private final RunMetrics metrics;
    private final int maxCalls;
    private final AtomicInteger calls = new AtomicInteger();
    private final Semaphore concurrency;

    public ObservedLlmClient(LlmClient delegate, AuditLog audit, RunMetrics metrics, int maxCalls, int maxConcurrent) {
        this.delegate = delegate;
        this.audit = audit;
        this.metrics = metrics;
        this.maxCalls = maxCalls;
        this.concurrency = new Semaphore(maxConcurrent);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (calls.incrementAndGet() > maxCalls) {
            throw new BudgetExceededException("LLM call budget of " + maxCalls + " exhausted");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("purpose", request.purpose());
        data.put("model", delegate.name());
        data.put("promptSha", Hashing.shortHash(request.system() + request.prompt()));
        data.put("promptChars", request.prompt().length());
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e, false);
        }
        try {
            LlmResponse r = delegate.complete(request);
            metrics.llmCall(r.latencyMs(), r.totalTokens(), true);
            data.put("ok", true);
            data.put("latencyMs", r.latencyMs());
            data.put("tokens", r.totalTokens());
            data.put("responseSha", Hashing.shortHash(r.text()));
            audit.record("llm.call", nodeOf(request), data);
            return r;
        } catch (RuntimeException e) {
            metrics.llmCall(0, 0, false);
            data.put("ok", false);
            data.put("error", e.getMessage());
            audit.record("llm.call", nodeOf(request), data);
            throw e;
        } finally {
            concurrency.release();
        }
    }

    private static String nodeOf(LlmRequest r) {
        return r.purpose().replaceFirst(":.*$", "");
    }

    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String m) {
            super(m);
        }
    }
}
