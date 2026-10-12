package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Data safety. New Flyway migrations need human approval (schema changes are hard to reverse in
 * production). Editing or deleting an existing migration is blocked outright: it has already run in
 * some environment, Flyway's checksum validation would fail, and history would diverge.
 */
public final class SchemaMigrationPolicy implements Policy {

    private static final Pattern MIGRATION = Pattern.compile(".*db/migration/V\\d+.*\\.sql$");
    private static final Pattern DESTRUCTIVE = Pattern.compile("(?i)\\b(DROP\\s+(TABLE|COLUMN)|TRUNCATE)\\b");

    @Override
    public String id() {
        return "data.schema-migration";
    }

    @Override
    public List<PolicyFinding> evaluate(String nodeId, List<FileChange> changes, Workspace ws) {
        List<PolicyFinding> out = new ArrayList<>();
        for (FileChange c : changes) {
            String p = c.path().replace('\\', '/');
            if (!MIGRATION.matcher(p).matches()) continue;
            boolean exists = ws.read(p).isPresent();
            if (exists) {
                out.add(new PolicyFinding(id(), PolicyFinding.Severity.BLOCK, p,
                        "existing migrations are immutable; add a new versioned migration instead"));
            } else {
                out.add(new PolicyFinding(id(), PolicyFinding.Severity.REQUIRE_APPROVAL, p, "new schema migration"));
                if (c.content() != null && DESTRUCTIVE.matcher(c.content()).find()) {
                    out.add(new PolicyFinding(id(), PolicyFinding.Severity.REQUIRE_APPROVAL, p,
                            "DESTRUCTIVE statement (DROP/TRUNCATE) — confirm data retention plan"));
                }
            }
        }
        return out;
    }
}
