package com.ticketflow.api;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.Venue;
import com.ticketflow.inventory.BookingService;
import com.ticketflow.lifecycle.StageTimer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The one endpoint ten thousand people want at the same second. */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    public record BookingRequest(String venueId, String seatId, String buyerId) {
    }

    private final BookingService bookings;
    private final Venue venue;

    public BookingController(BookingService bookings, Venue venue) {
        this.bookings = bookings;
        this.venue = venue;
    }

    @PostMapping
    public ResponseEntity<Booking> book(@RequestBody BookingRequest body, HttpServletRequest request) {
        if (!venue.id().equals(body.venueId())) {
            throw new IllegalArgumentException("unknown venue: " + body.venueId());
        }
        StageTimer timer = (StageTimer) request.getAttribute(StageTimer.ATTRIBUTE);
        Booking booking = bookings.book(body.seatId(), body.buyerId(), timer);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Server-Timing", timer.serverTiming())
                .body(booking);
    }
}
