package com.ticketflow.domain;

/** One physical seat. Its id is stable and readable, for example {@code R07-12}. */
public record Seat(String venueId, String id, int row, int number) {

    public static String idFor(int row, int number) {
        return String.format("R%02d-%02d", row, number);
    }
}
