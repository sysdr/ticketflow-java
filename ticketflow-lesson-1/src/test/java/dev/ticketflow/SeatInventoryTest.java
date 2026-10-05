package dev.ticketflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import dev.ticketflow.inventory.SeatInventory;
import org.junit.jupiter.api.Test;

class SeatInventoryTest {

    @Test
    void tenThousandBuyersGetExactlyFiveHundredDistinctSeats() {
        SeatInventory inventory = new SeatInventory(500);
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
