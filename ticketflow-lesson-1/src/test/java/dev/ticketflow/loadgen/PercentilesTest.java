package dev.ticketflow.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

class PercentilesTest {

    @Test
    void nearestRankOverOneToHundred() {
        long[] sorted = LongStream.rangeClosed(1, 100).toArray();
        assertThat(Percentiles.of(sorted, 50)).isEqualTo(50);
        assertThat(Percentiles.of(sorted, 95)).isEqualTo(95);
        assertThat(Percentiles.of(sorted, 99)).isEqualTo(99);
    }

    @Test
    void emptyInputGivesMinusOne() {
        assertThat(Percentiles.of(new long[0], 50)).isEqualTo(-1);
    }

    @Test
    void unsetEntriesAreDropped() {
        assertThat(Percentiles.sortedNonNegative(new long[] {-1, 7, -1, 3})).containsExactly(3, 7);
    }
}
