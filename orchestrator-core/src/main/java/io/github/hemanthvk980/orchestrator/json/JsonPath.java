package io.github.hemanthvk980.orchestrator.json;

import java.util.List;
import java.util.Map;

/** Null-safe typed accessors over parsed JSON maps. */
public final class JsonPath {

    private JsonPath() {}

    public static String str(Map<String, Object> m, String key, String dflt) {
        Object v = m == null ? null : m.get(key);
        return v == null ? dflt : v.toString();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Map<String, Object> m, String key) {
        return list(m, key).stream()
                .filter(o -> o instanceof Map)
                .map(o -> (Map<String, Object>) o)
                .toList();
    }

    public static List<String> strings(Map<String, Object> m, String key) {
        return list(m, key).stream().map(String::valueOf).toList();
    }
}
