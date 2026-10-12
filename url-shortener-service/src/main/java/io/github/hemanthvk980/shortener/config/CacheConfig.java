package io.github.hemanthvk980.shortener.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redirect lookups are read-heavy and immutable apart from deletion/expiry, so a short-TTL local cache
 * removes most database reads. The TTL bounds staleness for multi-instance deployments, where another
 * instance's eviction is not visible (a shared Redis cache would remove that window).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String LINKS_BY_CODE = "linksByCode";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(LINKS_BY_CODE);
        mgr.setCaffeine(Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofMinutes(5)).recordStats());
        mgr.setAllowNullValues(false);
        return mgr;
    }
}
