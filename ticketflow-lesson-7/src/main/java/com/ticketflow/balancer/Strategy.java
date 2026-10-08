package com.ticketflow.balancer;

import java.util.List;

/** The one decision a load balancer makes: which box gets this request? */
public interface Strategy {

    String name();

    /** Called under the balancer's lock, so implementations may keep simple state. */
    Backend pick(List<Backend> backends);

    /**
     * Like {@link #pick(List)}, but given the box the client says it was routed to last time
     * (the {@code X-Route} header). Only {@link Sticky} uses it; everything else ignores it.
     */
    default Backend pick(List<Backend> backends, String routeHint) {
        return pick(backends);
    }

    static Strategy named(String name) {
        String normal = name == null ? "" : name.trim().toLowerCase();
        return switch (normal) {
            case RoundRobin.NAME -> new RoundRobin();
            case LeastConnections.NAME -> new LeastConnections();
            case Sticky.NAME -> new Sticky();
            default -> throw new IllegalArgumentException("unknown strategy: " + name + " (use " + RoundRobin.NAME
                    + ", " + LeastConnections.NAME + " or " + Sticky.NAME + ")");
        };
    }
}
