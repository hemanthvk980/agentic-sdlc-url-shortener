package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Security: blocks credentials in any proposed file. Pattern-based (like gitleaks' core rules), so it
 * complements but does not replace a full secret scanner in CI.
 */
public final class SecretScanPolicy implements Policy {

    private static final Map<String, Pattern> RULES = Map.of(
            "google-api-key", Pattern.compile("AIza[0-9A-Za-z_\\-]{35}"),
            "openai-key", Pattern.compile("sk-(proj-)?[A-Za-z0-9_\\-]{20,}"),
            "anthropic-key", Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{20,}"),
            "aws-access-key", Pattern.compile("AKIA[0-9A-Z]{16}"),
            "github-token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
            "private-key", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH |)PRIVATE KEY-----"),
            "hardcoded-password", Pattern.compile(
                    "(?i)(password|passwd|secret)\\s*[:=]\\s*[\"'](?!\\$\\{)(?!changeme)[^\"'\\s]{6,}[\"']"));

    @Override
    public String id() {
        return "security.secret-scan";
    }

    @Override
    public List<PolicyFinding> evaluate(String nodeId, List<FileChange> changes, Workspace ws) {
        List<PolicyFinding> out = new ArrayList<>();
        for (FileChange c : changes) {
            if (c.content() == null) continue;
            RULES.forEach((rule, pattern) -> {
                if (pattern.matcher(c.content()).find()) {
                    out.add(new PolicyFinding(id(), PolicyFinding.Severity.BLOCK, c.path(),
                            "possible secret (" + rule + "); secrets must come from environment/secret store"));
                }
            });
        }
        return out;
    }
}
