package io.github.hemanthvk980.orchestrator.workspace;

import java.util.List;

public record CommandResult(List<String> command, int exitCode, String output, long durationMs) {

    /** Exit code used when the command could not be started at all (tool not installed). */
    public static final int NOT_RUNNABLE = 127;
    public static final int TIMED_OUT = 124;

    public String tail(int lines) {
        List<String> all = output.lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }
}
