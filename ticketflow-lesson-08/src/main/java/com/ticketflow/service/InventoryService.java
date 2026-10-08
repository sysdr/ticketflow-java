package com.ticketflow.service;

import com.ticketflow.model.Booking;
import com.ticketflow.model.Seat;
import com.ticketflow.model.Seat.Status;
import com.ticketflow.model.Venue;
import com.ticketflow.repository.BookingRepository;
import com.ticketflow.repository.SeatRepository;
import com.ticketflow.repository.VenueRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * InventoryService — Lesson 8.
 *
 * Key change from Lesson 6/7: the in-memory HashMap is gone.
 * Every read and write goes through JPA repositories, which means
 * every state change is durable — PostgreSQL WAL-backed.
 *
 * bookSeat() is @Transactional:
 *   1. Opens a JDBC connection from the HikariCP pool (sized in Lesson 9).
 *   2. Issues SELECT … FOR UPDATE via findAvailableForVenue().
 *   3. Marks the seat BOOKED and inserts a Booking row (JPA flush).
 *   4. PostgreSQL writes to WAL and confirms COMMIT.
 *   5. Returns the Booking — from this point it is durable.
 *
 * The intentional 400ms delay in buildSeatMap() is kept from Lesson 6 so
 * Lesson 9 can still demonstrate what happens when you starve the pool.
 */
@Service
public class InventoryService {

    private final SeatRepository    seatRepo;
    private final BookingRepository bookingRepo;
    private final VenueRepository   venueRepo;

    public InventoryService(SeatRepository seatRepo,
                            BookingRepository bookingRepo,
                            VenueRepository venueRepo) {
        this.seatRepo    = seatRepo;
        this.bookingRepo = bookingRepo;
        this.venueRepo   = venueRepo;
    }

    // ── booking ──────────────────────────────────────────────────────────────

    @Transactional
    public Booking bookSeat(Long venueId, String buyerEmail) {
        // findAvailableForVenue issues SELECT … FOR UPDATE.
        // If no AVAILABLE seat exists, throw immediately — no DB write.
        List<Seat> available = seatRepo.findAvailableForVenue(venueId);
        if (available.isEmpty()) {
            throw new IllegalStateException("No seats available for venue " + venueId);
        }

        Seat seat = available.get(0);
        seat.setStatus(Status.BOOKED);
        seatRepo.save(seat);

        String processedBy = MDC.get("instanceId") != null ? MDC.get("instanceId") : "unknown";
        Booking booking = new Booking(seat, buyerEmail, processedBy);
        return bookingRepo.save(booking);
        // @Transactional commits here → WAL flush → durable
    }

    // ── seat map ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SeatMapResponse buildSeatMap(Long venueId) {
        Venue venue = venueRepo.findById(venueId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown venue: " + venueId));

        long available = seatRepo.countByVenueIdAndStatus(venueId, Status.AVAILABLE);
        long booked    = seatRepo.countByVenueIdAndStatus(venueId, Status.BOOKED);

        // Intentional delay — retained from Lesson 6 so Lesson 9 can demonstrate
        // connection-pool starvation when many seatmap requests stack up.
        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        String servedBy = MDC.get("instanceId") != null ? MDC.get("instanceId") : "unknown";
        return new SeatMapResponse(venueId, venue.getName(), venue.getTotalSeats(),
                                   available, booked, servedBy);
    }

    // ── admin queries ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public long availableSeats(Long venueId) {
        return seatRepo.countByVenueIdAndStatus(venueId, Status.AVAILABLE);
    }

    @Transactional(readOnly = true)
    public List<Object[]> distributionByInstance() {
        return bookingRepo.countByInstance();
    }

    @Transactional(readOnly = true)
    public List<Long> duplicateBookings() {
        return bookingRepo.findDuplicateBookings();
    }

    // ── booking lookup (new in Day 8 — needed for restart verification) ───────

    @Transactional(readOnly = true)
    public Booking findBooking(Long bookingId) {
        return bookingRepo.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
    }

    // ── booking history (homework endpoint) ───────────────────────────────────

    @Transactional(readOnly = true)
    public List<Booking> bookingHistory(Long venueId) {
        return bookingRepo.findByVenueIdOrderByCreatedAtDesc(venueId);
    }

    // ── inner record ─────────────────────────────────────────────────────────

    public record SeatMapResponse(
            Long venueId,
            String venueName,
            int totalSeats,
            long available,
            long booked,
            String servedBy) {}
}
