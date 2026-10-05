package dev.ticketflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import dev.ticketflow.domain.Booking;
import dev.ticketflow.domain.DomainException;
import dev.ticketflow.domain.DomainException.Code;
import dev.ticketflow.domain.Hold;
import dev.ticketflow.domain.HoldState;
import dev.ticketflow.domain.SeatId;
import dev.ticketflow.inventory.EventLog;
import dev.ticketflow.inventory.VenueService;
import org.junit.jupiter.api.Test;

class VenueServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T12:00:00Z"));
    private final VenueService venue = new VenueService(clock, new EventLog(), 30, 20, 25);
    private final SeatId a1 = new SeatId("A-01");

    private Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e.code();
        }
        return null;
    }

    @Test
    void aHeldSeatIsNotAvailableToAnotherBuyer() {
        venue.placeHold(a1, "sam");
        assertThat(codeOf(() -> venue.placeHold(a1, "alex"))).isEqualTo(Code.SEAT_UNAVAILABLE);
        assertThat(venue.snapshot().held()).isEqualTo(1);
    }

    @Test
    void aHoldStopsBlockingItsSeatTheMomentTheClockSaysSoWithNoSweeper() {
        venue.placeHold(a1, "sam");
        clock.advance(Duration.ofSeconds(30));

        assertThat(venue.snapshot().available()).isEqualTo(500);
        Hold second = venue.placeHold(a1, "alex");
        assertThat(second.buyer()).isEqualTo("alex");
    }

    @Test
    void confirmingAfterTheDeadlineIsRejectedAndTheSeatIsFree() {
        Hold hold = venue.placeHold(a1, "sam");
        clock.advance(Duration.ofSeconds(31));

        assertThat(codeOf(() -> venue.confirm(hold.id()))).isEqualTo(Code.HOLD_EXPIRED);
        assertThat(venue.snapshot().booked()).isZero();
        assertThat(venue.snapshot().available()).isEqualTo(500);
    }

    @Test
    void confirmingBeforeTheDeadlineBooksTheSeatForGood() {
        Hold hold = venue.placeHold(a1, "sam");
        clock.advance(Duration.ofSeconds(29));
        Booking booking = venue.confirm(hold.id());

        assertThat(booking.holdId()).isEqualTo(hold.id());
        clock.advance(Duration.ofHours(1));
        assertThat(venue.snapshot().booked()).isEqualTo(1);
        assertThat(codeOf(() -> venue.placeHold(a1, "alex"))).isEqualTo(Code.SEAT_UNAVAILABLE);
    }

    @Test
    void aHoldCanBeConfirmedOnlyOnce() {
        Hold hold = venue.placeHold(a1, "sam");
        venue.confirm(hold.id());
        assertThat(codeOf(() -> venue.confirm(hold.id()))).isEqualTo(Code.HOLD_NOT_ACTIVE);
    }

    @Test
    void releasingFreesTheSeatImmediately() {
        Hold hold = venue.placeHold(a1, "sam");
        venue.release(hold.id());
        assertThat(venue.snapshot().available()).isEqualTo(500);
        assertThat(venue.placeHold(a1, "alex").buyer()).isEqualTo("alex");
    }

    @Test
    void theSweeperRetiresExpiredHoldsButNothingElse() {
        Hold dead = venue.placeHold(a1, "sam");
        clock.advance(Duration.ofSeconds(20));
        Hold alive = venue.placeHold(new SeatId("A-02"), "alex");
        clock.advance(Duration.ofSeconds(15));

        List<Hold> swept = venue.sweepExpired();

        assertThat(swept).extracting(Hold::id).containsExactly(dead.id());
        assertThat(swept.get(0).state()).isEqualTo(HoldState.EXPIRED);
        assertThat(venue.snapshot().liveHolds()).extracting(Hold::id).containsExactly(alive.id());
    }

    @Test
    void unknownSeatsAndHoldsAndBlankBuyersAreRejected() {
        assertThat(codeOf(() -> venue.placeHold(new SeatId("Z-99"), "sam"))).isEqualTo(Code.UNKNOWN_SEAT);
        assertThat(codeOf(() -> venue.confirm("h-9999"))).isEqualTo(Code.UNKNOWN_HOLD);
        assertThat(codeOf(() -> venue.placeHold(a1, " "))).isEqualTo(Code.BAD_REQUEST);
    }

    @Test
    void everySeatSellsExactlyOnceUnderContention() {
        Set<SeatId> sold = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 3_000; i++) {
                String buyer = "b-" + i;
                pool.submit(() -> venue.bookFirstAvailable(buyer).ifPresent(b -> sold.add(b.seatId())));
            }
        }
        assertThat(sold).hasSize(500);
        assertThat(venue.bookedCount()).isEqualTo(500);
    }
}
