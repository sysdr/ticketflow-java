package dev.ticketflow.inventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import dev.ticketflow.domain.Booking;
import dev.ticketflow.domain.DomainEvent;
import dev.ticketflow.domain.DomainException;
import dev.ticketflow.domain.DomainException.Code;
import dev.ticketflow.domain.Hold;
import dev.ticketflow.domain.HoldState;
import dev.ticketflow.domain.Seat;
import dev.ticketflow.domain.SeatId;
import dev.ticketflow.domain.SeatStatus;
import dev.ticketflow.domain.Venue;
import dev.ticketflow.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TicketFlow's in-memory box office for one venue.
 *
 * <p>Three facts are stored: holds, bookings, and the clock. A seat's status is never stored,
 * only derived from those three on every read. That gives the model one source of truth, and it
 * means a hold stops blocking its seat the moment its deadline passes, whether or not the
 * sweeper has run.
 *
 * <p>One lock guards all of it. That is correct on a single machine and cheap to reason about.
 */
@Service
public class VenueService {
    private static final Logger log = LoggerFactory.getLogger("VENUE");

    public record Snapshot(long nowMs, Venue venue, String map, int available, int held, int booked,
                           List<Hold> liveHolds, List<Booking> recentBookings) {}

    private final Venue venue;
    private final Clock clock;
    private final Duration holdTtl;
    private final EventLog events;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Hold> holds = new LinkedHashMap<>();
    private final Map<SeatId, String> holdOnSeat = new HashMap<>();   // ACTIVE holds only
    private final Map<SeatId, Booking> bookingOnSeat = new HashMap<>();
    private final List<Booking> bookings = new ArrayList<>();
    private int holdSeq;
    private int bookingSeq;

    public VenueService(Clock clock, EventLog events,
                        @Value("${ticketflow.hold.ttl-seconds:30}") long ttlSeconds,
                        @Value("${ticketflow.venue.rows:20}") int rows,
                        @Value("${ticketflow.venue.seats-per-row:25}") int seatsPerRow) {
        this.clock = clock;
        this.events = events;
        this.holdTtl = Duration.ofSeconds(ttlSeconds);
        this.venue = Venue.grid("riverside", "Riverside Hall", rows, seatsPerRow);
    }

    // ---- commands -----------------------------------------------------------------------

    public Hold placeHold(SeatId seatId, String buyer) {
        return locked(() -> {
            if (buyer == null || buyer.isBlank()) {
                throw new DomainException(Code.BAD_REQUEST, "buyer is required");
            }
            if (venue.seat(seatId).isEmpty()) {
                throw new DomainException(Code.UNKNOWN_SEAT, "no such seat: " + seatId);
            }
            Instant now = clock.instant();
            expireStaleHoldOn(seatId, now);
            SeatStatus status = statusAt(seatId, now);
            if (status != SeatStatus.AVAILABLE) {
                throw new DomainException(Code.SEAT_UNAVAILABLE, "seat " + seatId + " is " + status);
            }
            String id = "h-%04d".formatted(++holdSeq);
            Hold hold = new Hold(id, seatId, buyer.trim(), now, now.plus(holdTtl), HoldState.ACTIVE);
            holds.put(id, hold);
            holdOnSeat.put(seatId, id);
            events.add(new DomainEvent(now, "HOLD_PLACED", id, seatId.value(), hold.buyer()));
            log.info("event=hold_placed hold={} seat={} buyer={} expiresAt={}", id, seatId, hold.buyer(), hold.expiresAt());
            return hold;
        });
    }

    public Booking confirm(String holdId) {
        return locked(() -> {
            Hold hold = holds.get(holdId);
            if (hold == null) {
                throw new DomainException(Code.UNKNOWN_HOLD, "no such hold: " + holdId);
            }
            Instant now = clock.instant();
            if (hold.expiredAt(now)) {
                expire(hold, now);
                log.info("event=hold_expired hold={} seat={} via=lazy", holdId, hold.seatId());
                throw new DomainException(Code.HOLD_EXPIRED, "hold " + holdId + " expired at " + hold.expiresAt());
            }
            if (hold.state() != HoldState.ACTIVE) {
                throw new DomainException(Code.HOLD_NOT_ACTIVE, "hold " + holdId + " is " + hold.state());
            }
            Booking booking = new Booking("b-%04d".formatted(++bookingSeq), holdId, hold.seatId(), hold.buyer(), now);
            holds.put(holdId, hold.with(HoldState.CONFIRMED));
            holdOnSeat.remove(hold.seatId());
            bookingOnSeat.put(hold.seatId(), booking);
            bookings.add(booking);
            events.add(new DomainEvent(now, "BOOKING_CONFIRMED", holdId, hold.seatId().value(), hold.buyer()));
            log.info("event=booking_confirmed booking={} hold={} seat={} buyer={}",
                    booking.id(), holdId, hold.seatId(), hold.buyer());
            return booking;
        });
    }

    public Hold release(String holdId) {
        return locked(() -> {
            Hold hold = holds.get(holdId);
            if (hold == null) {
                throw new DomainException(Code.UNKNOWN_HOLD, "no such hold: " + holdId);
            }
            Instant now = clock.instant();
            if (hold.expiredAt(now)) {
                expire(hold, now);
                log.info("event=hold_expired hold={} seat={} via=lazy", holdId, hold.seatId());
                throw new DomainException(Code.HOLD_EXPIRED, "hold " + holdId + " expired at " + hold.expiresAt());
            }
            if (hold.state() != HoldState.ACTIVE) {
                throw new DomainException(Code.HOLD_NOT_ACTIVE, "hold " + holdId + " is " + hold.state());
            }
            Hold released = hold.with(HoldState.RELEASED);
            holds.put(holdId, released);
            holdOnSeat.remove(hold.seatId());
            events.add(new DomainEvent(now, "HOLD_RELEASED", holdId, hold.seatId().value(), hold.buyer()));
            log.info("event=hold_released hold={} seat={} buyer={}", holdId, hold.seatId(), hold.buyer());
            return released;
        });
    }

