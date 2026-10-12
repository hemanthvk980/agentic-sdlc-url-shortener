package io.github.hemanthvk980.orchestrator.workspace;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Workspace backed by a git repository on disk (git CLI, no library dependency). Each node's changes
 * become one commit on the run branch, so rollback is {@code git reset --hard <checkpoint>} and every
 * change is reviewable with ordinary git tooling after the run.
 */
public final class GitWorkspace implements Workspace {

    private final Path root;

    private GitWorkspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Opens (or initialises) a repository and checks out {@code branch} for this run. */
    public static GitWorkspace open(Path root, String branch) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GitWorkspace ws = new GitWorkspace(root);
        if (!Files.isDirectory(root.resolve(".git"))) {
            ws.git("init", "-q", "-b", "main");
            ws.git("config", "user.name", "sdlc-orchestrator");
            ws.git("config", "user.email", "orchestrator@localhost");
            ws.git("commit", "-q", "--allow-empty", "-m", "workspace: initial empty commit");
        }
        ws.git("checkout", "-q", "-B", branch);
        return ws;
    }

    public Path root() {
        return root;
    }

    @Override
    public String head() {
        return git("rev-parse", "HEAD").trim();
    }

    @Override
    public List<String> listFiles() {
        String out = git("ls-files", "--cached", "--others", "--exclude-standard");
        return out.lines().filter(l -> !l.isBlank()).sorted().toList();
    }

    @Override
    public Optional<String> read(String path) {
        Path p = resolve(path);
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void apply(List<FileChange> changes) {
        for (FileChange c : changes) {
            Path p = resolve(c.path());
            try {
                if (c.type() == FileChange.Type.DELETE) {
                    Files.deleteIfExists(p);
                } else {
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, c.content(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public String commit(String message) {
        git("add", "-A");
        git("commit", "-q", "--allow-empty", "-m", message);
        return head();
    }

    @Override
    public void resetTo(String revision) {
        git("reset", "-q", "--hard", revision);
        git("clean", "-q", "-fd");
    }

    @Override
    public CommandResult run(List<String> command, Duration timeout) {
        return ProcessRunner.run(root, command, timeout);
    }

    @Override
    public String diff(String fromRevision, String toRevision) {
        return git("diff", "--stat", "--patch", fromRevision, toRevision);
    }

    private Path resolve(String path) {
        Path p = root.resolve(Workspace.normalize(path)).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("path escapes workspace: " + path);
        return p;
    }

    private String git(String... args) {
        return ProcessRunner.check(root, Stream.concat(Stream.of("git"), Stream.of(args)).toList());
    }
}
