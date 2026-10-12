package io.github.hemanthvk980.orchestrator.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.InMemoryWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private final InMemoryWorkspace ws = new InMemoryWorkspace()
            .withFile("svc/src/main/resources/db/migration/V1__init.sql", "create table t(id int);");
    private final PolicyEngine engine = PolicyEngine.defaults(List.of("svc/"));

    private static FileChange add(String path, String content) {
        return new FileChange(path, FileChange.Type.ADD, content, "test");
    }

    @Test
    void cleanChangePasses() {
        var e = engine.evaluate("impl", List.of(add("svc/src/main/java/A.java", "class A {}")), ws);
        assertFalse(e.blocked());
        assertFalse(e.requiresApproval());
    }

    @Test
    void secretsAreBlocked() {
        String key = "AIza" + "Sy" + "A".repeat(33);
        assertTrue(engine.evaluate("impl", List.of(add("svc/a.yml", "key: " + key)), ws).blocked());
        assertTrue(engine.evaluate("impl", List.of(add("svc/a.yml", "password: \"hunter22secret\"")), ws).blocked());
        assertTrue(engine.evaluate("impl", List.of(add("svc/k.pem", "-----BEGIN PRIVATE KEY-----")), ws).blocked());
        assertFalse(engine.evaluate("impl", List.of(add("svc/a.yml", "password: ${DB_PASSWORD}")), ws).blocked());
    }

    @Test
    void pathScopeAndTraversalAreEnforced() {
        assertTrue(engine.evaluate("impl", List.of(add("other/x.java", "x")), ws).blocked());
        assertTrue(engine.evaluate("impl", List.of(add("svc/../../x", "x")), ws).blocked());
        assertTrue(engine.evaluate("impl", List.of(add("/etc/passwd", "x")), ws).blocked());
        assertTrue(engine.evaluate("impl", List.of(add("svc/.git/config", "x")), ws).blocked());
    }

    @Test
    void migrationsNeedApprovalAndHistoryIsImmutable() {
        var newMigration = engine.evaluate("impl", List.of(add("svc/src/main/resources/db/migration/V2__add.sql", "alter table t add c int;")), ws);
        assertFalse(newMigration.blocked());
        assertTrue(newMigration.requiresApproval());

        var editOld = engine.evaluate("impl", List.of(new FileChange("svc/src/main/resources/db/migration/V1__init.sql",
                FileChange.Type.MODIFY, "drop table t;", "oops")), ws);
        assertTrue(editOld.blocked());

        var destructive = engine.evaluate("impl", List.of(add("svc/src/main/resources/db/migration/V3__drop.sql", "DROP TABLE t;")), ws);
        assertEquals(2, destructive.ofSeverity(PolicyFinding.Severity.REQUIRE_APPROVAL).size());
    }

    @Test
    void dependencyChangesNeedApprovalAndDynamicVersionsAreBlocked() {
        assertTrue(engine.evaluate("impl", List.of(add("svc/build.gradle.kts",
                "dependencies { implementation(\"com.x:y:1.2.3\") }")), ws).requiresApproval());
        assertTrue(engine.evaluate("impl", List.of(add("svc/build.gradle.kts",
                "dependencies { implementation(\"com.x:y:1.+\") }")), ws).blocked());
    }

    @Test
    void protectedFilesAndRiskyCodeNeedApproval() {
        assertTrue(engine.evaluate("impl", List.of(add("svc/Dockerfile", "FROM x")), ws).requiresApproval());
        assertTrue(engine.evaluate("impl", List.of(add("svc/src/main/java/C.java",
                "@CrossOrigin(\"*\") class C {}")), ws).requiresApproval());
        assertTrue(engine.evaluate("impl", List.of(add("svc/src/main/java/R.java",
                "String q = \"SELECT * FROM t WHERE id=\" + id;")), ws).requiresApproval());
    }

    @Test
    void failingPolicyFailsClosed() {
        Policy broken = new Policy() {
            @Override public String id() { return "broken"; }
            @Override public List<PolicyFinding> evaluate(String n, List<FileChange> c, io.github.hemanthvk980.orchestrator.workspace.Workspace w) {
                throw new IllegalStateException("boom");
            }
        };
        assertTrue(new PolicyEngine(List.of(broken)).evaluate("x", List.of(), ws).blocked());
    }
}
