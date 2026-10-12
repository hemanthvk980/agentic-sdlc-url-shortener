package io.github.hemanthvk980.shortener.link.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "link")
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "target_url", nullable = false, length = 2048)
    private String targetUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LinkStatus status = LinkStatus.ACTIVE;

    @Column(name = "custom_alias", nullable = false)
    private boolean customAlias;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    @Version
    private long version;

    protected Link() {
        // JPA
    }

    public Link(String code, String targetUrl, Instant createdAt, Instant expiresAt, boolean customAlias,
                String idempotencyKey) {
        this.code = code;
        this.targetUrl = targetUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.customAlias = customAlias;
        this.idempotencyKey = idempotencyKey;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public void disable() {
        this.status = LinkStatus.DISABLED;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getTargetUrl() { return targetUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public LinkStatus getStatus() { return status; }
    public boolean isCustomAlias() { return customAlias; }
    public long getClickCount() { return clickCount; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
