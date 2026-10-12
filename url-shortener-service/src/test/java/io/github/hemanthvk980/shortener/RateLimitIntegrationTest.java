package io.github.hemanthvk980.shortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.rate-limit.enabled=true", "app.rate-limit.create-per-minute=3"})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void createIsRateLimitedPerClient() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://example.com/" + i + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
