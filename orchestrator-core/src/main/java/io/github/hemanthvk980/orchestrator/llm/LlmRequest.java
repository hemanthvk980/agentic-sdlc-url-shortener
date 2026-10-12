package io.github.hemanthvk980.orchestrator.llm;

import io.github.hemanthvk980.orchestrator.engine.Hashing;

/**
 * @param purpose stable label for the call (e.g. "requirements", "implementation:T3"); used for
 *                metrics, audit and offline fixtures
 * @param system  role/instructions
 * @param prompt  task input; must be deterministic for a given state so replay works
 */
public record LlmRequest(String purpose, String system, String prompt, double temperature, boolean jsonOutput,
                         int maxOutputTokens) {

    public static LlmRequest json(String purpose, String system, String prompt) {
        return new LlmRequest(purpose, system, prompt, 0.2, true, 32768);
    }

    /** Content key for record/replay. Excludes nothing that affects the answer. */
    public String cacheKey() {
        return Hashing.sha256(purpose + "\u0000" + system + "\u0000" + prompt + "\u0000" + temperature + "\u0000" + jsonOutput);
    }
}
