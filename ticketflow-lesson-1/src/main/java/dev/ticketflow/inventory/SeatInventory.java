package dev.ticketflow.inventory;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Day 1 seat map: just a counter of seats handed out, claimed atomically.
 * Real Venue/Seat objects arrive on Day 2. The claim is correct on purpose,
 * so today's failure is about capacity, not about duplicate seats.
 */
@Component
public class SeatInventory {
    private static final Logger log = LoggerFactory.getLogger("INVENTORY");

    private final int total;
    private final AtomicInteger next = new AtomicInteger();

    public SeatInventory(@Value("${ticketflow.seats.total:500}") int total) {
        this.total = total;
    }

    /** Hands out seat numbers 1..total, each exactly once. */
    public OptionalInt claim(String buyer) {
        for (;;) {
            int taken = next.get();
            if (taken >= total) {
                return OptionalInt.empty();
            }
            if (next.compareAndSet(taken, taken + 1)) {
                int seat = taken + 1;
                log.info("event=seat_sold seat={} buyer={}", seat, buyer);
                return OptionalInt.of(seat);
            }
        }
    }

    public int sold() {
        return next.get();
    }

    public int total() {
        return total;
    }

    public void reset() {
        next.set(0);
    }
}
