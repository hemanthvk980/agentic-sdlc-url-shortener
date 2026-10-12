package io.github.hemanthvk980.orchestrator.gate;

public record GateResult(String gate, boolean passed, String detail) {

    public static GateResult pass(String gate, String detail) {
        return new GateResult(gate, true, detail);
    }

    public static GateResult fail(String gate, String detail) {
        return new GateResult(gate, false, detail);
    }
}
