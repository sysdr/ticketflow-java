package com.ticketflow.controller;

import com.ticketflow.model.Booking;
import com.ticketflow.service.InventoryService;
import com.ticketflow.service.InventoryService.SeatMapResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * BookingController — Lesson 8.
 *
 * New endpoints vs Lesson 6/7:
 *   GET /bookings/{id}              — look up a booking by ID (restart verification)
 *   GET /venues/{venueId}/bookings  — booking history (homework endpoint)
 */
@RestController
public class BookingController {

    private final InventoryService inventory;

    public BookingController(InventoryService inventory) {
        this.inventory = inventory;
    }

    // ── booking ───────────────────────────────────────────────────────────────

    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> bookSeat(@RequestBody BookingRequest req) {
        try {
            Booking b = inventory.bookSeat(req.venueId(), req.buyerEmail());
            return ResponseEntity.ok(new BookingResponse(
                    b.getId(), b.getSeat().getSeatNumber(), b.getProcessedBy()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();  // sold out
        }
    }

    /** Lookup by ID — used by the restart-verification step in test.sh */
    @GetMapping("/bookings/{id}")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable Long id) {
        try {
            Booking b = inventory.findBooking(id);
            return ResponseEntity.ok(new BookingResponse(
                    b.getId(), b.getSeat().getSeatNumber(), b.getProcessedBy()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── seat map ──────────────────────────────────────────────────────────────

    @GetMapping("/venues/{venueId}/seatmap")
    public ResponseEntity<SeatMapResponse> seatMap(@PathVariable Long venueId) {
        try {
            return ResponseEntity.ok(inventory.buildSeatMap(venueId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── admin ─────────────────────────────────────────────────────────────────

    @GetMapping("/admin/distribution")
    public List<Object[]> distribution() {
        return inventory.distributionByInstance();
    }

    @GetMapping("/admin/duplicates")
    public List<Long> duplicates() {
        return inventory.duplicateBookings();
    }

    // ── booking history (homework endpoint) ───────────────────────────────────

    @GetMapping("/venues/{venueId}/bookings")
    public List<BookingHistoryResponse> bookingHistory(@PathVariable Long venueId) {
        return inventory.bookingHistory(venueId).stream()
                .map(b -> new BookingHistoryResponse(
                        b.getId(),
                        b.getSeat().getSeatNumber(),
                        b.getBuyerEmail(),
                        b.getProcessedBy(),
                        b.getCreatedAt().toString()))
                .toList();
    }

    // ── records ───────────────────────────────────────────────────────────────

    public record BookingRequest(Long venueId, String buyerEmail) {}

    public record BookingResponse(Long bookingId, String seatNumber, String processedBy) {}

    public record BookingHistoryResponse(
            Long bookingId,
            String seatNumber,
            String buyerEmail,
            String processedBy,
            String createdAt) {}
}
