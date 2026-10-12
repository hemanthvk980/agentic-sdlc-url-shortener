package io.github.hemanthvk980.shortener.link.service;

import io.github.hemanthvk980.shortener.config.AppProperties;
import io.github.hemanthvk980.shortener.config.CacheConfig;
import io.github.hemanthvk980.shortener.link.domain.Link;
import io.github.hemanthvk980.shortener.link.repo.ClickEventRepository;
import io.github.hemanthvk980.shortener.link.repo.LinkRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {

    static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{4,32}$");

    /** Paths the redirect route must never shadow. */
    static final Set<String> RESERVED = Set.of(
            "api", "actuator", "swagger-ui", "swagger", "v3", "docs", "health", "assets", "static", "admin",
            "login", "logout", "index", "favicon", "robots", "error", "stats");

    private final LinkRepository links;
    private final ClickEventRepository clicks;
    private final UrlValidator validator;
    private final CodeGenerator codes;
    private final AppProperties props;
    private final Clock clock;

    public LinkService(LinkRepository links, ClickEventRepository clicks, UrlValidator validator, CodeGenerator codes,
                       AppProperties props, Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.validator = validator;
        this.codes = codes;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Creates a link. With an Idempotency-Key, a client retry (e.g. after a timeout) returns the
     * original link instead of creating a duplicate; reusing the key for a different URL is a 409.
     */
    @Transactional
    public Link create(CreateLinkCommand cmd) {
        String target = validator.validateAndNormalize(cmd.url());

        if (cmd.idempotencyKey() != null) {
            var existing = links.findByIdempotencyKey(cmd.idempotencyKey());
            if (existing.isPresent()) {
                Link l = existing.get();
                boolean sameAlias = cmd.alias() == null || cmd.alias().equals(l.getCode());
                if (l.getTargetUrl().equals(target) && sameAlias) return l;
                throw new ConflictException("Idempotency-Key was already used for a different request");
            }
        }

        Instant now = clock.instant();
        Instant expiresAt = resolveExpiry(cmd.expiresAt(), now);

        if (cmd.alias() != null) {
            String alias = cmd.alias();
            if (!ALIAS.matcher(alias).matches()) {
                throw new InvalidUrlException("alias must be 4-32 characters of letters, digits, '-' or '_'");
            }
            if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) throw new ConflictException("alias '" + alias + "' is reserved");
            if (links.existsByCode(alias)) throw new ConflictException("alias '" + alias + "' is already taken");
            return links.saveAndFlush(new Link(alias, target, now, expiresAt, true, cmd.idempotencyKey()));
        }

        for (int i = 0; i < props.maxCodeGenerationAttempts(); i++) {
            String code = codes.next();
            if (!links.existsByCode(code) && !RESERVED.contains(code.toLowerCase(Locale.ROOT))) {
                // A concurrent insert of the same code is still caught by the unique constraint (→ 409, retryable).
                return links.saveAndFlush(new Link(code, target, now, expiresAt, false, cmd.idempotencyKey()));
            }
        }
        throw new IllegalStateException("could not allocate a unique code after " + props.maxCodeGenerationAttempts() + " attempts");
    }

    /** Redirect lookup (cached). Throws 404 for unknown codes and 410 for expired/disabled links. */
    @Cacheable(cacheNames = CacheConfig.LINKS_BY_CODE, key = "#code")
    @Transactional(readOnly = true)
    public LinkTarget lookup(String code) {
        return links.findByCode(code).map(LinkTarget::of).orElseThrow(() -> new LinkNotFoundException(code));
    }

    public LinkTarget resolveForRedirect(LinkTarget t) {
        if (!t.active()) throw new LinkGoneException(t.code(), "disabled");
        if (t.expiredAt(clock.instant())) throw new LinkGoneException(t.code(), "expired");
        return t;
    }

    @Transactional(readOnly = true)
    public Link get(String code) {
        return links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    @CacheEvict(cacheNames = CacheConfig.LINKS_BY_CODE, key = "#code")
    @Transactional
    public void disable(String code, String apiKey) {
        requireAdmin(apiKey);
        Link l = get(code);
        l.disable();
    }

    @Transactional(readOnly = true)
    public LinkStats stats(String code) {
        Link l = get(code);
        LocalDate since = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(props.analytics().statsDays() - 1L);
        List<LinkStats.Daily> daily = clicks.countByDay(l.getId(), since).stream()
                .map(d -> new LinkStats.Daily(d.getClickDate(), d.getClicks())).toList();
        List<LinkStats.Referrer> refs = clicks.topReferrers(l.getId(), PageRequest.of(0, 10)).stream()
                .map(r -> new LinkStats.Referrer(r.getReferrer(), r.getClicks())).toList();
        return new LinkStats(l.getCode(), l.getClickCount(), since, daily, refs);
    }

    public String shortUrl(String code) {
        String base = props.baseUrl().endsWith("/") ? props.baseUrl() : props.baseUrl() + "/";
        return base + code;
    }

    private Instant resolveExpiry(Instant requested, Instant now) {
        if (requested == null) {
            return props.defaultTtl() == null || props.defaultTtl().isZero() ? null : now.plus(props.defaultTtl());
        }
        if (!requested.isAfter(now)) throw new InvalidUrlException("expiresAt must be in the future");
        if (props.maxTtl() != null && !props.maxTtl().isZero() && requested.isAfter(now.plus(props.maxTtl()))) {
            throw new InvalidUrlException("expiresAt exceeds the maximum lifetime of " + props.maxTtl().toDays() + " days");
        }
        return requested;
    }

    /** Constant-time comparison; management is disabled entirely unless a strong key is configured. */
    private void requireAdmin(String apiKey) {
        if (!props.adminEnabled()) throw new ForbiddenException("link management is disabled on this deployment");
        if (apiKey == null || !MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), props.adminApiKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("invalid or missing X-API-Key");
        }
    }
}
