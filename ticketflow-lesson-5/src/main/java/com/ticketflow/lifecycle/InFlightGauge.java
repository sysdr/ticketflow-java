package com.ticketflow.lifecycle;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Counts booking requests that have arrived at this box and not yet been answered. */
public final class InFlightGauge {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong answered = new AtomicLong();
    private final AtomicLong overBudget = new AtomicLong();

    public void arrived() {
        inFlight.incrementAndGet();
    }

    public void answered(boolean withinBudget) {
        inFlight.decrementAndGet();
        answered.incrementAndGet();
        if (!withinBudget) {
            overBudget.incrementAndGet();
        }
    }

    public int inFlight() {
        return inFlight.get();
    }

    public long answered() {
        return answered.get();
    }

    public long overBudget() {
        return overBudget.get();
    }

    public void reset() {
        answered.set(0);
        overBudget.set(0);
    }
}
