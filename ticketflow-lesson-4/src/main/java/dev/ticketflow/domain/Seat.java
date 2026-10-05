package dev.ticketflow.domain;

/** A physical seat. Deliberately has no status field: availability is derived, see VenueService. */
public record Seat(SeatId id, String row, int number) {
    public static Seat of(String row, int number) {
        return new Seat(new SeatId("%s-%02d".formatted(row, number)), row, number);
    }
}
