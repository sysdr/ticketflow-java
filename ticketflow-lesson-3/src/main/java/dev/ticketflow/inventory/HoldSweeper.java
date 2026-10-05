package dev.ticketflow.inventory;

import java.util.List;

import dev.ticketflow.domain.Hold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Housekeeping only. Seats free themselves at the deadline; this just retires the paperwork. */
@Component
public class HoldSweeper {
    private static final Logger log = LoggerFactory.getLogger("SWEEPER");

    private final VenueService venue;

    public HoldSweeper(VenueService venue) {
        this.venue = venue;
    }

    @Scheduled(fixedDelay = 1000)
    public void sweep() {
        List<Hold> expired = venue.sweepExpired();
        for (Hold hold : expired) {
            log.info("event=hold_expired hold={} seat={} buyer={} via=sweeper", hold.id(), hold.seatId(), hold.buyer());
        }
    }
}
