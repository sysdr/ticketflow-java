package com.ticketflow.domain;

import java.time.Instant;

/** A short-lived claim on a seat while the buyer finishes checking out. */
public record Hold(String id, String venueId, String seatId, String buyerId, Instant expiresAt) {

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
