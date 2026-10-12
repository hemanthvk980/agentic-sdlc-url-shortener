package io.github.hemanthvk980.shortener.link.domain;

public enum LinkStatus {
    ACTIVE,
    /** Soft-deleted: no longer redirects, analytics retained. */
    DISABLED
}
