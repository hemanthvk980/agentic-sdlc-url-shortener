package io.github.hemanthvk980.orchestrator.graph;

import static io.github.hemanthvk980.orchestrator.TestStages.node;
import static io.github.hemanthvk980.orchestrator.TestStages.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskGraphTest {

    @Test
    void computesParallelLayers() {
        TaskGraph g = TaskGraph.of(List.of(
                node("a", ok("a")).build(),
                node("b", ok("b")).dependsOn("a").build(),
                node("c", ok("c")).dependsOn("a").build(),
                node("d", ok("d")).dependsOn("b", "c").build(),
                node("e", ok("e")).build()));
        assertEquals(List.of(List.of("a", "e"), List.of("b", "c"), List.of("d")), g.layers());
        assertEquals(List.of("b", "c", "d"), g.transitiveDependents("a"));
        assertEquals(List.of("d"), g.transitiveDependents("b"));
    }

    @Test
    void rejectsCycles() {
        GraphValidationException e = assertThrows(GraphValidationException.class, () -> TaskGraph.of(List.of(
                node("a", ok("a")).dependsOn("c").build(),
                node("b", ok("b")).dependsOn("a").build(),
                node("c", ok("c")).dependsOn("b").build())));
        assertTrue(e.getMessage().contains("cycle"));
    }

    @Test
    void rejectsUnknownAndSelfDependencies() {
        assertThrows(GraphValidationException.class, () -> TaskGraph.of(List.of(node("a", ok("a")).dependsOn("zzz").build())));
        assertThrows(GraphValidationException.class, () -> TaskGraph.of(List.of(node("a", ok("a")).dependsOn("a").build())));
        assertThrows(GraphValidationException.class, () -> TaskGraph.of(List.of(node("a", ok("a")).build(), node("a", ok("a")).build())));
    }

    @Test
    void mermaidIncludesEdgesAndApprovalMarker() {
        TaskGraph g = TaskGraph.of(List.of(node("a", ok("a")).build(), node("b", ok("b")).dependsOn("a").requiresApproval(true).build()));
        String m = g.toMermaid(java.util.Map.of("a", NodeStatus.SUCCEEDED));
        assertTrue(m.contains("a --> b"));
        assertTrue(m.contains("🔒"));
        assertTrue(m.contains("class a ok"));
    }

    @Test
    void retryBackoffIsExponentialAndCapped() {
        RetryPolicy p = new RetryPolicy(5, Duration.ofMillis(100), 2.0, Duration.ofMillis(300));
        assertEquals(Duration.ZERO, p.backoffBefore(1));
        assertEquals(Duration.ofMillis(100), p.backoffBefore(2));
        assertEquals(Duration.ofMillis(200), p.backoffBefore(3));
        assertEquals(Duration.ofMillis(300), p.backoffBefore(4));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ZERO, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(11, Duration.ZERO, 1, Duration.ZERO));
    }
}
