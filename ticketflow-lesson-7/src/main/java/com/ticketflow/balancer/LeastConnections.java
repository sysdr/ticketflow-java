package com.ticketflow.balancer;

import java.util.List;

/**
 * Send the request to the box with the fewest unanswered requests.
 *
 * <p>A slow box holds on to its requests, so its count climbs and it is passed
 * over. A box that answers instantly always has a low count, and this strategy
 * cannot tell whether those instant answers were bookings or errors.
 */
public final class LeastConnections implements Strategy {

    public static final String NAME = "least-connections";

    private int tieBreaker;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Backend pick(List<Backend> backends) {
        int size = backends.size();
        // Start the scan one place further along each time, so ties are shared out instead of always going to box 0.
        int start = tieBreaker++ % size;
        if (tieBreaker >= size) {
            tieBreaker = 0;
        }
        Backend best = backends.get(start);
        for (int offset = 1; offset < size; offset++) {
            Backend candidate = backends.get((start + offset) % size);
            if (candidate.inFlight() < best.inFlight()) {
                best = candidate;
            }
        }
        return best;
    }
}
