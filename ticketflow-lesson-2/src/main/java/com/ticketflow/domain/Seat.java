package com.ticketflow.domain;

import com.ticketflow.domain.DomainException.Code;

/**
 * One physical seat. Its status only moves along three legal edges:
 * AVAILABLE -> HELD, HELD -> BOOKED, HELD -> AVAILABLE. Anything else throws,
 * so a bug elsewhere cannot silently put a seat into an impossible state.
 *
 * Deliberately NOT thread-safe: lesson 11 breaks this on purpose.
 */
public final class Seat {

    private final String id;
    private final String row;
    private final int number;
    private SeatStatus status = SeatStatus.AVAILABLE;
    private String holdId;

    public Seat(String row, int number) {
        this.row = row;
        this.number = number;
        this.id = row + number;
    }

    public String id() { return id; }
    public String row() { return row; }
    public int number() { return number; }
    public SeatStatus status() { return status; }

    /** The hold currently guarding this seat, or null unless status is HELD. */
    public String holdId() { return holdId; }

    public void markHeld(String newHoldId) {
        require(SeatStatus.AVAILABLE, "hold");
        this.status = SeatStatus.HELD;
        this.holdId = newHoldId;
    }

    public void markBooked() {
        require(SeatStatus.HELD, "book");
        this.status = SeatStatus.BOOKED;
        this.holdId = null;
    }

    public void markAvailable() {
        require(SeatStatus.HELD, "release");
        this.status = SeatStatus.AVAILABLE;
        this.holdId = null;
    }

    private void require(SeatStatus expected, String action) {
        if (status != expected) {
            throw new DomainException(Code.SEAT_UNAVAILABLE,
                    "Cannot " + action + " seat " + id + ": it is " + status + ", expected " + expected);
        }
    }
}
