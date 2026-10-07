package com.ticketflow.controller;

import com.ticketflow.model.Booking;
import com.ticketflow.service.InventoryService;
import com.ticketflow.service.InventoryService.SeatMapResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * TicketFlow booking API.
 *
 * Endpoints in this lesson:
 *   POST /bookings                    — fast path (~30ms)
 *   GET  /venues/{id}/seatmap         — slow path (~400ms, for algorithm comparison)
 *   GET  /admin/distribution          — per-instance booking count
 *   GET  /admin/duplicates            — seat double-booking check
 */
@RestController
public class BookingController {

    private final InventoryService inventory;

    public BookingController(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** Book one seat. Fast path — completes in ~30ms under normal load. */
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> book(@RequestBody BookingRequest req) {
        Booking b = inventory.bookSeat(req.venueId(), req.buyerEmail());
        return ResponseEntity.ok(new BookingResponse(b.getId(), b.getSeat().getSeatNumber(),
                b.getProcessedBy()));
    }

    /**
     * Seat map endpoint. Deliberately slow to simulate a large join (~400ms).
     * Used by the load generator's mixed-traffic mode to create request-duration
     * variance that makes the two balancing algorithms behave differently.
     */
    @GetMapping("/venues/{id}/seatmap")
    public ResponseEntity<SeatMapResponse> seatMap(@PathVariable Long id)
            throws InterruptedException {
        // 400ms sleep simulates a real seat-map DB join on a 500-seat venue.
        // Remove in production.
        Thread.sleep(400);
        return ResponseEntity.ok(inventory.buildSeatMap(id));
    }

    /** Admin: how many bookings each instance processed. */
    @GetMapping("/admin/distribution")
    public ResponseEntity<List<Map<String, Object>>> distribution() {
        List<Map<String, Object>> rows = inventory.distributionByInstance().stream()
                .map(r -> Map.of("instance", r[0], "count", r[1]))
                .toList();
        return ResponseEntity.ok(rows);
    }

    /** Admin: any seat sold more than once — must always return empty. */
    @GetMapping("/admin/duplicates")
    public ResponseEntity<List<Map<String, Object>>> duplicates() {
        List<Map<String, Object>> rows = inventory.duplicateBookings().stream()
                .map(r -> Map.of("seatId", r[0], "count", r[1]))
                .toList();
        return ResponseEntity.ok(rows);
    }

    public record BookingRequest(Long venueId, String buyerEmail) {}
    public record BookingResponse(Long bookingId, String seatNumber, String processedBy) {}
}
