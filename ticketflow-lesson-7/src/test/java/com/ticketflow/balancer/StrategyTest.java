package com.ticketflow.balancer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyTest {

    @Test
    void roundRobinTakesTurnsWhateverTheBoxesAreDoing() {
        Backend a = new Backend("a", "http://a");
        Backend b = new Backend("b", "http://b");
        for (int i = 0; i < 50; i++) {
            a.started();
        }
        Strategy strategy = new RoundRobin();
        List<Backend> boxes = List.of(a, b);
        assertEquals("a", strategy.pick(boxes).name());
        assertEquals("b", strategy.pick(boxes).name());
        assertEquals("a", strategy.pick(boxes).name());
    }

    @Test
    void leastConnectionsAvoidsTheBoxThatIsHoldingRequests() {
        Backend busy = new Backend("busy", "http://busy");
        Backend idle = new Backend("idle", "http://idle");
        busy.started();
        busy.started();
        Strategy strategy = new LeastConnections();
        List<Backend> boxes = List.of(busy, idle);
        for (int i = 0; i < 5; i++) {
            assertEquals("idle", strategy.pick(boxes).name());
        }
    }

    @Test
    void leastConnectionsSharesTiesInsteadOfAlwaysPickingTheFirstBox() {
        Backend a = new Backend("a", "http://a");
        Backend b = new Backend("b", "http://b");
        Strategy strategy = new LeastConnections();
        List<Backend> boxes = List.of(a, b);
        int toA = 0;
        for (int i = 0; i < 10; i++) {
            if (strategy.pick(boxes) == a) {
                toA++;
            }
        }
        assertEquals(5, toA);
    }

    @Test
    void stickySendsAReturningClientBackToTheBoxNamedInItsRoute() {
        Backend a = new Backend("a", "http://a");
        Backend b = new Backend("b", "http://b");
        Strategy strategy = new Sticky();
        List<Backend> boxes = List.of(a, b);
        assertEquals("a", strategy.pick(boxes, null).name());
        assertEquals("b", strategy.pick(boxes, null).name());
        for (int i = 0; i < 5; i++) {
            assertEquals("b", strategy.pick(boxes, "b").name());
        }
        // A route naming a box that is no longer behind the balancer falls back to taking turns.
        assertEquals("a", strategy.pick(boxes, "gone").name());
    }

    @Test
    void strategiesAreLookedUpByName() {
        assertEquals("round-robin", Strategy.named("Round-Robin").name());
        assertEquals("least-connections", Strategy.named("least-connections").name());
        assertEquals("sticky", Strategy.named("sticky").name());
        assertThrows(IllegalArgumentException.class, () -> Strategy.named("random"));
    }
}
