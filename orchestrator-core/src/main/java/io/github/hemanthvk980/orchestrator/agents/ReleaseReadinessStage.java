package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.Decision;
import io.github.hemanthvk980.orchestrator.engine.FileChange;
import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Deterministic release checklist (no model call): evaluates objective evidence collected during the
 * run and produces a release-readiness report and release notes. The node is approval-gated: a human
 * makes the final go/no-go with this report in front of them.
 */
public final class ReleaseReadinessStage implements Stage {

    public static final String ARTIFACT = "release-readiness.md";

    private final Supplier<List<Decision>> decisions;
    private final String changelogPath;

    /** @param changelogPath where release notes are appended (inside the run's allowed scope) */
    public ReleaseReadinessStage(Supplier<List<Decision>> decisions, String changelogPath) {
        this.decisions = decisions;
        this.changelogPath = changelogPath;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        Map<String, Object> req = Json.parseObject(ctx.requireArtifact(RequirementsAgent.ARTIFACT));
        Map<String, Object> design = Json.parseObject(ctx.requireArtifact(DesignAgent.ARTIFACT));
        Map<String, Object> review = Json.parseObject(ctx.requireArtifact(ReviewAgent.ARTIFACT));
        List<String> files = ctx.workspace().listFiles();

        List<String[]> checks = new ArrayList<>();
        checks.add(check("Build & tests green", true, "verify stage exit gate passed (precondition of this stage)"));
        long highOpen = JsonPath.objects(review, "findings").stream()
                .filter(f -> List.of("HIGH", "CRITICAL").contains(JsonPath.str(f, "severity", "").toUpperCase())).count();
        boolean riskAccepted = decisions.get().stream().anyMatch(d -> "review".equals(d.nodeId())
                && d.kind() == Decision.Kind.HUMAN_APPROVAL);
        checks.add(check("No unaccepted HIGH/CRITICAL review findings", highOpen == 0 || riskAccepted,
                highOpen + " blocking finding(s)" + (highOpen > 0 && riskAccepted ? " — risk explicitly accepted by human" : "")));
        List<Map<String, Object>> coverage = JsonPath.objects(review, "coverage");
        long covered = coverage.stream().filter(c -> Boolean.TRUE.equals(c.get("implemented")) && Boolean.TRUE.equals(c.get("tested"))).count();
        int criteria = JsonPath.list(req, "acceptanceCriteria").size();
        checks.add(check("Acceptance criteria implemented + tested", covered >= criteria || riskAccepted,
                covered + "/" + criteria + " per independent review"));
        long openHighAmbiguity = JsonPath.objects(req, "ambiguities").stream()
                .filter(a -> "HIGH".equalsIgnoreCase(JsonPath.str(a, "impact", "")) && JsonPath.str(a, "resolution", "").isBlank()).count();
        checks.add(check("No unresolved high-impact ambiguity", openHighAmbiguity == 0, openHighAmbiguity + " open"));
        boolean designApproved = decisions.get().stream().anyMatch(d -> "design".equals(d.nodeId()) && d.kind() == Decision.Kind.HUMAN_APPROVAL);
        checks.add(check("Design approved by a human", designApproved, designApproved ? "yes" : "no approval recorded"));
        boolean hasDocs = files.stream().anyMatch(f -> f.endsWith("README.md"));
        checks.add(check("Documentation present", hasDocs, hasDocs ? "README.md found" : "no README.md"));
        boolean migrationsVersioned = files.stream().filter(f -> f.contains("db/migration/")).allMatch(f -> f.matches(".*/V\\d+__\\w+\\.sql"));
        checks.add(check("Migrations follow V<n>__name.sql", migrationsVersioned, "Flyway naming"));

        boolean ready = checks.stream().allMatch(c -> c[1].equals("PASS"));
        StringBuilder md = new StringBuilder("# Release readiness — ").append(ctx.runId()).append("\n\n");
        md.append("**Verdict:** ").append(ready ? "READY (pending human sign-off)" : "NOT READY").append("\n\n");
        md.append("| Check | Result | Evidence |\n|---|---|---|\n");
        for (String[] c : checks) md.append("| ").append(c[0]).append(" | ").append(c[1]).append(" | ").append(c[2]).append(" |\n");
        md.append("\n## Risks carried forward\n");
        for (Map<String, Object> r : JsonPath.objects(design, "risks")) {
            md.append("- **").append(r.get("impact")).append("** ").append(r.get("risk")).append(" — mitigation: ").append(r.get("mitigation")).append('\n');
        }
        md.append("\n## Review summary\n").append(JsonPath.str(review, "summary", "")).append('\n');

        String notes = "## " + ctx.runId() + "\n\n" + JsonPath.str(design, "summary", "") + "\n\n"
                + JsonPath.objects(design, "api").stream().map(a -> "- `" + a.get("method") + " " + a.get("path") + "`")
                .reduce("", (x, y) -> x + y + "\n");

        StageResult.Builder b = StageResult.builder()
                .artifact(ARTIFACT, md.toString())
                .artifact("release-notes.md", notes)
                .summary(ready ? "all release checks pass" : "release checks failing");
        if (!ready) {
            throw new IllegalStateException("release checklist failed:\n" + md);
        }
        // Release notes are written into the repo as a normal, reviewable change.
        String existing = ctx.workspace().read(changelogPath).orElse("# Changelog\n");
        b.change(FileChange.upsert(changelogPath, existing.strip() + "\n\n" + notes, "release notes",
                ctx.workspace().read(changelogPath).isPresent()));
        return b.build();
    }

    private static String[] check(String name, boolean pass, String evidence) {
        return new String[] {name, pass ? "PASS" : "FAIL", evidence.replace("|", "/")};
    }
}
