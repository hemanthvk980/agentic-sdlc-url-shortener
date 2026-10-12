package io.github.hemanthvk980.orchestrator.llm;

import io.github.hemanthvk980.orchestrator.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Record mode: calls the delegate and stores each response under the request's content hash.
 * Replay mode: serves stored responses only — a cache miss is an error (fail closed), which proves the
 * run is a faithful reproduction rather than silently mixing in live calls.
 *
 * <p>This lets a reviewer re-run a recorded scenario with no API key, deterministically.
 */
public final class RecordReplayLlmClient implements LlmClient {

    public enum Mode { RECORD, REPLAY }

    private final Mode mode;
    private final Path dir;
    private final LlmClient delegate;

    private RecordReplayLlmClient(Mode mode, Path dir, LlmClient delegate) {
        this.mode = mode;
        this.dir = dir;
        this.delegate = delegate;
    }

    public static RecordReplayLlmClient recording(LlmClient delegate, Path dir) {
        return new RecordReplayLlmClient(Mode.RECORD, dir, delegate);
    }

    public static RecordReplayLlmClient replaying(Path dir) {
        return new RecordReplayLlmClient(Mode.REPLAY, dir, null);
    }

    @Override
    public String name() {
        return mode == Mode.RECORD ? "record(" + delegate.name() + ")" : "replay(" + dir.getFileName() + ")";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Path file = dir.resolve(request.purpose().replaceAll("[^A-Za-z0-9_.-]", "_") + "-"
                + request.cacheKey().substring(0, 16) + ".json");
        if (mode == Mode.REPLAY) {
            if (!Files.isRegularFile(file)) {
                throw new LlmException("replay miss for '" + request.purpose() + "' (" + file.getFileName()
                        + "): the prompt differs from the recording", false);
            }
            try {
                Map<String, Object> rec = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                return new LlmResponse((String) rec.get("text"), "replay:" + rec.get("model"), 0, 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        LlmResponse r = delegate.complete(request);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("purpose", request.purpose());
        rec.put("model", r.model());
        rec.put("latencyMs", r.latencyMs());
        rec.put("totalTokens", r.totalTokens());
        rec.put("text", r.text());
        try {
            Files.createDirectories(dir);
            Files.writeString(file, Json.pretty(rec), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return r;
    }
}
