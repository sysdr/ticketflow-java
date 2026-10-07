package com.ticketflow.domain;

/** A hall with a rectangular block of seats. */
public record Venue(String id, String name, int rows, int seatsPerRow) {

    public Venue {
        if (rows < 1 || seatsPerRow < 1) {
            throw new IllegalArgumentException("a venue needs at least one seat");
        }
    }

    public int capacity() {
        return rows * seatsPerRow;
    }
}
