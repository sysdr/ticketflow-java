package dev.ticketflow.inventory;

import java.util.Optional;
import java.util.OptionalInt;

import dev.ticketflow.domain.Booking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Day 1's seat counter, now a thin adapter over the real model. The stampede still asks for
 * "any seat"; the answer now comes from holds and bookings on actual Seat objects.
 */
@Component
public class SeatInventory {
    private static final Logger log = LoggerFactory.getLogger("INVENTORY");

    private final VenueService venue;

    public SeatInventory(VenueService venue) {
        this.venue = venue;
    }

    /** Books the first free seat; the result is its 1-based position in the venue. */
    public OptionalInt claim(String buyer) {
        Optional<Booking> booking = venue.bookFirstAvailable(buyer);
        if (booking.isEmpty()) {
            return OptionalInt.empty();
        }
        Booking b = booking.get();
        int seat = venue.venue().position(b.seatId());
        log.info("event=seat_sold seat={} buyer={} booking={} hold={}", seat, buyer, b.id(), b.holdId());
        return OptionalInt.of(seat);
    }

    public int sold() {
        return venue.bookedCount();
    }

    public int total() {
        return venue.venue().size();
    }

    public void reset() {
        venue.reset();
    }
}
