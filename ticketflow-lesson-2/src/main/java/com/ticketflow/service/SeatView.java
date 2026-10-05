package com.ticketflow.service;

import com.ticketflow.domain.SeatStatus;

/** A read-only snapshot of one seat, safe to hand to the API layer. */
public record SeatView(
        String id,
        String row,
        int number,
        SeatStatus status,
        String holdId,
        String heldBy,
        Long holdExpiresAtMs,
        String bookingId) {
}
