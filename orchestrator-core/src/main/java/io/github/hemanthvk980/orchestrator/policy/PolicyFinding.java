package io.github.hemanthvk980.orchestrator.policy;

import io.github.hemanthvk980.orchestrator.json.JsonWritable;
import java.util.LinkedHashMap;
import java.util.Map;

public record PolicyFinding(String policy, Severity severity, String path, String message) implements JsonWritable {

    /**
     * BLOCK: the change may not land, full stop (rolled back; no human override at this layer).
     * REQUIRE_APPROVAL: may land only after an explicit human approval.
     * WARN: recorded in the audit trail and report.
     */
    public enum Severity { WARN, REQUIRE_APPROVAL, BLOCK }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy", policy);
        m.put("severity", severity);
        m.put("path", path);
        m.put("message", message);
        return m;
    }
}
