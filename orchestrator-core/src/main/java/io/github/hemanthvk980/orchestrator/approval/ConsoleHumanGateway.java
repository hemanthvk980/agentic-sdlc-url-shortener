package io.github.hemanthvk980.orchestrator.approval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Interactive approvals on stdin/stdout. Serialised (one prompt at a time) so parallel branches never interleave. */
public final class ConsoleHumanGateway implements HumanGateway {

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final PrintStream out = System.out;
    private final String approver;
    private final ReentrantLock lock = new ReentrantLock();

    public ConsoleHumanGateway(String approver) {
        this.approver = approver;
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalRequest r) {
        lock.lock();
        try {
            return doRequestApproval(r);
        } finally {
            lock.unlock();
        }
    }

    private ApprovalDecision doRequestApproval(ApprovalRequest r) {
        out.println();
        out.println("════════════════ APPROVAL REQUIRED ════════════════");
        out.println("Run:   " + r.runId());
        out.println("Stage: " + r.nodeId() + " — " + r.title());
        out.println("Why:");
        r.reasons().forEach(x -> out.println("  • " + x));
        if (!r.changedPaths().isEmpty()) {
            out.println("Changes (" + r.changedPaths().size() + " files):");
            r.changedPaths().stream().limit(30).forEach(p -> out.println("  " + p));
        }
        if (r.summary() != null && !r.summary().isBlank()) out.println("Agent summary: " + r.summary());
        if (r.diffPreview() != null && !r.diffPreview().isBlank()) {
            out.println("--- preview ---");
            r.diffPreview().lines().limit(60).forEach(out::println);
        }
        while (true) {
            out.print("[a]pprove / [r]eject / [c]hanges requested > ");
            out.flush();
            String choice = readLine().trim().toLowerCase();
            if (choice.startsWith("a")) return ApprovalDecision.approved(approver, ask("Comment (optional)"));
            if (choice.startsWith("r")) return ApprovalDecision.rejected(approver, ask("Reason"));
            if (choice.startsWith("c")) return ApprovalDecision.changesRequested(approver, ask("What should change?"));
        }
    }

    @Override
    public Map<String, String> clarify(String runId, String nodeId, List<String> questions) {
        lock.lock();
        try {
            return doClarify(nodeId, questions);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, String> doClarify(String nodeId, List<String> questions) {
        out.println();
        out.println("════════════════ CLARIFICATION NEEDED (" + nodeId + ") ════════════════");
        Map<String, String> answers = new LinkedHashMap<>();
        for (String q : questions) {
            String a = ask(q);
            if (!a.isBlank()) answers.put(q, a);
        }
        return answers;
    }

    private String ask(String prompt) {
        out.print(prompt + ": ");
        out.flush();
        return readLine().trim();
    }

    private String readLine() {
        try {
            String line = in.readLine();
            if (line == null) throw new IllegalStateException("stdin closed while waiting for human input");
            return line;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
