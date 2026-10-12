package io.github.hemanthvk980.orchestrator.engine;

/**
 * The unit of work inside a node. Implementations are agents (LLM-backed) or deterministic tools
 * (build runner, repository indexer). A stage only <em>proposes</em>: it returns artifacts and file
 * changes, and the engine decides whether they are allowed to land.
 */
@FunctionalInterface
public interface Stage {
    StageResult execute(StageContext ctx) throws Exception;
}
