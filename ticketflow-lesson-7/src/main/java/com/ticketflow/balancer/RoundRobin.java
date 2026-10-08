package com.ticketflow.balancer;

import java.util.List;

/** Take turns. Knows nothing about the boxes, which is both its strength and its weakness. */
public final class RoundRobin implements Strategy {

    public static final String NAME = "round-robin";

    private int next;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Backend pick(List<Backend> backends) {
        Backend chosen = backends.get(next % backends.size());
        next = (next + 1) % backends.size();
        return chosen;
    }
}
