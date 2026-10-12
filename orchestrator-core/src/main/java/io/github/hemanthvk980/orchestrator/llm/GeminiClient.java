package io.github.hemanthvk980.orchestrator.llm;

import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonException;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Google Gemini via the Generative Language REST API, using only the JDK HTTP client.
 * The API key is sent in a header (never in the URL, so it cannot leak into proxy/access logs).
 */
public final class GeminiClient implements LlmClient {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public GeminiClient(String apiKey, String model, Duration timeout) {
        this.apiKey = Objects.requireNonNull(apiKey, "GEMINI_API_KEY is not set");
        if (apiKey.isBlank()) throw new IllegalArgumentException("GEMINI_API_KEY is empty");
        this.model = model;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** Reads GEMINI_API_KEY and optional GEMINI_MODEL from the environment. */
    public static GeminiClient fromEnv(String defaultModel) {
        String key = System.getenv("GEMINI_API_KEY");
        String model = System.getenv().getOrDefault("GEMINI_MODEL", defaultModel);
        return new GeminiClient(key, model, Duration.ofSeconds(180));
    }

    @Override
    public String name() {
        return "gemini:" + model;
    }

    @Override
    public LlmResponse complete(LlmRequest req) {
        Map<String, Object> genConfig = new java.util.LinkedHashMap<>();
        genConfig.put("temperature", req.temperature());
        genConfig.put("maxOutputTokens", req.maxOutputTokens());
        if (req.jsonOutput()) genConfig.put("responseMimeType", "application/json");

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", req.system()))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", req.prompt())))));
        body.put("generationConfig", genConfig);

        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(BASE + model + ":generateContent"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> resp;
        try {
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("gemini transport error: " + e.getMessage(), e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e, false);
        }
        long latency = (System.nanoTime() - start) / 1_000_000;

        int code = resp.statusCode();
        if (code == 429 || code >= 500) throw new LlmException("gemini HTTP " + code + ": " + abbreviate(resp.body()), true);
        if (code != 200) throw new LlmException("gemini HTTP " + code + ": " + abbreviate(resp.body()), false);

        try {
            Map<String, Object> json = Json.parseObject(resp.body());
            List<Map<String, Object>> candidates = JsonPath.objects(json, "candidates");
            if (candidates.isEmpty()) {
                throw new LlmException("gemini returned no candidates: " + abbreviate(resp.body()), true);
            }
            Map<String, Object> first = candidates.get(0);
            String finish = JsonPath.str(first, "finishReason", "");
            StringBuilder text = new StringBuilder();
            for (Map<String, Object> part : JsonPath.objects(JsonPath.obj(first, "content"), "parts")) {
                text.append(JsonPath.str(part, "text", ""));
            }
            if (text.isEmpty()) throw new LlmException("gemini empty response (finishReason=" + finish + ")", true);
            if ("MAX_TOKENS".equals(finish)) throw new LlmException("gemini output truncated (MAX_TOKENS)", true);
            Object total = JsonPath.obj(json, "usageMetadata").get("totalTokenCount");
            return new LlmResponse(text.toString(), name(), latency, total instanceof Number n ? n.longValue() : 0);
        } catch (JsonException e) {
            throw new LlmException("unparseable gemini response: " + e.getMessage(), e, true);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
