package io.github.hemanthvk980.orchestrator.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline mode for demos and tests: serves <b>hand-written</b> fixture responses keyed by request
 * purpose (e.g. {@code requirements.json}, {@code implementation.json}). These are NOT recordings of a
 * real model; they exist so the orchestration machinery can be exercised end-to-end with no network.
 *
 * <p>If several fixtures exist for a purpose ({@code design.json}, {@code design.2.json}...), they are
 * served in order on successive calls — used to script "first attempt fails, retry succeeds".
 */
public final class FixtureLlmClient implements LlmClient {

    private final Map<String, List<String>> fixtures = new LinkedHashMap<>();
    private final Map<String, Integer> served = new ConcurrentHashMap<>();

    public FixtureLlmClient(Map<String, List<String>> fixturesByPurpose) {
        fixturesByPurpose.forEach((k, v) -> fixtures.put(k, List.copyOf(v)));
    }

    public static FixtureLlmClient fromDirectory(Path dir) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        try (var s = Files.list(dir)) {
            List<Path> files = s.filter(f -> f.toString().endsWith(".json")).sorted(java.util.Comparator
                    .comparing((Path f) -> f.getFileName().toString().replaceFirst("(\\.\\d+)?\\.json$", ""))
                    .thenComparingInt(f -> sequence(f.getFileName().toString()))).toList();
            for (Path p : files) {
                String file = p.getFileName().toString().replaceFirst("\\.json$", "");
                // "implementation@T2.json" serves purpose "implementation:T2"; ".2" suffix = 2nd response
                String purpose = file.replaceFirst("\\.\\d+$", "").replace('@', ':');
                map.computeIfAbsent(purpose, k -> new ArrayList<>()).add(Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new FixtureLlmClient(map);
    }

    /** "design.json" → 1, "design.2.json" → 2. */
    private static int sequence(String fileName) {
        var m = java.util.regex.Pattern.compile("\\.(\\d+)\\.json$").matcher(fileName);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    @Override
    public String name() {
        return "fixtures(offline)";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String purpose = request.purpose();
        List<String> list = fixtures.get(purpose);
        if (list == null) list = fixtures.get(purpose.replaceFirst(":.*$", "")); // "implementation:T2" -> "implementation"
        if (list == null || list.isEmpty()) {
            throw new LlmException("no offline fixture for purpose '" + purpose + "'", false);
        }
        int n = served.merge(purpose, 1, Integer::sum);
        String text = list.get(Math.min(n - 1, list.size() - 1));
        if (text.startsWith("!FAIL")) throw new LlmException("fixture-simulated transient failure", true);
        return new LlmResponse(text, "fixture", 1, 0);
    }
}
