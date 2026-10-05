package com.ticketflow.domain;

/** The three states a physical seat can be in. HELD is a lease, BOOKED is a fact. */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    BOOKED
}
