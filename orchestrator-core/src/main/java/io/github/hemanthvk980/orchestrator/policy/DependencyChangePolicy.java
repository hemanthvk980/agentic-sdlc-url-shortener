package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supply-chain: build file changes need approval; dynamic/snapshot versions are blocked because they
 * make builds non-reproducible.
 */
public final class DependencyChangePolicy implements Policy {

    private static final Pattern DYNAMIC = Pattern.compile("[\"'][\\w.\\-]+:[\\w.\\-]+:([^\"']*(\\+|SNAPSHOT|latest)[^\"']*)[\"']");

    @Override
    public String id() {
        return "supply-chain.dependencies";
    }

    @Override
    public List<PolicyFinding> evaluate(String nodeId, List<FileChange> changes, Workspace ws) {
        List<PolicyFinding> out = new ArrayList<>();
        for (FileChange c : changes) {
            String p = c.path().replace('\\', '/');
            boolean buildFile = p.endsWith("build.gradle.kts") || p.endsWith("build.gradle") || p.endsWith("pom.xml")
                    || p.endsWith("libs.versions.toml") || p.endsWith("settings.gradle.kts") || p.endsWith("package.json");
            if (!buildFile) continue;
            out.add(new PolicyFinding(id(), PolicyFinding.Severity.REQUIRE_APPROVAL, p, "build/dependency change"));
            if (c.content() != null) {
                Matcher m = DYNAMIC.matcher(c.content());
                while (m.find()) {
                    out.add(new PolicyFinding(id(), PolicyFinding.Severity.BLOCK, p,
                            "non-reproducible dependency version: " + m.group(1)));
                }
            }
        }
        return out;
    }
}
