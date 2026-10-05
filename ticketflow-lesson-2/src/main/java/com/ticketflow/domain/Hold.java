package com.ticketflow.domain;

import com.ticketflow.domain.DomainException.Code;

import java.time.Instant;

/**
 * A temporary claim on one seat: a lease with a deadline. Exactly one of
 * three things ends it: the buyer confirms, the buyer lets go, or time runs out.
 *
 * ACTIVE -> CONFIRMED | RELEASED | EXPIRED, and nothing ever leaves those three.
 */
public final class Hold {

    public enum Status { ACTIVE, CONFIRMED, RELEASED, EXPIRED }

    private final String id;
    private final String seatId;
    private final String buyerId;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Status status = Status.ACTIVE;

    public Hold(String id, String seatId, String buyerId, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.seatId = seatId;
        this.buyerId = buyerId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String id() { return id; }
    public String seatId() { return seatId; }
    public String buyerId() { return buyerId; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Status status() { return status; }

    /** Time is a question asked at read time: has the deadline passed as of {@code now}? */
    public boolean isPastDeadline(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void confirm() { moveTo(Status.CONFIRMED); }
    public void release() { moveTo(Status.RELEASED); }
    public void expire() { moveTo(Status.EXPIRED); }

    private void moveTo(Status next) {
        if (status != Status.ACTIVE) {
            throw new DomainException(Code.HOLD_NOT_ACTIVE,
                    "Hold " + id + " is already " + status + "; cannot become " + next);
        }
        status = next;
    }
}
