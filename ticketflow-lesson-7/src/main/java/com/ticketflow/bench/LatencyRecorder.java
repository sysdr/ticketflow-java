package com.ticketflow.bench;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/** Collects the outcome of every buyer in one step. Safe to call from many threads. */
final class LatencyRecorder {

    private final ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
    private final LongAdder booked = new LongAdder();
    private final LongAdder soldOut = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder sessionLost = new LongAdder();
    private final LongAdder shed = new LongAdder();
    private final LongAdder answeredInWindow = new LongAdder();

    void booked(long latencyNanos, boolean insideWindow) {
        booked.increment();
        answered(latencyNanos, insideWindow);
    }

    void soldOut(long latencyNanos, boolean insideWindow) {
        soldOut.increment();
        answered(latencyNanos, insideWindow);
    }

    void error(long latencyNanos) {
        errors.increment();
        latenciesNanos.add(latencyNanos);
    }

    void timeout(long latencyNanos) {
        timeouts.increment();
        latenciesNanos.add(latencyNanos);
    }

    /** A two-step buyer held a seat, came back to pay, and the box had no idea who they were. */
    void sessionLost(long latencyNanos) {
        sessionLost.increment();
        latenciesNanos.add(latencyNanos);
    }

    /** The bench already had too many unanswered buyers open and did not send this one. */
    void shed() {
        shed.increment();
    }

    private void answered(long latencyNanos, boolean insideWindow) {
        latenciesNanos.add(latencyNanos);
        if (insideWindow) {
            answeredInWindow.increment();
        }
    }

    StepResult toResult(int rate, int seconds, long offered, int budgetMillis) {
        long[] sorted = latenciesNanos.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        long failures = errors.sum() + timeouts.sum() + sessionLost.sum() + shed.sum();
        double failureRatio = offered == 0 ? 0 : failures / (double) offered;
        double p99 = percentileMillis(sorted, 0.99);
        boolean healthy = failureRatio < 0.01 && p99 <= budgetMillis;
        boolean fellOver = failureRatio >= 0.20;
        return new StepResult(rate, seconds, offered, booked.sum(), soldOut.sum(), errors.sum(),
                timeouts.sum(), sessionLost.sum(), shed.sum(), answeredInWindow.sum() / (double) seconds,
                percentileMillis(sorted, 0.50), p99, percentileMillis(sorted, 1.0),
                failureRatio, healthy, fellOver);
    }

    static double percentileMillis(long[] sortedNanos, double quantile) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(quantile * sortedNanos.length) - 1;
        index = Math.max(0, Math.min(index, sortedNanos.length - 1));
        return sortedNanos[index] / 1_000_000.0;
    }
}
