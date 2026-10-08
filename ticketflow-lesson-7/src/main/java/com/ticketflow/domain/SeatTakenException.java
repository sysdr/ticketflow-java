package com.ticketflow.domain;

/** Thrown when a buyer asks for a seat that is already held or booked on this box. */
public class SeatTakenException extends RuntimeException {

    private final String seatId;

    public SeatTakenException(String seatId) {
        super("seat " + seatId + " is already taken");
        this.seatId = seatId;
    }

    public String seatId() {
        return seatId;
    }
}
