package io.github.hemanthvk980.orchestrator.engine;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Safe-stop control. Once tripped (by a human, a budget breach, or a critical policy violation) no new
 * node starts, in-flight stages are interrupted at their next cancellation checkpoint, and nothing
 * further is applied to the workspace.
 */
public final class KillSwitch {

    private final AtomicReference<String> reason = new AtomicReference<>();

    public void trip(String why) {
        reason.compareAndSet(null, why);
    }

    public boolean isTripped() {
        return reason.get() != null;
    }

    public String reason() {
        return reason.get();
    }

    public void throwIfStopped() {
        String r = reason.get();
        if (r != null) throw new SafeStopException(r);
    }

    public static final class SafeStopException extends RuntimeException {
        public SafeStopException(String reason) {
            super("safe-stop: " + reason);
        }
    }
}
