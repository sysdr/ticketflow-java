package com.ticketflow.service;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.DomainException;
import com.ticketflow.domain.DomainException.Code;
import com.ticketflow.domain.Hold;
import com.ticketflow.domain.Seat;
import com.ticketflow.domain.SeatStatus;
import com.ticketflow.domain.Venue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.ticketflow.service.EventSink.fields;

/**
 * The single-node, all-in-memory heart of TicketFlow: one venue, its holds, its bookings.
 *
 * Two design choices worth knowing before you read on:
 *
 * 1. Expiry is lazy. Nothing sweeps in the background. Every read and write first asks
 *    "is this hold past its deadline as of the clock right now?" and settles the answer
 *    on the spot. A hold that nobody touches costs nothing, and a test can move time
 *    forward by swapping the Clock instead of sleeping.
 *
 * 2. Check-then-act is NOT protected. placeHold() reads the seat status and then writes it
 *    with nothing in between. On one thread that is correct. Under concurrent requests it is
 *    the double-booking bug, and lesson 11 reproduces it on purpose.
 */
public class InventoryService {

    private final Venue venue;
    private final Clock clock;
    private final Duration holdTtl;
    private final EventSink sink;

    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();

    public InventoryService(Venue venue, Clock clock, Duration holdTtl, EventSink sink) {
        this.venue = venue;
        this.clock = clock;
        this.holdTtl = holdTtl;
        this.sink = sink;
    }

    // ---------------------------------------------------------------- commands

    public Hold placeHold(String seatId, String buyerId) {
        requireText(seatId, "seatId");
        requireText(buyerId, "buyerId");
        Seat seat = venue.seat(seatId);
        settleIfExpired(seat);

        if (seat.status() != SeatStatus.AVAILABLE) {
            throw reject("hold.rejected", new DomainException(Code.SEAT_UNAVAILABLE,
                    "Seat " + seatId + " is " + seat.status()), fields("seat", seatId, "buyer", buyerId));
        }

        Instant now = clock.instant();
        Hold hold = new Hold("h-" + shortId(), seatId, buyerId, now, now.plus(holdTtl));
        holds.put(hold.id(), hold);
        seat.markHeld(hold.id());
        sink.emit("inventory", "hold.created", fields(
                "hold", hold.id(), "seat", seatId, "buyer", buyerId, "ttlSeconds", holdTtl.toSeconds()));
        return hold;
    }

    public Booking confirm(String holdId) {
        Hold hold = findHold(holdId);
        Seat seat = venue.seat(hold.seatId());

        if (hold.status() == Hold.Status.ACTIVE && hold.isPastDeadline(clock.instant())) {
            settleIfExpired(seat);
        }
        if (hold.status() == Hold.Status.EXPIRED) {
            throw reject("confirm.rejected", new DomainException(Code.HOLD_EXPIRED,
                    "Hold " + holdId + " expired before it was confirmed"), fields("hold", holdId));
        }
        if (hold.status() != Hold.Status.ACTIVE) {
            throw reject("confirm.rejected", new DomainException(Code.HOLD_NOT_ACTIVE,
                    "Hold " + holdId + " is " + hold.status()), fields("hold", holdId));
        }

        Booking booking = new Booking("b-" + shortId(), hold.id(), hold.seatId(), hold.buyerId(), clock.instant());
        hold.confirm();
        seat.markBooked();
        bookings.put(booking.id(), booking);
        sink.emit("inventory", "booking.created", fields(
                "booking", booking.id(), "hold", hold.id(), "seat", booking.seatId(), "buyer", booking.buyerId()));
        return booking;
    }

    public Hold release(String holdId) {
        Hold hold = findHold(holdId);
        Seat seat = venue.seat(hold.seatId());
        settleIfExpired(seat);

        if (hold.status() == Hold.Status.EXPIRED) {
            throw reject("release.rejected", new DomainException(Code.HOLD_EXPIRED,
                    "Hold " + holdId + " already expired"), fields("hold", holdId));
        }
        if (hold.status() != Hold.Status.ACTIVE) {
            throw reject("release.rejected", new DomainException(Code.HOLD_NOT_ACTIVE,
                    "Hold " + holdId + " is " + hold.status()), fields("hold", holdId));
        }
        hold.release();
        seat.markAvailable();
        sink.emit("inventory", "hold.released", fields("hold", holdId, "seat", hold.seatId()));
        return hold;
    }

