package com.ticketflow.inventory;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.Hold;
import com.ticketflow.domain.Seat;
import com.ticketflow.domain.SeatStatus;
import com.ticketflow.domain.SeatTakenException;
import com.ticketflow.domain.Venue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The seat map for one venue, held entirely in this process's memory.
 *
 * <p>It is safe for many threads inside one JVM. It knows nothing about any
 * other JVM, which is the fact lesson 5 makes visible.
 */
public final class SeatMap {

    public static final Duration HOLD_TTL = Duration.ofMinutes(2);

    private final Venue venue;
    private final Clock clock;
    private final Map<String, Seat> seats;
    private final ConcurrentHashMap<String, Hold> holds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Booking> bookings = new ConcurrentHashMap<>();
    private final AtomicLong holdIds = new AtomicLong();
    private final AtomicLong bookingIds = new AtomicLong();

    public SeatMap(Venue venue, Clock clock) {
        this.venue = venue;
        this.clock = clock;
        Map<String, Seat> all = new LinkedHashMap<>();
        for (int row = 1; row <= venue.rows(); row++) {
            for (int number = 1; number <= venue.seatsPerRow(); number++) {
                String id = Seat.idFor(row, number);
                all.put(id, new Seat(venue.id(), id, row, number));
            }
        }
        this.seats = Collections.unmodifiableMap(all);
    }

    public Venue venue() {
        return venue;
    }

    public List<String> seatIds() {
        return List.copyOf(seats.keySet());
    }

    public List<Seat> seats() {
        return List.copyOf(seats.values());
    }

    /** Claims a seat for a buyer, or throws if somebody on this box got there first. */
    public Hold hold(String seatId, String buyerId) {
        requireSeat(seatId);
        if (bookings.containsKey(seatId)) {
            throw new SeatTakenException(seatId);
        }
        Instant now = clock.instant();
        Hold fresh = new Hold("H-" + holdIds.incrementAndGet(), venue.id(), seatId, buyerId, now.plus(HOLD_TTL));
        Hold winner = holds.compute(seatId,
                (id, current) -> current == null || current.expiredAt(now) ? fresh : current);
        if (winner != fresh) {
            throw new SeatTakenException(seatId);
        }
        return fresh;
    }

    /** Turns a live hold into a booking. The hold must still be the one on record. */
    public Booking confirm(Hold hold, String ticketCode, String node) {
        Instant now = clock.instant();
        if (holds.get(hold.seatId()) != hold || hold.expiredAt(now)) {
            throw new SeatTakenException(hold.seatId());
        }
        Booking booking = new Booking("B-" + node + "-" + bookingIds.incrementAndGet(),
                venue.id(), hold.seatId(), hold.buyerId(), ticketCode, node, now);
        Booking existing = bookings.putIfAbsent(hold.seatId(), booking);
        holds.remove(hold.seatId(), hold);
        if (existing != null) {
            throw new SeatTakenException(hold.seatId());
        }
        return booking;
    }

    public SeatStatus statusOf(String seatId) {
        requireSeat(seatId);
        if (bookings.containsKey(seatId)) {
            return SeatStatus.BOOKED;
        }
        Hold hold = holds.get(seatId);
        return hold != null && !hold.expiredAt(clock.instant()) ? SeatStatus.HELD : SeatStatus.AVAILABLE;
    }

    /** Seat ids this box believes it has sold, in a stable order. */
    public List<String> soldSeatIds() {
        List<String> sold = new ArrayList<>(bookings.keySet());
        Collections.sort(sold);
        return sold;
    }

    public int bookedCount() {
        return bookings.size();
    }

    public int heldCount() {
        Instant now = clock.instant();
        int held = 0;
        for (Hold hold : holds.values()) {
            if (!hold.expiredAt(now)) {
                held++;
            }
        }
        return held;
    }

    /** Empties the hall. Used between experiments so every run starts with 500 free seats. */
    public void reset() {
        holds.clear();
        bookings.clear();
    }

    private void requireSeat(String seatId) {
        if (seatId == null || !seats.containsKey(seatId)) {
            throw new IllegalArgumentException("unknown seat: " + seatId);
        }
    }
}
