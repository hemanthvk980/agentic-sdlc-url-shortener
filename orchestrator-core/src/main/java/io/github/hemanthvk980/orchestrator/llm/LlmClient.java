package io.github.hemanthvk980.orchestrator.llm;

/** Provider-neutral model interface. Agents depend only on this. */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    /** Identifier for reports, e.g. "gemini:gemini-2.5-flash". */
    String name();
}
