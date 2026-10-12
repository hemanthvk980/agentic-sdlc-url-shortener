package io.github.hemanthvk980.orchestrator.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * <p>Objects map to {@link LinkedHashMap} (insertion order preserved so audit records are stable),
 * arrays to {@link ArrayList}, numbers to {@link Long} or {@link Double}. Kept deliberately small:
 * the orchestrator only exchanges simple structured documents with the LLM and the audit log.
 */
public final class Json {

    private Json() {}

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, -1, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 2, 0);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(s, sb);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Enum<?> e) {
            quote(e.name(), sb);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, depth + 1);
                quote(String.valueOf(e.getKey()), sb);
                sb.append(indent >= 0 ? ": " : ":");
                write(e.getValue(), sb, indent, depth + 1);
            }
            if (!m.isEmpty()) newline(sb, indent, depth);
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, depth + 1);
                write(o, sb, indent, depth + 1);
            }
            if (!c.isEmpty()) newline(sb, indent, depth);
            sb.append(']');
        } else if (v instanceof JsonWritable w) {
            write(w.toJson(), sb, indent, depth);
        } else {
            quote(v.toString(), sb);
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n').append(" ".repeat(indent * depth));
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) throw p.error("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("expected JSON object");
        return (Map<String, Object>) v;
    }

    /**
     * Extracts the first JSON object from free text. LLMs frequently wrap JSON in markdown fences
     * or add commentary; this tolerates that without trusting anything outside the braces.
     */
    public static Map<String, Object> extractObject(String text) {
        if (text == null) throw new JsonException("no content");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new JsonException("no JSON object found in model output");
        return parseObject(text.substring(start, end + 1));
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        JsonException error(String msg) { return new JsonException(msg + " at position " + pos); }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            if (pos >= s.length()) throw error("unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object val) {
            if (!s.startsWith(word, pos)) throw error("invalid literal");
            pos += word.length();
            return val;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("expected key");
                String k = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(k, value());
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw error("expected , or }");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw error("expected , or ]");
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw error("bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
        }

        Number number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String n = s.substring(start, pos);
            if (n.isEmpty()) throw error("unexpected character");
            try {
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                return Long.parseLong(n);
            } catch (NumberFormatException ex) {
                throw error("invalid number");
            }
        }

        char peek() {
            if (pos >= s.length()) throw error("unexpected end");
            return s.charAt(pos);
        }

        char next() {
            if (pos >= s.length()) throw error("unexpected end");
            return s.charAt(pos++);
        }

        void expect(char c) {
            if (next() != c) throw error("expected '" + c + "'");
        }
    }
}
