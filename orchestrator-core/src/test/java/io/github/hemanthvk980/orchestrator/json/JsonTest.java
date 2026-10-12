package io.github.hemanthvk980.orchestrator.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsNestedStructures() {
        Map<String, Object> m = Map.of("s", "a\"b\\c\n\u0001", "n", 42L, "d", 1.5, "b", true, "l", List.of(1L, "x"));
        Map<String, Object> back = Json.parseObject(Json.write(m));
        assertEquals(m, back);
        assertEquals(back, Json.parseObject(Json.pretty(m)));
    }

    @Test
    void extractsObjectFromFencedModelOutput() {
        String out = "Sure! Here it is:\n```json\n{\"a\": [1, 2, {\"b\": \"}\"}]}\n```\nHope that helps.";
        assertEquals(Map.of("a", List.of(1L, 2L, Map.of("b", "}"))), Json.extractObject(out));
    }

    @Test
    void parsesUnicodeEscapes() {
        assertEquals("é✓", Json.parse("\"\\u00e9\\u2713\""));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2"));
        assertThrows(JsonException.class, () -> Json.parse("{} trailing"));
        assertThrows(JsonException.class, () -> Json.extractObject("no json here"));
    }
}
