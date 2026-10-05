package dev.ticketflow.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.ticketflow.inventory.SeatInventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What the server itself can see. Note what is missing: a request that has been
 * accepted by the network stack but is still waiting for a thread is invisible
 * here, because no application code has run for it yet.
 */
@Component
public class ServerStats {
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong sold = new AtomicLong();
    private final AtomicLong soldOut = new AtomicLong();
    private final AtomicLong ghost = new AtomicLong();
    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicInteger peakBusy = new AtomicInteger();

    private final SeatInventory inventory;
    private final int threads;
    private final long workMs;

    public ServerStats(SeatInventory inventory,
                       @Value("${server.tomcat.threads.max:50}") int threads,
                       @Value("${ticketflow.booking.work-ms:25}") long workMs) {
        this.inventory = inventory;
        this.threads = threads;
        this.workMs = workMs;
    }

    /** A thread picked the request up. */
    public void begin() {
        received.incrementAndGet();
        peakBusy.accumulateAndGet(busy.incrementAndGet(), Math::max);
    }

    /** The request finished its work; {@code late} means the buyer's deadline had already passed. */
    public void end(boolean wasSold, boolean late) {
        busy.decrementAndGet();
        completed.incrementAndGet();
        (wasSold ? sold : soldOut).incrementAndGet();
        if (late) {
            ghost.incrementAndGet();
        }
    }

    /** The thread was interrupted before finishing. */
    public void abort() {
        busy.decrementAndGet();
    }

    public long ghostCompleted() {
        return ghost.get();
    }

    public long soldCount() {
        return sold.get();
    }

    public void reset() {
        received.set(0);
        completed.set(0);
        sold.set(0);
        soldOut.set(0);
        ghost.set(0);
        peakBusy.set(busy.get());
    }

    public String toJson() {
        long capacityRps = workMs <= 0 ? 0 : threads * 1000L / workMs;
        return "{\"received\":" + received.get()
                + ",\"busy\":" + busy.get()
                + ",\"peakBusy\":" + peakBusy.get()
                + ",\"completed\":" + completed.get()
                + ",\"sold\":" + sold.get()
                + ",\"soldOut\":" + soldOut.get()
                + ",\"ghost\":" + ghost.get()
                + ",\"seatsSold\":" + inventory.sold()
                + ",\"seatsTotal\":" + inventory.total()
                + ",\"threads\":" + threads
                + ",\"workMs\":" + workMs
                + ",\"capacityRps\":" + capacityRps + "}";
    }
}
