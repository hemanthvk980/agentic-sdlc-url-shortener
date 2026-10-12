package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;

/** Change control: agents may only write inside the paths the run was scoped to. */
public final class PathAllowlistPolicy implements Policy {

    private final List<String> allowedPrefixes;

    public PathAllowlistPolicy(List<String> allowedPrefixes) {
        this.allowedPrefixes = List.copyOf(allowedPrefixes);
    }

    @Override
    public String id() {
        return "change-control.path-allowlist";
    }

    @Override
    public List<PolicyFinding> evaluate(String nodeId, List<FileChange> changes, Workspace ws) {
        List<PolicyFinding> out = new ArrayList<>();
        for (FileChange c : changes) {
            String p;
            try {
                p = Workspace.normalize(c.path());
            } catch (IllegalArgumentException e) {
                out.add(new PolicyFinding(id(), PolicyFinding.Severity.BLOCK, c.path(), e.getMessage()));
                continue;
            }
            boolean allowed = allowedPrefixes.isEmpty() || allowedPrefixes.stream().anyMatch(p::startsWith);
            if (!allowed) {
                out.add(new PolicyFinding(id(), PolicyFinding.Severity.BLOCK, p,
                        "outside run scope " + allowedPrefixes));
            }
        }
        return out;
    }
}
