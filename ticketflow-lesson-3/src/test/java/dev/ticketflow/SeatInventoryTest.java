package dev.ticketflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import dev.ticketflow.inventory.EventLog;
import dev.ticketflow.inventory.SeatInventory;
import dev.ticketflow.inventory.VenueService;
import org.junit.jupiter.api.Test;

class SeatInventoryTest {

    @Test
    void tenThousandBuyersGetExactlyFiveHundredDistinctSeats() {
        SeatInventory inventory = new SeatInventory(new VenueService(Clock.systemUTC(), new EventLog(), 30, 20, 25));
        Set<Integer> seats = ConcurrentHashMap.newKeySet();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10_000; i++) {
                String buyer = "b-" + i;
                pool.submit(() -> inventory.claim(buyer).ifPresent(seats::add));
            }
        }

        assertThat(seats).hasSize(500);
        assertThat(inventory.sold()).isEqualTo(500);
    }
}
