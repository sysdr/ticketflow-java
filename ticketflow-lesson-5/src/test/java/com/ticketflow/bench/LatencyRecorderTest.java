package com.ticketflow.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LatencyRecorderTest {

    private static final long MILLI = 1_000_000L;

    @Test
    void aStepIsHealthyWhenEveryoneIsAnsweredInsideTheBudget() {
        LatencyRecorder recorder = new LatencyRecorder();
        for (int i = 1; i <= 100; i++) {
            recorder.booked(i * MILLI, true);
        }
        StepResult step = recorder.toResult(50, 2, 100, 200);
        assertEquals(50.0, step.p50Millis(), 0.001);
        assertEquals(99.0, step.p99Millis(), 0.001);
        assertEquals(50.0, step.answeredPerSecond(), 0.001);
        assertTrue(step.healthy());
        assertFalse(step.fellOver());
    }

    @Test
    void aSlowTailBlowsTheBudgetEvenWhenNothingFails() {
        LatencyRecorder recorder = new LatencyRecorder();
        for (int i = 0; i < 95; i++) {
            recorder.soldOut(10 * MILLI, true);
        }
        for (int i = 0; i < 5; i++) {
            recorder.soldOut(900 * MILLI, false);
        }
        StepResult step = recorder.toResult(50, 2, 100, 200);
        assertEquals(0.0, step.failureRatio(), 0.0001);
        assertEquals(900.0, step.p99Millis(), 0.001);
        assertFalse(step.healthy());
        assertFalse(step.fellOver());
    }

    @Test
    void aStepHasFallenOverWhenAFifthOfBuyersGetNoAnswer() {
        LatencyRecorder recorder = new LatencyRecorder();
        for (int i = 0; i < 70; i++) {
            recorder.booked(50 * MILLI, true);
        }
        for (int i = 0; i < 20; i++) {
            recorder.timeout(2_000 * MILLI);
        }
        for (int i = 0; i < 10; i++) {
            recorder.shed();
        }
        StepResult step = recorder.toResult(50, 2, 100, 200);
        assertEquals(0.30, step.failureRatio(), 0.0001);
        assertTrue(step.fellOver());
        assertFalse(step.healthy());
    }
}
