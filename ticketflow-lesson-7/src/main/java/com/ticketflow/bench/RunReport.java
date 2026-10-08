package com.ticketflow.bench;

import java.util.List;

/**
 * The outcome of one ramp.
 *
 * @param status         RUNNING, FINISHED (reached the end rate), FELL_OVER, or FAILED
 * @param strategy       the balancer strategy used, or empty when buyers went straight to the boxes
 * @param checkout       {@code server} or {@code signed} for two-step runs, empty for one-shot bookings
 * @param fault          the fault that was switched on, for example {@code slow x3 on http://localhost:8082}, or empty
 * @param kneeRate       the highest offered rate the target answered inside the latency budget
 * @param seatsSold      bookings added up across every box
 * @param seatsSoldTwice seats that more than one box sold
 * @param shares         how the balancer split the traffic; empty without a balancer
 */
public record RunReport(String id, String label, String group, List<String> targets, String strategy, String checkout,
                        String fault,
                        String status, List<StepResult> steps, int kneeRate, int budgetMillis,
                        int seatsSold, int seatsSoldTwice, List<BackendShare> shares,
                        String startedAt, String note) {
}
