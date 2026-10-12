package io.github.hemanthvk980.orchestrator.agents;

import io.github.hemanthvk980.orchestrator.engine.Stage;
import io.github.hemanthvk980.orchestrator.engine.StageContext;
import io.github.hemanthvk980.orchestrator.engine.StageResult;
import io.github.hemanthvk980.orchestrator.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic (non-LLM) codebase reasoning: builds a factual index of the repository — modules,
 * packages, HTTP endpoints, JPA entities, migrations, tests. Downstream agents reason over this index,
 * and the impact-analysis exit gate checks the model's claims against it (hallucination guard).
 */
public final class RepoIndexStage implements Stage {

    public static final String ARTIFACT = "repo-index.json";

    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping(?:\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"([^\"]*)\")?");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    @Override
    public StageResult execute(StageContext ctx) {
        var ws = ctx.workspace();
        List<String> files = ws.listFiles();
        List<Map<String, Object>> endpoints = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> migrations = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        Map<String, Integer> packages = new LinkedHashMap<>();
        Map<String, Integer> modules = new LinkedHashMap<>();

        for (String f : files) {
            if (f.endsWith("build.gradle.kts") || f.endsWith("pom.xml")) {
                modules.merge(f.contains("/") ? f.substring(0, f.lastIndexOf('/')) : ".", 1, Integer::sum);
            }
            if (f.matches(".*db/migration/.*\\.sql")) migrations.add(f);
            if (!f.endsWith(".java")) continue;
            if (f.contains("/test/")) { tests.add(f); continue; }
            String src = ws.read(f).orElse("");
            Matcher pm = PACKAGE.matcher(src);
            if (pm.find()) packages.merge(pm.group(1), 1, Integer::sum);
            if (src.contains("@Entity")) entities.add(f);
            if (src.contains("@RestController") || src.contains("@Controller")) {
                Matcher cm = CLASS_MAPPING.matcher(src);
                String base = cm.find() ? cm.group(1) : "";
                Matcher mm = METHOD_MAPPING.matcher(src);
                while (mm.find()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("method", mm.group(1).toUpperCase());
                    e.put("path", base + (mm.group(2) == null ? "" : mm.group(2)));
                    e.put("file", f);
                    endpoints.add(e);
                }
            }
        }

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("greenfield", files.stream().noneMatch(f -> f.endsWith(".java")));
        index.put("fileCount", files.size());
        index.put("files", files.size() > 400 ? files.subList(0, 400) : files);
        index.put("modules", modules.keySet());
        index.put("packages", packages);
        index.put("endpoints", endpoints);
        index.put("entities", entities);
        index.put("migrations", migrations);
        index.put("tests", tests);
        return StageResult.builder()
                .artifact(ARTIFACT, Json.pretty(index))
                .summary(files.size() + " files, " + endpoints.size() + " endpoints, " + entities.size()
                        + " entities, " + migrations.size() + " migrations, " + tests.size() + " test files")
                .build();
    }
}
