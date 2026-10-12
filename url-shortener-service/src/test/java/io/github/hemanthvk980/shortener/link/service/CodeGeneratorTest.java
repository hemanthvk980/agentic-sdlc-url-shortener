package io.github.hemanthvk980.shortener.link.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hemanthvk980.shortener.config.AppProperties;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    private final CodeGenerator gen = new CodeGenerator(new AppProperties(
            "https://sho.rt", 7, 5, 2048, Duration.ZERO, Duration.ZERO, null, List.of(),
            new AppProperties.RateLimit(false, 1, 1), new AppProperties.Analytics(1, 1)));

    @Test
    void producesBase62CodesOfConfiguredLength() {
        for (int i = 0; i < 1000; i++) {
            String c = gen.next();
            assertThat(c).hasSize(7).matches("^[0-9A-Za-z]{7}$");
        }
    }

    @Test
    void codesAreEffectivelyUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50_000; i++) seen.add(gen.next());
        assertThat(seen).hasSize(50_000);
    }

    @Test
    void referrerHostExtraction() {
        assertThat(ClickRecorder.referrerHost("https://News.Example.com/a?b")).isEqualTo("news.example.com");
        assertThat(ClickRecorder.referrerHost(null)).isNull();
        assertThat(ClickRecorder.referrerHost("::not a uri::")).isNull();
    }
}
