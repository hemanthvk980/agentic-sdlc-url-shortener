package io.github.hemanthvk980.shortener.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.hemanthvk980.shortener.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client token-bucket rate limiting (Bucket4j). Link creation is limited tightly (abuse/spam);
 * redirects loosely (scraping/enumeration). Buckets live in a bounded, expiring cache so memory stays
 * flat under many distinct clients.
 *
 * <p>Limitation: buckets are per instance. Behind N instances the effective limit is N× — a
 * Redis-backed Bucket4j proxy manager fixes that when horizontally scaled.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties.RateLimit cfg;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterAccess(Duration.ofMinutes(10)).build();

    public RateLimitFilter(AppProperties props) {
        this.cfg = props.rateLimit();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (cfg == null || !cfg.enabled()) return true;
        String p = request.getRequestURI();
        return p.startsWith("/actuator") || p.startsWith("/swagger-ui") || p.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        boolean create = "POST".equals(req.getMethod()) && req.getRequestURI().startsWith("/api/v1/links");
        boolean api = req.getRequestURI().startsWith("/api/");
        int perMinute = create ? cfg.createPerMinute() : cfg.redirectPerMinute();
        String key = (create ? "c:" : api ? "a:" : "r:") + req.getRemoteAddr();

        Bucket bucket = buckets.get(key, k -> Bucket.builder()
                .addLimit(l -> l.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        res.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
        if (probe.isConsumed()) {
            chain.doFilter(req, res);
            return;
        }
        long retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"rate limit exceeded; retry after " + retryAfter + "s\"}");
    }
}
