package io.github.hemanthvk980.orchestrator.llm;

public record LlmResponse(String text, String model, long latencyMs, long totalTokens) {
}
