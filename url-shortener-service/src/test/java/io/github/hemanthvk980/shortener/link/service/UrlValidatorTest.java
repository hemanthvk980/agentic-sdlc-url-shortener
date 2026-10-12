package io.github.hemanthvk980.shortener.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hemanthvk980.shortener.config.AppProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(new AppProperties(
            "https://sho.rt", 7, 5, 2048, Duration.ZERO, Duration.ofDays(365), null, List.of("evil.example"),
            new AppProperties.RateLimit(false, 10, 10), new AppProperties.Analytics(30, 30)));

    @ParameterizedTest
    @CsvSource({
            "https://example.com/a?b=c#d, https://example.com/a?b=c#d",
            "HTTPS://Example.COM/Path, https://example.com/Path",
            "  http://example.com  , http://example.com/",
            "https://example.com:8443/x%20y, https://example.com:8443/x%20y"
    })
    void acceptsAndNormalisesPublicHttpUrls(String input, String expected) {
        assertThat(validator.validateAndNormalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "ftp://example.com/file",
            "file:///etc/passwd",
            "https://user:pass@example.com/",
            "https://bank.example@evil.example/",
            "http://localhost:8080/admin",
            "http://127.0.0.1/",
            "http://10.0.0.5/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/",
            "http://[fd00::1]/",
            "http://service.internal/",
            "https://sho.rt/abc1234",
            "https://evil.example/",
            "https://sub.evil.example/",
            "not a url",
            "https:///no-host",
            ""
    })
    void rejectsUnsafeOrInvalidUrls(String input) {
        assertThatThrownBy(() -> validator.validateAndNormalize(input)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsOverlongUrls() {
        String url = "https://example.com/" + "a".repeat(2100);
        assertThatThrownBy(() -> validator.validateAndNormalize(url))
                .isInstanceOf(InvalidUrlException.class).hasMessageContaining("2048");
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> validator.validateAndNormalize("https://example.com/\r\nSet-Cookie:x"))
                .isInstanceOf(InvalidUrlException.class);
    }
}
