package com.ticketflow.bench;

import java.util.List;

/**
 * The outcome of one ramp.
 *
 * @param status         RUNNING, FINISHED (reached the end rate), FELL_OVER, or FAILED
 * @param kneeRate       the highest offered rate the target answered inside the latency budget
 * @param seatsSold      bookings added up across every box
 * @param seatsSoldTwice seats that more than one box sold
 */
public record RunReport(String id, String label, List<String> targets, String status, List<StepResult> steps,
                        int kneeRate, int budgetMillis, int seatsSold, int seatsSoldTwice,
                        String startedAt, String note) {
}
