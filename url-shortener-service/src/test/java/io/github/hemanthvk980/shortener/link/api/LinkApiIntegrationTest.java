package io.github.hemanthvk980.shortener.link.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hemanthvk980.shortener.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.base-url=https://sho.rt",
        "app.admin-api-key=test-admin-key-0123456789",
        "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class LinkApiIntegrationTest {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(T0);
    }

    // ---------------------------------------------------------------- create + redirect

    @Test
    void createsLinkAndRedirects() throws Exception {
        JsonNode link = create("{\"url\":\"https://example.com/page?x=1\"}", null);
        String code = link.get("code").asText();
        assertThat(code).matches("^[0-9A-Za-z]{7}$");
        assertThat(link.get("shortUrl").asText()).isEqualTo("https://sho.rt/" + code);
        assertThat(link.get("targetUrl").asText()).isEqualTo("https://example.com/page?x=1");

        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/page?x=1"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void unknownCodeIs404WithProblemDetail() throws Exception {
        mvc.perform(get("/nope1234"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Link not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rejectsUnsafeUrls() throws Exception {
        for (String bad : new String[] {"javascript:alert(1)", "http://127.0.0.1/", "https://sho.rt/loop", "ftp://x.example"}) {
            mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void validatesRequestBody() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.url").exists());
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- aliases

    @Test
    void customAliasAndConflicts() throws Exception {
        String alias = "talk-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode link = create("{\"url\":\"https://example.com\",\"alias\":\"" + alias + "\"}", null);
        assertThat(link.get("code").asText()).isEqualTo(alias);

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://other.example\",\"alias\":\"" + alias + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://other.example\",\"alias\":\"swagger-ui\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://other.example\",\"alias\":\"a b\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void idempotencyKeyMakesRetriesSafe() throws Exception {
        String key = UUID.randomUUID().toString();
        JsonNode first = create("{\"url\":\"https://example.com/idem\"}", key);
        JsonNode retry = create("{\"url\":\"https://example.com/idem\"}", key);
        assertThat(retry.get("code").asText()).isEqualTo(first.get("code").asText());

        mvc.perform(post("/api/v1/links").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/different\"}"))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- expiry

    @Test
    void expiredLinksReturn410() throws Exception {
        JsonNode link = create("{\"url\":\"https://example.com/soon\",\"expiresAt\":\"" + T0.plus(Duration.ofHours(1)) + "\"}", null);
        String code = link.get("code").asText();
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        clock.advance(Duration.ofHours(2));
        mvc.perform(get("/" + code)).andExpect(status().isGone());
    }

    @Test
    void rejectsPastExpiry() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"expiresAt\":\"" + T0.minusSeconds(1) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- analytics

    @Test
    void recordsClicksAndReportsStats() throws Exception {
        String code = create("{\"url\":\"https://example.com/stats\"}", null).get("code").asText();
        mvc.perform(get("/" + code).header("Referer", "https://news.example.org/post/1")).andExpect(status().isFound());
        mvc.perform(get("/" + code).header("Referer", "https://news.example.org/post/2")).andExpect(status().isFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        JsonNode stats = awaitStats(code, 3);
        assertThat(stats.get("totalClicks").asLong()).isEqualTo(3);
        assertThat(stats.get("clicksByDay").get(0).get("day").asText()).isEqualTo("2026-10-01");
        assertThat(stats.get("topReferrers").get(0).get("referrer").asText()).isEqualTo("news.example.org");
        assertThat(stats.get("topReferrers").get(0).get("clicks").asLong()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- admin

    @Test
    void disableRequiresApiKeyAndStopsRedirects() throws Exception {
        String code = create("{\"url\":\"https://example.com/del\"}", null).get("code").asText();
        mvc.perform(get("/" + code)).andExpect(status().isFound()); // warms the cache

        mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", "wrong-key-wrong-key")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", "test-admin-key-0123456789"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/" + code)).andExpect(status().isGone()); // cache evicted
        mvc.perform(get("/api/v1/links/" + code)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void actuatorHealthIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode create(String body, String idempotencyKey) throws Exception {
        var req = post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) req.header("Idempotency-Key", idempotencyKey);
        MvcResult r = mvc.perform(req).andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** Clicks are recorded asynchronously; poll briefly instead of sleeping a fixed time. */
    private JsonNode awaitStats(String code, long expected) throws Exception {
        JsonNode stats = null;
        for (int i = 0; i < 50; i++) {
            stats = json.readTree(mvc.perform(get("/api/v1/links/" + code + "/stats")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            if (stats.get("totalClicks").asLong() >= expected) return stats;
            Thread.sleep(100);
        }
        return stats;
    }
}
