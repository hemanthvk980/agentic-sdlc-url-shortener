package io.github.hemanthvk980.shortener.link.service;

import io.github.hemanthvk980.shortener.link.domain.Link;
import io.github.hemanthvk980.shortener.link.domain.LinkStatus;
import java.io.Serializable;
import java.time.Instant;

/** Immutable, cache-safe projection of what a redirect needs (never cache JPA entities). */
public record LinkTarget(long id, String code, String targetUrl, Instant expiresAt, boolean active)
        implements Serializable {

    public static LinkTarget of(Link l) {
        return new LinkTarget(l.getId(), l.getCode(), l.getTargetUrl(), l.getExpiresAt(), l.getStatus() == LinkStatus.ACTIVE);
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
