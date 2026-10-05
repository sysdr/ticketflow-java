package dev.ticketflow.domain;

import java.time.Instant;

/** A lease on one seat. The deadline is an absolute instant, so it means the same thing wherever it is read. */
public record Hold(String id, SeatId seatId, String buyer, Instant placedAt, Instant expiresAt, HoldState state) {

    public boolean liveAt(Instant now) {
        return state == HoldState.ACTIVE && now.isBefore(expiresAt);
    }

    public boolean expiredAt(Instant now) {
        return state == HoldState.ACTIVE && !now.isBefore(expiresAt);
    }

    public Hold with(HoldState next) {
        return new Hold(id, seatId, buyer, placedAt, expiresAt, next);
    }
}
