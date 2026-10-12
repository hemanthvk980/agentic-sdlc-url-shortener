package io.github.hemanthvk980.orchestrator.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a subprocess with a hard timeout and bounded output capture. */
public final class ProcessRunner {

    private static final int MAX_OUTPUT_BYTES = 512 * 1024;

    private ProcessRunner() {}

    public static CommandResult run(Path dir, List<String> command, Duration timeout) {
        long start = System.nanoTime();
        Process p;
        try {
            p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new CommandResult(command, CommandResult.NOT_RUNNABLE, "could not start: " + e.getMessage(), 0);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Platform thread on purpose: a virtual pump can be starved if every carrier thread is pinned.
        Thread pump = Thread.ofPlatform().daemon().start(() -> copyBounded(p.getInputStream(), out));
        try {
            boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                pump.join(1000);
                return new CommandResult(command, CommandResult.TIMED_OUT,
                        out.toString(StandardCharsets.UTF_8) + "\n[timed out after " + timeout + "]", elapsed(start));
            }
            pump.join(5000);
            return new CommandResult(command, p.exitValue(), out.toString(StandardCharsets.UTF_8), elapsed(start));
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return new CommandResult(command, CommandResult.TIMED_OUT, "interrupted", elapsed(start));
        }
    }

    /** Like {@link #run} but throws if the command fails — for internal plumbing such as git. */
    public static String check(Path dir, List<String> command) {
        CommandResult r = run(dir, command, Duration.ofSeconds(60));
        if (r.exitCode() != 0) throw new UncheckedIOException(new IOException(command + " failed: " + r.output()));
        return r.output();
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void copyBounded(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (out) {
                    if (out.size() < MAX_OUTPUT_BYTES) out.write(buf, 0, Math.min(n, MAX_OUTPUT_BYTES - out.size()));
                }
            }
        } catch (IOException ignored) {
            // process ended; partial output is fine
        }
    }
}
