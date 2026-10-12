package io.github.hemanthvk980.orchestrator.audit;

import io.github.hemanthvk980.orchestrator.engine.Hashing;
import io.github.hemanthvk980.orchestrator.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Append-only, hash-chained audit trail (JSON Lines). Each event carries the hash of its predecessor,
 * so editing or deleting any past event breaks {@link #verify()} — tamper-evident, like a minimal
 * ledger. Every state transition, gate result, policy finding, approval and LLM call is recorded.
 */
public final class AuditLog {

    private static final String GENESIS = "0".repeat(64);

    private final String runId;
    private final Path file;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final List<Consumer<Map<String, Object>>> listeners = new ArrayList<>();
    private String lastHash = GENESIS;

    public AuditLog(String runId, Path file) {
        this.runId = runId;
        this.file = file;
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static AuditLog inMemory(String runId) {
        return new AuditLog(runId, null);
    }

    public void addListener(Consumer<Map<String, Object>> l) {
        listeners.add(l);
    }

    public synchronized Map<String, Object> record(String type, String nodeId, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("seq", (long) events.size() + 1);
        e.put("at", Instant.now().toString());
        e.put("runId", runId);
        e.put("type", type);
        e.put("node", nodeId);
        e.put("data", data == null ? Map.of() : data);
        e.put("prevHash", lastHash);
        String hash = Hashing.sha256(Json.write(e));
        e.put("hash", hash);
        lastHash = hash;
        events.add(e);
        if (file != null) {
            try {
                Files.writeString(file, Json.write(e) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        listeners.forEach(l -> l.accept(e));
        return e;
    }

    public synchronized List<Map<String, Object>> events() {
        return List.copyOf(events);
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized boolean verify() {
        return verifyChain(events);
    }

    /** Verifies a chain read back from disk (e.g. by a reviewer or the release gate). */
    public static boolean verifyFile(Path path) throws IOException {
        List<Map<String, Object>> evs = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) evs.add(Json.parseObject(line));
        }
        return verifyChain(evs);
    }

    private static boolean verifyChain(List<Map<String, Object>> evs) {
        String prev = GENESIS;
        for (Map<String, Object> e : evs) {
            if (!prev.equals(e.get("prevHash"))) return false;
            Map<String, Object> copy = new LinkedHashMap<>(e);
            Object hash = copy.remove("hash");
            // Re-serialise via a parse round trip so numbers/maps normalise identically to the write path.
            String recomputed = Hashing.sha256(Json.write(Json.parse(Json.write(copy))));
            if (!recomputed.equals(hash)) return false;
            prev = (String) hash;
        }
        return true;
    }
}
