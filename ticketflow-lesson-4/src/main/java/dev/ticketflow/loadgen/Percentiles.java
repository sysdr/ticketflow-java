package dev.ticketflow.loadgen;

import java.util.Arrays;

public final class Percentiles {
    private Percentiles() {}

    /** Nearest-rank percentile of already-sorted values; -1 when there are none. */
    public static long of(long[] sorted, double percent) {
        if (sorted.length == 0) {
            return -1;
        }
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    public static long[] sortedNonNegative(long[] values) {
        long[] kept = Arrays.stream(values).filter(v -> v >= 0).toArray();
        Arrays.sort(kept);
        return kept;
    }
}
