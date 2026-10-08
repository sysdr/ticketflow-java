package com.ticketflow.checkout;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.Hold;
import com.ticketflow.faults.BoxBrokenException;
import com.ticketflow.faults.FaultSwitch;
import com.ticketflow.inventory.SeatMap;
import com.ticketflow.lifecycle.StageTimer;
import com.ticketflow.scaling.CpuPool;
import com.ticketflow.scaling.TicketSigner;
import java.time.Clock;
import java.time.Instant;

/**
 * The two-step checkout: {@link #start} holds a seat and hands the buyer a
 * session; {@link #confirm} turns that session into a booking.
 *
 * <p>Which kind of session is handed out can be switched while the box runs,
 * so the lesson can compare them under the same traffic.
 */
public final class CheckoutService {

    /** What {@link #start} hands back to the buyer. */
    public record Started(String session, String sessionKind, String seatId, Instant expiresAt, String node) {
    }

    private final SeatMap seatMap;
    private final TicketSigner signer;
    private final CpuPool cpuPool;
    private final FaultSwitch faults;
    private final String node;
    private final Clock clock;
    private final ServerSessionStore serverSessions;
    private final SignedSessionTokens signedTokens;
    private volatile CheckoutSessions current;

    public CheckoutService(SeatMap seatMap, TicketSigner signer, CpuPool cpuPool, FaultSwitch faults, String node,
                           Clock clock, ServerSessionStore serverSessions, SignedSessionTokens signedTokens,
                           String initialKind) {
        this.seatMap = seatMap;
        this.signer = signer;
        this.cpuPool = cpuPool;
        this.faults = faults;
        this.node = node;
        this.clock = clock;
        this.serverSessions = serverSessions;
        this.signedTokens = signedTokens;
        use(initialKind);
    }

    /** Switches the kind of session new checkouts receive: {@code server} or {@code signed}. */
    public void use(String kind) {
        String normal = kind == null ? "" : kind.trim().toLowerCase();
        current = switch (normal) {
            case ServerSessionStore.KIND -> serverSessions;
            case SignedSessionTokens.KIND -> signedTokens;
            default -> throw new IllegalArgumentException("unknown session kind: " + kind + " (use server or signed)");
        };
    }

    public String sessionKind() {
        return current.kind();
    }

    public int liveSessionsInMemory() {
        return serverSessions.liveInMemory();
    }

    /** What a process restart does: everything held only in memory is gone. */
    public void forgetEverythingInMemory() {
        serverSessions.forgetAll();
        signedTokens.forgetAll();
        seatMap.reset();
    }

    /** Step 1: hold the seat on this box and give the buyer something to come back with. */
    public Started start(String seatId, String buyerId, StageTimer timer) {
        if (buyerId == null || buyerId.isBlank()) {
            throw new IllegalArgumentException("buyerId is required");
        }
        if (faults.failFast()) {
            throw new BoxBrokenException(node);
        }
        long started = System.nanoTime();
        try {
            Hold hold = seatMap.hold(seatId, buyerId);
            CheckoutSession session = new CheckoutSession(seatMap.venue().id(), seatId, buyerId, hold.expiresAt(), node);
            CheckoutSessions sessions = current;
            return new Started(sessions.issue(session), sessions.kind(), seatId, hold.expiresAt(), node);
        } finally {
            timer.record("hold", System.nanoTime() - started);
        }
    }

    /**
     * Step 2: whatever box this lands on, turn the session into a booking.
     *
     * <p>The session is checked with the kind of store it claims to be: a token
     * contains a '.', a server key never does. That way a box switched to signed
     * mode can still tell a buyer clearly that an old server session is unknown.
     */
    public Booking confirm(String handle, StageTimer timer) {
        if (faults.failFast()) {
            throw new BoxBrokenException(node);
        }
        CheckoutSessions sessions = handle != null && handle.contains(".") ? signedTokens : serverSessions;
        CheckoutSession session = sessions.resolve(handle);
        int repeats = faults.signMultiplier();
        String ticketCode = cpuPool.run(() -> {
            String code = signer.sign(session.venueId(), session.seatId(), session.buyerId());
            for (int extra = 1; extra < repeats; extra++) {
                code = signer.sign(session.venueId(), session.seatId(), session.buyerId());
            }
            return code;
        }, timer);

        long started = System.nanoTime();
        try {
            Hold hold = seatMap.holdFor(session.seatId());
            boolean ours = hold != null && hold.buyerId().equals(session.buyerId()) && !hold.expiredAt(clock.instant());
            if (!ours) {
                // The hold was placed on another box, or this box restarted. The session proves the buyer was
                // promised the seat; this box can only honour that if its own seat map still has it free.
                hold = seatMap.hold(session.seatId(), session.buyerId());
            }
            Booking booking = seatMap.confirm(hold, ticketCode, node);
            sessions.finished(handle);
            return booking;
        } finally {
            timer.record("reserve", System.nanoTime() - started);
        }
    }

    public String node() {
        return node;
    }
}
