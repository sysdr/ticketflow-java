package com.ticketflow.domain;

import com.ticketflow.domain.DomainException.Code;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A venue and its fixed seat map. Seats are created once and never added or removed. */
public final class Venue {

    private final String id;
    private final String name;
    private final Map<String, Seat> seats = new LinkedHashMap<>();

    public Venue(String id, String name, List<Seat> seatList) {
        this.id = id;
        this.name = name;
        for (Seat s : seatList) {
            seats.put(s.id(), s);
        }
    }

    /** Builds a rectangular hall: rows A, B, C... each with {@code perRow} seats. */
    public static Venue grid(String id, String name, int rows, int perRow) {
        List<Seat> list = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String rowLabel = String.valueOf((char) ('A' + r));
            for (int n = 1; n <= perRow; n++) {
                list.add(new Seat(rowLabel, n));
            }
        }
        return new Venue(id, name, list);
    }

    public String id() { return id; }
    public String name() { return name; }

    public List<Seat> seats() {
        return Collections.unmodifiableList(new ArrayList<>(seats.values()));
    }

    public Seat seat(String seatId) {
        Seat s = seats.get(seatId);
        if (s == null) {
            throw new DomainException(Code.NOT_FOUND, "No seat " + seatId + " in " + name);
        }
        return s;
    }
}
