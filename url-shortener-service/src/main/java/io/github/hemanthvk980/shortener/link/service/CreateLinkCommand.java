package io.github.hemanthvk980.shortener.link.service;

import java.time.Instant;

/** Service-layer input, decoupled from the HTTP DTO. */
public record CreateLinkCommand(String url, String alias, Instant expiresAt, String idempotencyKey) {
}
