package io.github.hemanthvk980.orchestrator.gate;

/**
 * A deterministic check guarding entry to or exit from a node. Gates are code, not prompts: an agent
 * cannot talk its way past one. Entry gates protect a stage from bad inputs; exit gates stop a stage's
 * output from propagating downstream until it meets a defined bar.
 */
public interface Gate {

    String name();

    GateResult evaluate(GateContext ctx);
}
