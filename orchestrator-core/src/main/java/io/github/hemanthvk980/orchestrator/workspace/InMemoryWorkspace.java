package io.github.hemanthvk980.orchestrator.workspace;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * In-memory workspace with snapshot revisions. Used by unit tests and offline demos; command
 * execution is delegated to a pluggable function so tests can simulate builds passing or failing.
 */
public final class InMemoryWorkspace implements Workspace {

    private final Map<String, String> files = new TreeMap<>();
    private final Map<String, Map<String, String>> revisions = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private Function<List<String>, CommandResult> commandHandler =
            cmd -> new CommandResult(cmd, 0, "ok (simulated)", 1);
    private String head;

    public InMemoryWorkspace() {
        head = snapshot("initial");
    }

    public synchronized InMemoryWorkspace withFile(String path, String content) {
        files.put(Workspace.normalize(path), content);
        head = snapshot("seed " + path);
        return this;
    }

    public synchronized void onCommand(Function<List<String>, CommandResult> handler) {
        this.commandHandler = handler;
    }

    @Override public synchronized String head() { return head; }

    @Override public synchronized List<String> listFiles() { return List.copyOf(files.keySet()); }

    @Override public synchronized Optional<String> read(String path) {
        return Optional.ofNullable(files.get(Workspace.normalize(path)));
    }

    @Override
    public synchronized void apply(List<FileChange> changes) {
        for (FileChange c : changes) {
            String p = Workspace.normalize(c.path());
            if (c.type() == FileChange.Type.DELETE) files.remove(p);
            else files.put(p, c.content());
        }
    }

    @Override public synchronized String commit(String message) {
        head = snapshot(message);
        return head;
    }

    @Override
    public synchronized void resetTo(String revision) {
        Map<String, String> snap = revisions.get(revision);
        if (snap == null) throw new IllegalArgumentException("unknown revision " + revision);
        files.clear();
        files.putAll(snap);
        int idx = order.indexOf(revision);
        for (String later : List.copyOf(order.subList(idx + 1, order.size()))) revisions.remove(later);
        order.subList(idx + 1, order.size()).clear();
        head = revision;
    }

    @Override
    public synchronized CommandResult run(List<String> command, Duration timeout) {
        return commandHandler.apply(command);
    }

    @Override
    public synchronized String diff(String from, String to) {
        Map<String, String> a = revisions.getOrDefault(from, Map.of());
        Map<String, String> b = revisions.getOrDefault(to, files);
        StringBuilder sb = new StringBuilder();
        for (String p : b.keySet()) {
            if (!a.containsKey(p)) sb.append("A ").append(p).append('\n');
            else if (!a.get(p).equals(b.get(p))) sb.append("M ").append(p).append('\n');
        }
        for (String p : a.keySet()) if (!b.containsKey(p)) sb.append("D ").append(p).append('\n');
        return sb.toString();
    }

    private String snapshot(String message) {
        String id = "rev" + (order.size() + 1);
        revisions.put(id, new TreeMap<>(files));
        order.add(id);
        return id;
    }
}
