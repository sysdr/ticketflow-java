package com.ticketflow.service;

import com.ticketflow.model.Booking;
import com.ticketflow.model.Seat;
import com.ticketflow.model.Seat.Status;
import com.ticketflow.model.Venue;
import com.ticketflow.repository.BookingRepository;
import com.ticketflow.repository.SeatRepository;
import com.ticketflow.repository.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Core booking logic for TicketFlow.
 *
 * Lesson-6 addition: processedBy is captured from MDC and stored on every
 * Booking row so post-run queries can show per-instance distribution.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final VenueRepository venues;

    public InventoryService(SeatRepository seats,
                            BookingRepository bookings,
                            VenueRepository venues) {
        this.seats = seats;
        this.bookings = bookings;
        this.venues = venues;
    }

    /** Book one available seat for the given buyer. Returns the new Booking. */
    @Transactional
    public Booking bookSeat(Long venueId, String buyerEmail) {
        Seat seat = seats.findFirstAvailable(venueId)
                .orElseThrow(() -> new IllegalStateException("No seats available"));

        seat.setStatus(Status.BOOKED);
        seats.save(seat);

        String instanceId = MDC.get("instanceId");
        if (instanceId == null) instanceId = "unknown";

        Booking booking = new Booking(seat, buyerEmail, instanceId);
        bookings.save(booking);

        log.info("Booked seat {} for {} [instance={}]",
                seat.getSeatNumber(), buyerEmail, instanceId);
        return booking;
    }

    /**
     * Build the full seat map for a venue.
     *
     * This is intentionally slow — it fetches every seat row individually
     * to simulate a real seat-map join that takes 400 ms under load.
     * The lesson homework uses this endpoint to make the algorithm difference
     * between round-robin and least-connections visible.
     */
    public SeatMapResponse buildSeatMap(Long venueId) {
        Venue venue = venues.findById(venueId)
                .orElseThrow(() -> new IllegalArgumentException("Venue not found: " + venueId));

        List<Seat> allSeats = seats.findByVenueIdOrderBySeatNumber(venueId);
        long available = allSeats.stream().filter(s -> s.getStatus() == Status.AVAILABLE).count();
        long booked = allSeats.stream().filter(s -> s.getStatus() == Status.BOOKED).count();

        String instanceId = MDC.get("instanceId");
        if (instanceId == null) instanceId = "unknown";

        return new SeatMapResponse(
                venue.getId(),
                venue.getName(),
                allSeats.size(),
                available,
                booked,
                instanceId
        );
    }

    /** Count available seats for a venue — used by the health check. */
    public long availableSeats(Long venueId) {
        return seats.countByVenueIdAndStatus(venueId, Status.AVAILABLE);
    }

    /** Per-instance booking distribution — used by the dashboard endpoint. */
    public List<Object[]> distributionByInstance() {
        return bookings.countByInstance();
    }

    /** Duplicate booking check — should always return an empty list. */
    public List<Object[]> duplicateBookings() {
        return bookings.findDuplicateBookings();
    }

    /** Simple response record for the seat-map endpoint. */
    public record SeatMapResponse(
            Long venueId,
            String venueName,
            int totalSeats,
            long available,
            long booked,
            String servedBy
    ) {}
}
