package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;

/** Evaluates all policies; a policy that throws is treated as a BLOCK (fail closed). */
public final class PolicyEngine {

    private final List<Policy> policies;

    public PolicyEngine(List<Policy> policies) {
        this.policies = List.copyOf(policies);
    }

    /** The default guardrail set for code-changing runs. */
    public static PolicyEngine defaults(List<String> allowedPathPrefixes) {
        return new PolicyEngine(List.of(
                new SecretScanPolicy(),
                new PathAllowlistPolicy(allowedPathPrefixes),
                new ProtectedFilePolicy(),
                new SchemaMigrationPolicy(),
                new DependencyChangePolicy(),
                new ChangeSizePolicy(400, 25),
                new DangerousCodePolicy()));
    }

    public List<Policy> policies() {
        return policies;
    }

    public Evaluation evaluate(String nodeId, List<FileChange> changes, Workspace ws) {
        List<PolicyFinding> findings = new ArrayList<>();
        for (Policy p : policies) {
            try {
                findings.addAll(p.evaluate(nodeId, changes, ws));
            } catch (RuntimeException e) {
                findings.add(new PolicyFinding(p.id(), PolicyFinding.Severity.BLOCK, "*",
                        "policy evaluation error (fail-closed): " + e.getMessage()));
            }
        }
        return new Evaluation(findings);
    }

    public record Evaluation(List<PolicyFinding> findings) {
        public boolean blocked() {
            return findings.stream().anyMatch(f -> f.severity() == PolicyFinding.Severity.BLOCK);
        }

        public boolean requiresApproval() {
            return findings.stream().anyMatch(f -> f.severity() == PolicyFinding.Severity.REQUIRE_APPROVAL);
        }

        public List<PolicyFinding> ofSeverity(PolicyFinding.Severity s) {
            return findings.stream().filter(f -> f.severity() == s).toList();
        }
    }
}
