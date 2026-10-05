package dev.ticketflow.domain;

import java.util.Objects;

/** A seat's stable identity, like "A-01". Never an array index: indexes change, identities travel. */
public record SeatId(String value) {
    public SeatId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
