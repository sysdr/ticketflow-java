package com.ticketflow.checkout;

import java.time.Instant;

/**
 * What TicketFlow needs to remember between "hold this seat for me" and
 * "here is my payment, book it": who the buyer is, which seat, until when,
 * and which box handed it out.
 */
public record CheckoutSession(String venueId, String seatId, String buyerId, Instant expiresAt, String issuedBy) {

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
