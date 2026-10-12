package io.github.hemanthvk980.shortener.link.service;

import io.github.hemanthvk980.shortener.config.AppProperties;
import io.github.hemanthvk980.shortener.config.AsyncConfig;
import io.github.hemanthvk980.shortener.link.domain.ClickEvent;
import io.github.hemanthvk980.shortener.link.repo.ClickEventRepository;
import io.github.hemanthvk980.shortener.link.repo.LinkRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Asynchronous click analytics plus retention enforcement. Failures never affect the redirect. */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private final ClickEventRepository clicks;
    private final LinkRepository links;
    private final AppProperties props;
    private final Clock clock;
    private final Counter recorded;
    private final Counter failed;

    public ClickRecorder(ClickEventRepository clicks, LinkRepository links, AppProperties props, Clock clock, MeterRegistry meters) {
        this.clicks = clicks;
        this.links = links;
        this.props = props;
        this.clock = clock;
        this.recorded = meters.counter("shortener.clicks.recorded");
        this.failed = meters.counter("shortener.clicks.failed");
    }

    @Async(AsyncConfig.CLICK_EXECUTOR)
    @Transactional
    public void record(long linkId, String referrerHeader, Instant at) {
        try {
            clicks.save(new ClickEvent(linkId, at, referrerHost(referrerHeader)));
            links.incrementClickCount(linkId);
            recorded.increment();
        } catch (RuntimeException e) {
            failed.increment();
            log.warn("click recording failed for link {}: {}", linkId, e.toString());
        }
    }

    /** Keeps only the host of the referrer: enough for "top referrers", avoids storing full URLs. */
    static String referrerHost(String referrer) {
        if (referrer == null || referrer.isBlank()) return null;
        try {
            String host = URI.create(referrer.strip()).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            return host.length() > 255 ? host.substring(0, 255) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Scheduled(cron = "${app.analytics.retention-cron:0 15 3 * * *}")
    @Transactional
    public void enforceRetention() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(props.analytics().retentionDays()));
        int n = clicks.deleteOlderThan(cutoff);
        if (n > 0) log.info("retention: deleted {} click events older than {}", n, cutoff);
    }
}
