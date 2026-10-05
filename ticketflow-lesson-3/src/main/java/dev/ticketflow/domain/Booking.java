package dev.ticketflow.domain;

import java.time.Instant;

/** A confirmed sale. It remembers which hold produced it, so one purchase can be traced end to end. */
public record Booking(String id, String holdId, SeatId seatId, String buyer, Instant confirmedAt) {}
