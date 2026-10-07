package com.ticketflow.bench;

/**
 * What happened during one step of a ramp, where buyers arrived at a fixed rate.
 *
 * @param offeredRate       buyers per second the bench sent, regardless of how the box coped
 * @param answeredPerSecond answers (booked or sold-out) that came back inside the step window
 * @param p99Millis         latency measured from when each buyer was due to arrive, not when it was sent
 * @param healthy           p99 inside the latency budget and under 1% of buyers failed
 * @param fellOver          at least 20% of buyers got an error, a timeout, or were never sent
 */
public record StepResult(int offeredRate, int seconds, long offered, long booked, long soldOut,
                         long errors, long timeouts, long shed, double answeredPerSecond,
                         double p50Millis, double p99Millis, double maxMillis,
                         double failureRatio, boolean healthy, boolean fellOver) {
}
