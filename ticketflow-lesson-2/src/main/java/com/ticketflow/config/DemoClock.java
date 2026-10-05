package com.ticketflow.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The system clock plus a skew you can push forward. This is what lets the dashboard's
 * "skip ahead" button make a two-minute hold expire in one click instead of two minutes.
 * Time only moves forward; there is no way to rewind.
 */
public class DemoClock extends Clock {

    private final AtomicLong skewSeconds = new AtomicLong();
    private final ZoneId zone;

    public DemoClock() {
        this(ZoneId.of("UTC"));
    }

    private DemoClock(ZoneId zone) {
        this.zone = zone;
    }

    public void advance(long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("time only moves forward");
        }
        skewSeconds.addAndGet(seconds);
    }

    public long skewSeconds() {
        return skewSeconds.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        DemoClock copy = new DemoClock(newZone);
        copy.skewSeconds.set(skewSeconds.get());
        return copy;
    }

    @Override
    public Instant instant() {
        return Instant.now().plusSeconds(skewSeconds.get());
    }
}