    /** Tidies up holds whose deadline passed. Correctness never depends on this running. */
    public List<Hold> sweepExpired() {
        return locked(() -> {
            Instant now = clock.instant();
            List<Hold> expired = new ArrayList<>();
            for (String id : new ArrayList<>(holdOnSeat.values())) {
                Hold hold = holds.get(id);
                if (hold.expiredAt(now)) {
                    expired.add(expire(hold, now));
                }
            }
            return expired;
        });
    }

    /** Releases every live hold whose buyer name starts with the prefix; returns how many. */
    public int releaseHoldsOf(String buyerPrefix) {
        return locked(() -> {
            Instant now = clock.instant();
            int released = 0;
            for (String id : new ArrayList<>(holdOnSeat.values())) {
                Hold hold = holds.get(id);
                if (hold.liveAt(now) && hold.buyer().startsWith(buyerPrefix)) {
                    release(id);
                    released++;
                }
            }
            return released;
        });
    }

    /** The live hold on a seat right now, if any. This is the server's own ground truth. */
    public Optional<Hold> liveHoldOn(SeatId seatId) {
        return locked(() -> {
            String id = holdOnSeat.get(seatId);
            if (id == null) {
                return Optional.empty();
            }
            Hold hold = holds.get(id);
            return hold.liveAt(clock.instant()) ? Optional.of(hold) : Optional.empty();
        });
    }

    /** Day 1's stampede path: hold the first free seat and confirm it in one step. */
    public Optional<Booking> bookFirstAvailable(String buyer) {
        return locked(() -> {
            if (bookingOnSeat.size() >= venue.size()) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            for (Seat seat : venue.seats()) {
                if (statusAt(seat.id(), now) == SeatStatus.AVAILABLE) {
                    Hold hold = placeHold(seat.id(), buyer);
                    return Optional.of(confirm(hold.id()));
                }
            }
            return Optional.empty();
        });
    }

    public void reset() {
        locked(() -> {
            holds.clear();
            holdOnSeat.clear();
            bookingOnSeat.clear();
            bookings.clear();
            holdSeq = 0;
            bookingSeq = 0;
            events.clear();
            return null;
        });
    }

    // ---- queries ------------------------------------------------------------------------

    public Snapshot snapshot() {
        return locked(() -> {
            Instant now = clock.instant();
            StringBuilder map = new StringBuilder(venue.size());
            int available = 0, held = 0, booked = 0;
            for (Seat seat : venue.seats()) {
                switch (statusAt(seat.id(), now)) {
                    case BOOKED -> { map.append('B'); booked++; }
                    case HELD -> { map.append('H'); held++; }
                    case AVAILABLE -> { map.append('A'); available++; }
                }
            }
            List<Hold> live = holds.values().stream()
                    .filter(h -> h.liveAt(now))
                    .sorted(Comparator.comparing(Hold::placedAt))
                    .toList();
            List<Booking> recent = bookings.subList(Math.max(0, bookings.size() - 20), bookings.size());
            return new Snapshot(now.toEpochMilli(), venue, map.toString(), available, held, booked,
                    live, List.copyOf(recent));
        });
    }

    public int bookedCount() {
        return locked(() -> {
            return bookingOnSeat.size();
        });
    }

    public Venue venue() { return venue; }

    public long holdTtlSeconds() { return holdTtl.toSeconds(); }


    /**
     * Takes the venue lock and reports how long the caller waited for it ("lock") and how long it
     * then spent inside ("domain") to the current request trace, if there is one. Nested calls,
     * such as bookFirstAvailable calling placeHold, are counted once at the outermost level.
     */
    private <T> T locked(Supplier<T> body) {
        boolean outermost = !lock.isHeldByCurrentThread();
        long requested = System.nanoTime();
        lock.lock();
        long acquired = System.nanoTime();
        try {
            return body.get();
        } finally {
            lock.unlock();
            if (outermost) {
                TraceContext.add("lock", acquired - requested);
                TraceContext.add("domain", System.nanoTime() - acquired);
            }
        }
    }

    // ---- internals (lock held) ----------------------------------------------------------

    /** The whole idea in four lines: a seat is what the facts say it is right now. */
    private SeatStatus statusAt(SeatId seatId, Instant now) {
        if (bookingOnSeat.containsKey(seatId)) {
            return SeatStatus.BOOKED;
        }
        String holdId = holdOnSeat.get(seatId);
        if (holdId != null && holds.get(holdId).liveAt(now)) {
            return SeatStatus.HELD;
        }
        return SeatStatus.AVAILABLE;
    }

    private void expireStaleHoldOn(SeatId seatId, Instant now) {
        String holdId = holdOnSeat.get(seatId);
        if (holdId != null) {
            Hold hold = holds.get(holdId);
            if (hold.expiredAt(now)) {
                expire(hold, now);
                log.info("event=hold_expired hold={} seat={} via=lazy", holdId, seatId);
            }
        }
    }

    private Hold expire(Hold hold, Instant now) {
        Hold expired = hold.with(HoldState.EXPIRED);
        holds.put(hold.id(), expired);
        holdOnSeat.remove(hold.seatId());
        events.add(new DomainEvent(now, "HOLD_EXPIRED", hold.id(), hold.seatId().value(), hold.buyer()));
        return expired;
    }
}
