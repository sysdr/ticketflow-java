package com.ticketflow.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.SeatTakenException;
import com.ticketflow.domain.Venue;
import com.ticketflow.faults.FaultSwitch;
import com.ticketflow.inventory.SeatMap;
import com.ticketflow.lifecycle.StageTimer;
import com.ticketflow.scaling.CpuPool;
import com.ticketflow.scaling.TicketSigner;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Two boxes in one test: each with its own seat map, both with the same signing key. */
class CheckoutServiceTest {

    private static final String KEY = "a-test-key-that-is-long-enough-0123456789";
    private final List<CpuPool> pools = new ArrayList<>();

    @AfterEach
    void stopPools() {
        pools.forEach(CpuPool::close);
    }

    private CheckoutService box(String node, String kind) {
        Clock clock = Clock.systemUTC();
        CpuPool pool = new CpuPool(1);
        pools.add(pool);
        return new CheckoutService(new SeatMap(new Venue("riverside", "Riverside Hall", 20, 25), clock),
                TicketSigner.withRounds(1_000), pool, new FaultSwitch(), node, clock,
                new ServerSessionStore(node, clock), new SignedSessionTokens(KEY, clock), kind);
    }

    @Test
    void aServerSessionOnlyWorksOnTheBoxThatHandedItOut() {
        CheckoutService boxA = box("box-a", "server");
        CheckoutService boxB = box("box-b", "server");
        String session = boxA.start("R07-12", "ana", new StageTimer()).session();

        SessionLostException lost = assertThrows(SessionLostException.class,
                () -> boxB.confirm(session, new StageTimer()));
        assertEquals("unknown", lost.reason());

        Booking booking = boxA.confirm(session, new StageTimer());
        assertEquals("box-a", booking.node());
    }

    @Test
    void aSignedSessionWorksOnAnyBoxWithTheKey() {
        CheckoutService boxA = box("box-a", "signed");
        CheckoutService boxB = box("box-b", "signed");
        String token = boxA.start("R07-12", "ana", new StageTimer()).session();

        Booking booking = boxB.confirm(token, new StageTimer());
        assertEquals("box-b", booking.node());
        assertEquals("ana", booking.buyerId());
    }

    @Test
    void aRestartLosesServerSessionsButNotSignedOnes() {
        CheckoutService server = box("box-a", "server");
        String serverSession = server.start("R01-01", "ana", new StageTimer()).session();
        assertEquals(1, server.liveSessionsInMemory());
        server.forgetEverythingInMemory();
        assertEquals("unknown", assertThrows(SessionLostException.class,
                () -> server.confirm(serverSession, new StageTimer())).reason());

        CheckoutService signed = box("box-b", "signed");
        String token = signed.start("R01-01", "ben", new StageTimer()).session();
        assertEquals(0, signed.liveSessionsInMemory());
        signed.forgetEverythingInMemory();
        assertEquals("ben", signed.confirm(token, new StageTimer()).buyerId());
    }

    @Test
    void aSignedTokenCannotBuyTheSameSeatTwice() {
        CheckoutService box = box("box-a", "signed");
        String token = box.start("R02-02", "ana", new StageTimer()).session();
        box.confirm(token, new StageTimer());
        assertThrows(SeatTakenException.class, () -> box.confirm(token, new StageTimer()));
    }

    @Test
    void theSessionKindCanBeSwitchedWhileRunning() {
        CheckoutService box = box("box-a", "signed");
        assertEquals("signed", box.sessionKind());
        box.use("server");
        assertEquals("server", box.sessionKind());
        assertThrows(IllegalArgumentException.class, () -> box.use("cookie"));
    }
}
