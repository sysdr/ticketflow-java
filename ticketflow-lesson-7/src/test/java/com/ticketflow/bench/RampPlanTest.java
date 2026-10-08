package com.ticketflow.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RampPlanTest {

    private static final List<String> BOXES = List.of("http://localhost:8081/", "http://localhost:8082");

    @Test
    void withoutABalancerBuyersGoStraightToTheBoxes() {
        RampPlan plan = RampPlan.direct("direct", BOXES, 50, 100, 50, 2).validated();
        assertEquals(List.of("http://localhost:8081", "http://localhost:8082"), plan.entryPoints());
        assertNull(plan.strategy());
    }

    @Test
    void withABalancerBuyersGoToItsProxyPath() {
        RampPlan plan = new RampPlan("lb", "g", BOXES, "http://localhost:8080", "Least-Connections", null, null, 50, 100, 50, 2).validated();
        assertEquals(List.of("http://localhost:8080/lb"), plan.entryPoints());
        assertEquals("least-connections", plan.strategy());
    }

    @Test
    void aFaultMustNameOneOfTheBoxes() {
        RampPlan.Fault elsewhere = new RampPlan.Fault("http://localhost:9999", "slow", 3);
        assertThrows(IllegalArgumentException.class,
                () -> new RampPlan("lb", "g", BOXES, "http://localhost:8080", "round-robin", null, elsewhere, 50, 100, 50, 2).validated());

        RampPlan.Fault onBox = new RampPlan.Fault("http://localhost:8082", "slow", 3);
        RampPlan plan = new RampPlan("lb", "g", BOXES, "http://localhost:8080", "round-robin", null, onBox, 50, 100, 50, 2).validated();
        assertEquals("slow x3", plan.fault().describe());
    }

    @Test
    void aTwoStepPlanNamesItsSessionKindAndMayRestartABox() {
        RampPlan.Fault restart = new RampPlan.Fault("http://localhost:8082", "restart", 1);
        RampPlan plan = new RampPlan("co", "g", BOXES, "http://localhost:8080", "sticky", "Server", restart, 100, 100, 1, 10)
                .validated();
        assertEquals("server", plan.checkout());
        assertEquals(true, plan.twoStep());
        assertEquals(true, plan.fault().restart());
        assertThrows(IllegalArgumentException.class,
                () -> new RampPlan("co", "g", BOXES, null, null, "cookie", null, 100, 100, 1, 10).validated());
    }

    @Test
    void theBalancerStatusShapeIsParsedWhateverTheFieldOrder() {
        String json = "{\"node\":\"balancer\",\"strategy\":\"round-robin\",\"backends\":["
                + "{\"name\":\"box-small-1\",\"url\":\"http://localhost:8081\",\"inFlight\":0,\"sent\":120,\"failed\":0},"
                + "{\"failed\":80,\"sent\":80,\"name\":\"box-small-2\",\"inFlight\":1,\"url\":\"http://localhost:8082\"}]}";
        assertEquals(List.of(new BackendShare("box-small-1", 120, 0), new BackendShare("box-small-2", 80, 80)),
                BoxClient.parseShares(json));
    }
}
