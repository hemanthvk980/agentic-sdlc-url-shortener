package io.github.hemanthvk980.shortener.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All tunables in one validated place; bound from {@code app.*} (environment variables in production,
 * e.g. {@code APP_BASE_URL}, {@code APP_ADMIN_API_KEY}). Startup fails fast on invalid configuration.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String baseUrl,
        @Min(5) @Max(12) int codeLength,
        @Min(1) @Max(10) int maxCodeGenerationAttempts,
        @Min(1) int maxUrlLength,
        Duration defaultTtl,
        Duration maxTtl,
        String adminApiKey,
        List<String> blockedHosts,
        RateLimit rateLimit,
        Analytics analytics) {

    public record RateLimit(boolean enabled, @Min(1) int createPerMinute, @Min(1) int redirectPerMinute) {}

    public record Analytics(@Min(1) int retentionDays, @Min(1) int statsDays) {}

    public boolean adminEnabled() {
        return adminApiKey != null && adminApiKey.length() >= 16;
    }
}
