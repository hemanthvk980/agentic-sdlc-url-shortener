package io.github.hemanthvk980.orchestrator.workspace;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The codebase the agents work on. Every mutation is checkpointed so any stage's effect can be rolled
 * back. Paths are always repository-relative; implementations must reject escapes from the root.
 */
public interface Workspace {

    /** Opaque revision id of the current state. */
    String head();

    List<String> listFiles();

    Optional<String> read(String path);

    void apply(List<FileChange> changes);

    /** Records the current state as a new revision; returns its id. */
    String commit(String message);

    /** Discards everything after {@code revision} (rollback). */
    void resetTo(String revision);

    CommandResult run(List<String> command, Duration timeout);

    /** Unified-ish diff between two revisions, for approval requests and reports. */
    String diff(String fromRevision, String toRevision);

    /** Shared validation: repository-relative, no traversal, no absolute paths. */
    static String normalize(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("empty path");
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("absolute path not allowed: " + path);
        for (String part : p.split("/")) {
            if (part.equals("..")) throw new IllegalArgumentException("path traversal not allowed: " + path);
            if (part.equals(".git")) throw new IllegalArgumentException("writing to .git not allowed: " + path);
        }
        return p;
    }
}
