package com.ticketflow.balancer;

import java.util.List;

/**
 * Sticky sessions: once a client has been sent to a box, keep sending it there.
 *
 * <p>The balancer stamps every answer with an {@code X-Route} header naming the
 * box. A client that sends that header back is routed to the same box; a client
 * without one gets round-robin. Browsers do the same thing with a cookie the
 * balancer sets, which is how NGINX, HAProxy and cloud balancers implement it.
 *
 * <p>It makes server-side sessions appear to work. It does not make them survive
 * that box going away, and it ignores how busy the box is.
 */
public final class Sticky implements Strategy {

    public static final String NAME = "sticky";

    private final RoundRobin firstVisit = new RoundRobin();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Backend pick(List<Backend> backends) {
        return firstVisit.pick(backends);
    }

    @Override
    public Backend pick(List<Backend> backends, String routeHint) {
        if (routeHint != null) {
            for (Backend backend : backends) {
                if (backend.name().equals(routeHint)) {
                    return backend;
                }
            }
        }
        return firstVisit.pick(backends);
    }
}
