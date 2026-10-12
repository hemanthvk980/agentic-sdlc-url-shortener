package io.github.hemanthvk980.orchestrator.metrics;

import io.github.hemanthvk980.orchestrator.json.Json;
import io.github.hemanthvk980.orchestrator.json.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Cross-run reliability view: success rate, retry/rollback frequency, MTTR and latency percentiles,
 * computed from every {@code runs/<id>/run.json} on disk.
 */
public final class MetricsAggregator {

    private MetricsAggregator() {}

    public static Map<String, Object> aggregate(Path runsDir) throws IOException {
        List<Map<String, Object>> runs = new ArrayList<>();
        if (Files.isDirectory(runsDir)) {
            try (Stream<Path> s = Files.list(runsDir)) {
                for (Path dir : s.filter(Files::isDirectory).sorted().toList()) {
                    Path f = dir.resolve("run.json");
                    if (Files.isRegularFile(f)) runs.add(Json.parseObject(Files.readString(f)));
                }
            }
        }
        long total = runs.size();
        long succeeded = runs.stream().filter(r -> "SUCCEEDED".equals(r.get("outcome"))).count();
        List<Long> latencies = new ArrayList<>();
        long retries = 0, rollbacks = 0, recoveries = 0, nodeExecutions = 0;
        double mttrSum = 0;
        for (Map<String, Object> r : runs) {
            Map<String, Object> m = JsonPath.obj(r, "metrics");
            latencies.add(num(m.get("e2eLatencyExcludingHumanMs")));
            retries += num(m.get("retries"));
            rollbacks += num(m.get("rollbacks"));
            long rec = num(m.get("recoveries"));
            recoveries += rec;
            mttrSum += num(m.get("mttrMs")) * rec;
            nodeExecutions += JsonPath.obj(m, "nodes").size();
        }
        latencies.sort(Long::compare);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", total);
        out.put("successRate", total == 0 ? 0 : (double) succeeded / total);
        out.put("retryRatePerNode", nodeExecutions == 0 ? 0 : (double) retries / nodeExecutions);
        out.put("rollbackRatePerNode", nodeExecutions == 0 ? 0 : (double) rollbacks / nodeExecutions);
        out.put("mttrMs", recoveries == 0 ? 0 : Math.round(mttrSum / recoveries));
        out.put("latencyP50Ms", percentile(latencies, 0.50));
        out.put("latencyP95Ms", percentile(latencies, 0.95));
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
