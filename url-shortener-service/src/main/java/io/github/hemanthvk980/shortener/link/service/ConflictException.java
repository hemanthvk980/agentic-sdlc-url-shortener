package io.github.hemanthvk980.shortener.link.service;

/** Alias already taken, or an Idempotency-Key reused with a different payload. */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
