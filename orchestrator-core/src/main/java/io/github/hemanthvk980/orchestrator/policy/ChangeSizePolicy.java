package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;

/**
 * Change control: large change sets are hard to review, so they need explicit sign-off. Deletions
 * always need sign-off.
 */
public final class ChangeSizePolicy implements Policy {

    private final int maxLinesPerFile;
    private final int maxFiles;

    public ChangeSizePolicy(int maxLinesPerFile, int maxFiles) {
        this.maxLinesPerFile = maxLinesPerFile;
        this.maxFiles = maxFiles;
    }

    @Override
    public String id() {
        return "change-control.change-size";
    }

    @Override
    public List<PolicyFinding> evaluate(String nodeId, List<FileChange> changes, Workspace ws) {
        List<PolicyFinding> out = new ArrayList<>();
        if (changes.size() > maxFiles) {
            out.add(new PolicyFinding(id(), PolicyFinding.Severity.REQUIRE_APPROVAL, "*",
                    changes.size() + " files changed (limit " + maxFiles + " without sign-off)"));
        }
        for (FileChange c : changes) {
            if (c.type() == FileChange.Type.DELETE) {
                out.add(new PolicyFinding(id(), PolicyFinding.Severity.REQUIRE_APPROVAL, c.path(), "file deletion"));
            } else if (c.lineCount() > maxLinesPerFile) {
                out.add(new PolicyFinding(id(), PolicyFinding.Severity.WARN, c.path(),
                        c.lineCount() + " lines (> " + maxLinesPerFile + "); consider splitting"));
            }
        }
        return out;
    }
}
