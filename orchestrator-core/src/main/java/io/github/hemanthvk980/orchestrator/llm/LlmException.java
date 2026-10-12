package io.github.hemanthvk980.orchestrator.llm;

public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** True for transient conditions (rate limit, 5xx, timeout); false for auth/config errors. */
    public boolean retryable() {
        return retryable;
    }
}
