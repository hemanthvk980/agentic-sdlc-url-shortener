package io.github.hemanthvk980.shortener.link.api;

import io.github.hemanthvk980.shortener.link.domain.Link;
import io.github.hemanthvk980.shortener.link.domain.LinkStatus;
import java.time.Instant;

public record LinkResponse(String code, String shortUrl, String targetUrl, Instant createdAt, Instant expiresAt,
                           LinkStatus status, long clickCount) {

    public static LinkResponse of(Link l, String shortUrl) {
        return new LinkResponse(l.getCode(), shortUrl, l.getTargetUrl(), l.getCreatedAt(), l.getExpiresAt(),
                l.getStatus(), l.getClickCount());
    }
}
