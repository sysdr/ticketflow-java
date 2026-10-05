package dev.ticketflow.booking;

import java.util.OptionalInt;

import dev.ticketflow.inventory.SeatInventory;
import dev.ticketflow.metrics.ServerStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The deliberately naive Day 1 booking: do the slow part first (validate, charge,
 * write - modeled as a blocking wait of work-ms), then try to claim a seat.
 * Every buyer pays the full cost before learning whether a seat was left.
 */
@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger("BOOKING");

    public record Outcome(boolean sold, int seat) {}

    private final SeatInventory inventory;
    private final ServerStats stats;
    private final long workMs;

    public BookingService(SeatInventory inventory, ServerStats stats,
                          @Value("${ticketflow.booking.work-ms:25}") long workMs) {
        this.inventory = inventory;
        this.stats = stats;
        this.workMs = workMs;
    }

    /**
     * @param deadlineEpochMs when the buyer stops waiting (0 = unknown). Used only to
     *                        measure work done for buyers who have already left.
     */
    public Outcome book(String buyer, boolean traced, long deadlineEpochMs) {
        stats.begin();
        boolean finished = false;
        try {
            if (traced) {
                log.info("event=work_start workMs={}", workMs);
            }
            Thread.sleep(workMs);
            OptionalInt seat = inventory.claim(buyer);
            long lateByMs = deadlineEpochMs > 0 ? System.currentTimeMillis() - deadlineEpochMs : 0;
            boolean late = lateByMs > 0;
            if (traced) {
                log.info("event=work_done sold={} lateByMs={}", seat.isPresent(), Math.max(0, lateByMs));
            }
            stats.end(seat.isPresent(), late);
            finished = true;
            return new Outcome(seat.isPresent(), seat.orElse(0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while booking", e);
        } finally {
            if (!finished) {
                stats.abort();
            }
        }
    }
}
