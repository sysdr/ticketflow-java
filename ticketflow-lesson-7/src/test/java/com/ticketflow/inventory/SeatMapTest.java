package com.ticketflow.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.Hold;
import com.ticketflow.domain.SeatStatus;
import com.ticketflow.domain.SeatTakenException;
import com.ticketflow.domain.Venue;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SeatMapTest {

    private static final Venue VENUE = new Venue("riverside", "Riverside Hall", 20, 25);

    @Test
    void aVenueOfTwentyRowsOfTwentyFiveHasFiveHundredSeats() {
        SeatMap map = new SeatMap(VENUE, Clock.systemUTC());
        assertEquals(500, map.seatIds().size());
        assertEquals("R01-01", map.seatIds().get(0));
        assertEquals("R20-25", map.seatIds().get(499));
    }

    @Test
    void oneBoxNeverSellsTheSameSeatTwiceHoweverManyThreadsAsk() throws Exception {
        SeatMap map = new SeatMap(VENUE, Clock.systemUTC());
        int buyers = 64;
        ExecutorService crowd = Executors.newFixedThreadPool(buyers);
        CountDownLatch doorsOpen = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyers);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        for (int i = 0; i < buyers; i++) {
            String buyerId = "buyer-" + i;
            crowd.submit(() -> {
                try {
                    doorsOpen.await();
                    Hold hold = map.hold("R01-01", buyerId);
                    map.confirm(hold, "CODE", "box-a");
                    winners.incrementAndGet();
                } catch (SeatTakenException taken) {
                    losers.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        doorsOpen.countDown();
        done.await(10, TimeUnit.SECONDS);
        crowd.shutdownNow();

        assertEquals(1, winners.get());
        assertEquals(buyers - 1, losers.get());
        assertEquals(1, map.bookedCount());
        assertEquals(SeatStatus.BOOKED, map.statusOf("R01-01"));
    }

    @Test
    void anExpiredHoldLetsTheNextBuyerIn() {
        MovableClock clock = new MovableClock(Instant.parse("2026-01-01T20:00:00Z"));
        SeatMap map = new SeatMap(VENUE, clock);

        map.hold("R05-05", "slow-buyer");
        assertEquals(SeatStatus.HELD, map.statusOf("R05-05"));
        assertThrows(SeatTakenException.class, () -> map.hold("R05-05", "second-buyer"));

        clock.advance(SeatMap.HOLD_TTL.plusSeconds(1));
        assertEquals(SeatStatus.AVAILABLE, map.statusOf("R05-05"));
        Hold hold = map.hold("R05-05", "second-buyer");
        Booking booking = map.confirm(hold, "CODE", "box-a");
        assertEquals("second-buyer", booking.buyerId());
    }

    @Test
    void resetEmptiesTheHall() {
        SeatMap map = new SeatMap(VENUE, Clock.systemUTC());
        map.confirm(map.hold("R02-02", "buyer"), "CODE", "box-a");
        map.reset();
        assertEquals(0, map.bookedCount());
        assertEquals(SeatStatus.AVAILABLE, map.statusOf("R02-02"));
    }

    @Test
    void anUnknownSeatIsRejected() {
        SeatMap map = new SeatMap(VENUE, Clock.systemUTC());
        assertThrows(IllegalArgumentException.class, () -> map.hold("R99-99", "buyer"));
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        private void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
