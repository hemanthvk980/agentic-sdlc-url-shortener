package io.github.hemanthvk980.shortener.link.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * One redirect. Deliberately minimal: no IP address or full user agent is stored (privacy by design);
 * only the referrer host, which is what the stats need.
 */
@Entity
@Table(name = "click_event")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;

    /** UTC calendar day, denormalised so daily aggregation is a portable GROUP BY. */
    @Column(name = "click_date", nullable = false)
    private LocalDate clickDate;

    @Column(name = "referrer_host", length = 255)
    private String referrerHost;

    protected ClickEvent() {
        // JPA
    }

    public ClickEvent(Long linkId, Instant clickedAt, String referrerHost) {
        this.linkId = linkId;
        this.clickedAt = clickedAt;
        this.clickDate = LocalDate.ofInstant(clickedAt, ZoneOffset.UTC);
        this.referrerHost = referrerHost;
    }

    public Long getId() { return id; }
    public Long getLinkId() { return linkId; }
    public Instant getClickedAt() { return clickedAt; }
    public LocalDate getClickDate() { return clickDate; }
    public String getReferrerHost() { return referrerHost; }
}
