package com.ticketflow.domain;

import java.time.Instant;

/**
 * A completed purchase. Immutable on purpose: a booking is a fact, so it has
 * no deadline and no status to change. Cancelling would be a new fact, not an edit.
 */
public record Booking(String id, String holdId, String seatId, String buyerId, Instant bookedAt) {
}
