package dev.ticketflow.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An immutable hall layout: rows A, B, C... of numbered seats. */
public final class Venue {
    private final String id;
    private final String name;
    private final int rows;
    private final int seatsPerRow;
    private final List<Seat> seats;
    private final Map<SeatId, Integer> positions = new HashMap<>();

    private Venue(String id, String name, int rows, int seatsPerRow, List<Seat> seats) {
        this.id = id;
        this.name = name;
        this.rows = rows;
        this.seatsPerRow = seatsPerRow;
        this.seats = List.copyOf(seats);
        for (int i = 0; i < this.seats.size(); i++) {
            positions.put(this.seats.get(i).id(), i + 1);
        }
    }

    public static Venue grid(String id, String name, int rows, int seatsPerRow) {
        if (rows < 1 || rows > 26 || seatsPerRow < 1) {
            throw new IllegalArgumentException("rows must be 1..26 and seatsPerRow at least 1");
        }
        List<Seat> seats = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String row = String.valueOf((char) ('A' + r));
            for (int n = 1; n <= seatsPerRow; n++) {
                seats.add(Seat.of(row, n));
            }
        }
        return new Venue(id, name, rows, seatsPerRow, seats);
    }

    public Optional<Seat> seat(SeatId seatId) {
        Integer pos = positions.get(seatId);
        return pos == null ? Optional.empty() : Optional.of(seats.get(pos - 1));
    }

    /** 1-based position of the seat in venue order. */
    public int position(SeatId seatId) {
        return positions.getOrDefault(seatId, 0);
    }

    public List<Seat> seats() { return seats; }
    public int size() { return seats.size(); }
    public String id() { return id; }
    public String name() { return name; }
    public int rows() { return rows; }
    public int seatsPerRow() { return seatsPerRow; }
}
