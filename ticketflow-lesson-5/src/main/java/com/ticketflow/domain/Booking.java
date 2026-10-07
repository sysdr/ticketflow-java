package com.ticketflow.domain;

import java.time.Instant;

/**
 * A sold seat.
 *
 * @param node the box that sold it; with one box this is trivia, with two it is evidence
 */
public record Booking(String id, String venueId, String seatId, String buyerId,
                      String ticketCode, String node, Instant bookedAt) {
}
