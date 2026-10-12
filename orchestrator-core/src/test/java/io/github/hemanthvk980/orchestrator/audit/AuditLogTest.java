package io.github.hemanthvk980.orchestrator.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {

    @TempDir Path tmp;

    @Test
    void chainVerifiesInMemoryAndOnDisk() throws Exception {
        Path f = tmp.resolve("audit.jsonl");
        AuditLog log = new AuditLog("r1", f);
        log.record("run.started", null, Map.of("x", 1));
        log.record("node.status", "design", Map.of("from", "PENDING", "to", "RUNNING", "list", List.of(1, 2.5, "a")));
        log.record("run.finished", null, Map.of());
        assertTrue(log.verify());
        assertEquals(3, log.size());
        assertTrue(AuditLog.verifyFile(f));
    }

    @Test
    void tamperingWithAnyEventBreaksTheChain() throws Exception {
        Path f = tmp.resolve("audit.jsonl");
        AuditLog log = new AuditLog("r1", f);
        log.record("approval.decided", "design", Map.of("outcome", "REJECTED"));
        log.record("node.status", "design", Map.of("to", "FAILED"));
        String tampered = Files.readString(f).replace("REJECTED", "APPROVED");
        Files.writeString(f, tampered);
        assertFalse(AuditLog.verifyFile(f));
    }

    @Test
    void deletingAnEventBreaksTheChain() throws Exception {
        Path f = tmp.resolve("audit.jsonl");
        AuditLog log = new AuditLog("r1", f);
        for (int i = 0; i < 3; i++) log.record("e", null, Map.of("i", i));
        List<String> lines = Files.readAllLines(f);
        Files.write(f, List.of(lines.get(0), lines.get(2)));
        assertFalse(AuditLog.verifyFile(f));
    }
}
