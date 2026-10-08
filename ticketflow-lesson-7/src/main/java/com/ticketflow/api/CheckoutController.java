package com.ticketflow.api;

import com.ticketflow.checkout.CheckoutService;
import com.ticketflow.domain.Booking;
import com.ticketflow.domain.Venue;
import com.ticketflow.lifecycle.StageTimer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two-step checkout a real buyer goes through: hold a seat, then pay for it.
 * The session handed out by {@code start} must come back on {@code confirm} in
 * the {@code X-Checkout-Session} header.
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    public record StartRequest(String venueId, String seatId, String buyerId) {
    }

    private final CheckoutService checkout;
    private final Venue venue;

    public CheckoutController(CheckoutService checkout, Venue venue) {
        this.checkout = checkout;
        this.venue = venue;
    }

    @PostMapping("/start")
    public ResponseEntity<CheckoutService.Started> start(@RequestBody StartRequest body, HttpServletRequest request) {
        if (!venue.id().equals(body.venueId())) {
            throw new IllegalArgumentException("unknown venue: " + body.venueId());
        }
        StageTimer timer = (StageTimer) request.getAttribute(StageTimer.ATTRIBUTE);
        CheckoutService.Started started = checkout.start(body.seatId(), body.buyerId(), timer);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Server-Timing", timer.serverTiming())
                .body(started);
    }

    @PostMapping("/confirm")
    public ResponseEntity<Booking> confirm(@RequestHeader(value = "X-Checkout-Session", required = false) String session,
                                           HttpServletRequest request) {
        StageTimer timer = (StageTimer) request.getAttribute(StageTimer.ATTRIBUTE);
        Booking booking = checkout.confirm(session, timer);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Server-Timing", timer.serverTiming())
                .body(booking);
    }
}
