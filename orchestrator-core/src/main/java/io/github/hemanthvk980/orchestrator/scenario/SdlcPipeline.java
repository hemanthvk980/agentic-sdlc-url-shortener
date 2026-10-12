package io.github.hemanthvk980.orchestrator.scenario;

import io.github.hemanthvk980.orchestrator.agents.DesignAgent;
import io.github.hemanthvk980.orchestrator.agents.ImpactAnalysisAgent;
import io.github.hemanthvk980.orchestrator.agents.ReleaseReadinessStage;
import io.github.hemanthvk980.orchestrator.agents.RepoIndexStage;
import io.github.hemanthvk980.orchestrator.agents.RequirementsAgent;
import io.github.hemanthvk980.orchestrator.agents.ReviewAgent;
import io.github.hemanthvk980.orchestrator.agents.TaskExecutionAgent;
import io.github.hemanthvk980.orchestrator.agents.VerifyAndRepairStage;
import io.github.hemanthvk980.orchestrator.engine.Decision;
import io.github.hemanthvk980.orchestrator.gate.Gates;
import io.github.hemanthvk980.orchestrator.graph.RetryPolicy;
import io.github.hemanthvk980.orchestrator.graph.SdlcPhase;
import io.github.hemanthvk980.orchestrator.graph.StageNode;
import io.github.hemanthvk980.orchestrator.graph.TaskGraph;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * The standard SDLC graph:
 *
 * <pre>
 *  requirements ─┐
 *                ├─► impact-analysis ─► design 🔒 ─┬─► implementation ─┐
 *  repo-index ───┘                                ├─► tests ──────────┼─► verify ─► review ─► release-readiness 🔒
 *                                                 └─► docs ───────────┘
 * </pre>
 *
 * Two parallel fan-outs (requirements ∥ repo-index; implementation ∥ tests ∥ docs) with explicit joins.
 */
public final class SdlcPipeline {

    private SdlcPipeline() {}

    /** Release notes live at the root of the run's scope (e.g. url-shortener-service/CHANGELOG.md). */
    static String changelogPath(Scenario s) {
        List<String> scope = s.allowedPaths();
        return scope.size() == 1 && scope.get(0).endsWith("/") ? scope.get(0) + "CHANGELOG.md" : "CHANGELOG.md";
    }

    public static TaskGraph build(Scenario s, String baseRevision, Supplier<List<Decision>> decisions, List<String> verifyCommand) {
        String c = s.constraints();
        RetryPolicy agentRetry = RetryPolicy.of(3, Duration.ofSeconds(2));
        List<String> verify = verifyCommand == null || verifyCommand.isEmpty()
                ? List.of("./gradlew", "test", "--no-daemon", "-q") : verifyCommand;

        return TaskGraph.of(List.of(
                StageNode.builder("requirements", new RequirementsAgent(c))
                        .title("Requirements analysis").phase(SdlcPhase.REQUIREMENTS)
                        .retry(agentRetry)
                        .exitGate(Gates.jsonArtifactHasKeys(RequirementsAgent.ARTIFACT,
                                "problemStatement", "functionalRequirements", "acceptanceCriteria", "ambiguities"))
                        .exitGate(Gates.noOpenHighImpactAmbiguities(RequirementsAgent.ARTIFACT))
                        .build(),

                StageNode.builder("repo-index", new RepoIndexStage())
                        .title("Codebase index").phase(SdlcPhase.ANALYSIS)
                        .build(),

                StageNode.builder("impact-analysis", new ImpactAnalysisAgent(c))
                        .title("Impact analysis").phase(SdlcPhase.ANALYSIS)
                        .dependsOn("requirements", "repo-index")
                        .retry(agentRetry)
                        .exitGate(Gates.jsonArtifactHasKeys(ImpactAnalysisAgent.ARTIFACT, "impactedFiles", "regressionRisks"))
                        .exitGate(ImpactAnalysisAgent.impactedFilesExist())
                        .build(),

                StageNode.builder("design", new DesignAgent(c))
                        .title("Architecture & task plan").phase(SdlcPhase.DESIGN)
                        .dependsOn("impact-analysis")
                        .entryGate(Gates.noOpenHighImpactAmbiguities(RequirementsAgent.ARTIFACT))
                        .retry(agentRetry)
                        .exitGate(DesignAgent.schema())
                        .exitGate(DesignAgent.taskPlanValid())
                        .requiresApproval(true)
                        .maxRevisions(2)
                        .build(),

                StageNode.builder("implementation", TaskExecutionAgent.implementation(c))
                        .title("Implementation").phase(SdlcPhase.IMPLEMENTATION)
                        .dependsOn("design")
                        .entryGate(Gates.artifactPresent(DesignAgent.ARTIFACT))
                        .retry(agentRetry)
                        .build(),

                StageNode.builder("tests", TaskExecutionAgent.tests(c))
                        .title("Test authoring").phase(SdlcPhase.TESTING)
                        .dependsOn("design")
                        .retry(agentRetry)
                        .build(),

                StageNode.builder("docs", TaskExecutionAgent.docs(c))
                        .title("Documentation").phase(SdlcPhase.DOCUMENTATION)
                        .dependsOn("design")
                        .retry(agentRetry)
                        .build(),

                StageNode.builder("verify", new VerifyAndRepairStage(c))
                        .title("Build, test & repair").phase(SdlcPhase.VALIDATION)
                        .dependsOn("implementation", "tests", "docs")
                        .retry(RetryPolicy.of(3, Duration.ofSeconds(1)))
                        .exitGate(Gates.commandSucceeds("build-and-test", verify, Duration.ofSeconds(s.verifyTimeoutSeconds())))
                        .build(),

                StageNode.builder("review", new ReviewAgent(baseRevision))
                        .title("Independent review").phase(SdlcPhase.VALIDATION)
                        .dependsOn("verify")
                        .retry(agentRetry)
                        .exitGate(Gates.jsonArtifactHasKeys(ReviewAgent.ARTIFACT, "verdict", "coverage", "findings"))
                        .build(),

                StageNode.builder("release-readiness", new ReleaseReadinessStage(decisions, changelogPath(s)))
                        .title("Release readiness").phase(SdlcPhase.RELEASE)
                        .dependsOn("review")
                        .entryGate(Gates.auditChainIntact())
                        .exitGate(Gates.auditChainIntact())
                        .requiresApproval(true)
                        .build()));
    }
}
