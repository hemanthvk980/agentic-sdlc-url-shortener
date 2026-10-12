package io.github.hemanthvk980.orchestrator.llm;

import java.util.function.BiConsumer;

/**
 * Model-level fallback: if the primary model fails with a retryable error (rate limit, outage,
 * truncation), the same request goes to the secondary (e.g. a lighter/cheaper model).
 * Non-retryable errors (bad key, bad request) are not masked.
 */
public final class FallbackLlmClient implements LlmClient {

    private final LlmClient primary;
    private final LlmClient secondary;
    private final BiConsumer<LlmRequest, LlmException> onFallback;

    public FallbackLlmClient(LlmClient primary, LlmClient secondary, BiConsumer<LlmRequest, LlmException> onFallback) {
        this.primary = primary;
        this.secondary = secondary;
        this.onFallback = onFallback;
    }

    @Override
    public String name() {
        return primary.name() + " → " + secondary.name();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        try {
            return primary.complete(request);
        } catch (LlmException e) {
            if (!e.retryable()) throw e;
            onFallback.accept(request, e);
            return secondary.complete(request);
        }
    }
}
