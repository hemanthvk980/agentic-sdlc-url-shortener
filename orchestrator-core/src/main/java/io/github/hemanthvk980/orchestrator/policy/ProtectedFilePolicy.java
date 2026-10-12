package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;

/**
 * Change control: CI pipelines, security config and ownership files govern how code is verified and
 * deployed. An agent changing them could weaken the very controls that check its work.
 */
public final class ProtectedFilePolicy implements Policy {

    private static final List<String> PROTECTED = List.of(
            ".github/", "CODEOWNERS", "Dockerfile", "docker-compose", ".gitignore", "gradle/wrapper/", "gradlew");

    @Override
    public String id() {
        return "change-control.protected-files";
    }

    @Override
    public List<PolicyFinding> evaluate(String nodeId, List<FileChange> changes, Workspace ws) {
        List<PolicyFinding> out = new ArrayList<>();
        for (FileChange c : changes) {
            String p = c.path().replace('\\', '/');
            String file = p.substring(p.lastIndexOf('/') + 1);
            boolean hit = PROTECTED.stream().anyMatch(x -> x.endsWith("/") ? p.contains(x) : file.startsWith(x));
            if (hit) {
                out.add(new PolicyFinding(id(), PolicyFinding.Severity.REQUIRE_APPROVAL, p,
                        "protected build/CI/security file"));
            }
        }
        return out;
    }
}