    // ----------------------------------------------------------------- queries

    /** Every seat as of right now. Reading settles any expired holds first. */
    public List<SeatView> seats() {
        List<SeatView> out = new ArrayList<>();
        for (Seat seat : venue.seats()) {
            settleIfExpired(seat);
            Hold hold = seat.holdId() == null ? null : holds.get(seat.holdId());
            String bookingId = seat.status() == SeatStatus.BOOKED ? bookingIdFor(seat.id()) : null;
            out.add(new SeatView(
                    seat.id(), seat.row(), seat.number(), seat.status(),
                    hold == null ? null : hold.id(),
                    hold == null ? null : hold.buyerId(),
                    hold == null ? null : hold.expiresAt().toEpochMilli(),
                    bookingId));
        }
        return out;
    }

    public List<Booking> bookings() {
        List<Booking> list = new ArrayList<>(bookings.values());
        list.sort(Comparator.comparing(Booking::bookedAt));
        return list;
    }

    public Hold hold(String holdId) {
        return findHold(holdId);
    }

    public String venueName() { return venue.name(); }

    public long holdTtlSeconds() { return holdTtl.toSeconds(); }

    public Instant now() { return clock.instant(); }

    /**
     * Cross-checks the three collections against each other. An empty list means the model
     * is self-consistent: every BOOKED seat has exactly one booking, every HELD seat has an
     * ACTIVE hold pointing back at it, and no seat has two bookings.
     */
    public List<String> invariantViolations() {
        List<String> problems = new ArrayList<>();
        for (Seat seat : venue.seats()) {
            long bookingCount = bookings.values().stream().filter(b -> b.seatId().equals(seat.id())).count();
            if (seat.status() == SeatStatus.BOOKED && bookingCount != 1) {
                problems.add("Seat " + seat.id() + " is BOOKED but has " + bookingCount + " bookings");
            }
            if (seat.status() != SeatStatus.BOOKED && bookingCount > 0) {
                problems.add("Seat " + seat.id() + " is " + seat.status() + " but has " + bookingCount + " bookings");
            }
            if (seat.status() == SeatStatus.HELD) {
                Hold h = seat.holdId() == null ? null : holds.get(seat.holdId());
                if (h == null || h.status() != Hold.Status.ACTIVE || !h.seatId().equals(seat.id())) {
                    problems.add("Seat " + seat.id() + " is HELD without a matching ACTIVE hold");
                }
            }
        }
        return problems;
    }

    // ---------------------------------------------------------------- internals

    /** If this seat's hold has run out of time, end the hold and free the seat. */
    private void settleIfExpired(Seat seat) {
        if (seat.status() != SeatStatus.HELD) {
            return;
        }
        Hold hold = holds.get(seat.holdId());
        if (hold != null && hold.status() == Hold.Status.ACTIVE && hold.isPastDeadline(clock.instant())) {
            hold.expire();
            seat.markAvailable();
            sink.emit("inventory", "hold.expired", fields(
                    "hold", hold.id(), "seat", seat.id(), "buyer", hold.buyerId()));
        }
    }

    private Hold findHold(String holdId) {
        Hold hold = holds.get(holdId);
        if (hold == null) {
            throw new DomainException(Code.NOT_FOUND, "No hold " + holdId);
        }
        return hold;
    }

    private String bookingIdFor(String seatId) {
        return bookings.values().stream()
                .filter(b -> b.seatId().equals(seatId))
                .map(Booking::id)
                .findFirst()
                .orElse(null);
    }

    private DomainException reject(String event, DomainException ex, Map<String, Object> f) {
        f.put("code", ex.code().name());
        sink.emit("inventory", event, f);
        return ex;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DomainException(Code.INVALID_REQUEST, name + " is required");
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
