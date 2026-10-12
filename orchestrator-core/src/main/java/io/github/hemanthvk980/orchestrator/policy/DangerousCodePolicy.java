package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Security: flags code patterns that are rarely right in a web service and commonly wrong in
 * generated code. Lightweight; real SAST (e.g. SpotBugs/FindSecBugs, CodeQL) belongs in CI.
 */
public final class DangerousCodePolicy implements Policy {

    private static final Map<Pattern, String> RULES = Map.of(
            Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|new ProcessBuilder"), "process execution in service code",
            Pattern.compile("@CrossOrigin\\(\\s*(origins\\s*=\\s*)?\"\\*\""), "wildcard CORS",
            Pattern.compile("csrf\\(\\s*\\w*\\s*->\\s*\\w*\\.disable\\(\\)\\)|csrf\\(\\)\\.disable\\(\\)"), "CSRF disabled",
            Pattern.compile("\"\\s*(SELECT|UPDATE|DELETE|INSERT)\\b[^\"]*\"\\s*\\+"), "string-concatenated SQL",
            Pattern.compile("TrustAllCerts|setHostnameVerifier\\(\\s*\\(.*\\)\\s*->\\s*true"), "TLS verification disabled",
            Pattern.compile("ObjectInputStream"), "Java deserialization");

    @Override
    public String id() {
        return "security.dangerous-code";
    }

    @Override
    public List<PolicyFinding> evaluate(String nodeId, List<FileChange> changes, Workspace ws) {
        List<PolicyFinding> out = new ArrayList<>();
        for (FileChange c : changes) {
            if (c.content() == null || !c.path().endsWith(".java") || c.path().contains("/test/")) continue;
            RULES.forEach((pattern, label) -> {
                if (pattern.matcher(c.content()).find()) {
                    out.add(new PolicyFinding(id(), PolicyFinding.Severity.REQUIRE_APPROVAL, c.path(), label));
                }
            });
        }
        return out;
    }
}
