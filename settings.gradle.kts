rootProject.name = "agentic-sdlc-url-shortener"

include(
    "orchestrator-core",      // Agentic SDLC engine: DAG, gates, policies, approvals, audit, metrics (pure Java)
    "url-shortener-service",  // The product the orchestrator builds and evolves (Spring Boot)
)
