package com.ticketflow.bench;

import com.ticketflow.balancer.Strategy;
import com.ticketflow.faults.FaultSwitch;
import java.util.ArrayList;
import java.util.List;

/**
 * One experiment: send buyers at {@code startRate} per second, then raise the
 * rate by {@code stepRate} every {@code stepSeconds} until {@code endRate} or
 * until the target falls over.
 *
 * @param group    experiments in the same group are drawn on the same chart
 * @param targets  the boxes that will do the work; they are reset before and audited after
 * @param balancer when set, buyers are sent here instead of straight to the boxes
 * @param strategy the strategy the balancer is switched to before the run
 * @param checkout null for one-shot bookings; {@code server} or {@code signed} for the two-step checkout,
 *                 with every box switched to that kind of session before the run
 * @param fault    an optional fault for one box: {@code slow} or {@code fail-fast} for the whole run,
 *                 or {@code restart} once, halfway through the first step
 */
public record RampPlan(String label, String group, List<String> targets, String balancer, String strategy,
                       String checkout, Fault fault, int startRate, int endRate, int stepRate, int stepSeconds) {

    /**
     * @param target one of the plan's targets
     * @param mode   {@code slow}, {@code fail-fast} or {@code restart}
     * @param factor for {@code slow}, how many times more CPU each request costs
     */
    public record Fault(String target, String mode, int factor) {
        public String describe() {
            return "slow".equalsIgnoreCase(mode) ? "slow x" + factor : mode;
        }

        public boolean restart() {
            return RESTART.equalsIgnoreCase(mode);
        }
    }

    public static final String RESTART = "restart";

    /** A direct ramp with no balancer and no fault, as in lesson 5. */
    public static RampPlan direct(String label, List<String> targets, int startRate, int endRate,
                                  int stepRate, int stepSeconds) {
        return new RampPlan(label, null, targets, null, null, null, null, startRate, endRate, stepRate, stepSeconds);
    }

    /** True when each buyer goes through start and confirm instead of one booking call. */
    public boolean twoStep() {
        return checkout != null;
    }

    /** Where buyers are actually sent: the balancer's proxy path if there is one, else the boxes. */
    public List<String> entryPoints() {
        return balancer == null ? targets : List.of(balancer + "/lb");
    }

    /** Returns a cleaned copy, or throws with a message a person can act on. */
    public RampPlan validated() {
        if (targets == null || targets.isEmpty() || targets.size() > 8) {
            throw new IllegalArgumentException("give between 1 and 8 target URLs");
        }
        List<String> cleaned = new ArrayList<>();
        for (String target : targets) {
            cleaned.add(cleanUrl(target, "target"));
        }
        String cleanBalancer = null;
        String cleanStrategy = null;
        if (balancer != null && !balancer.isBlank()) {
            cleanBalancer = cleanUrl(balancer, "balancer");
            cleanStrategy = Strategy.named(strategy).name();
        }
        String cleanCheckout = null;
        if (checkout != null && !checkout.isBlank()) {
            cleanCheckout = checkout.trim().toLowerCase();
            if (!cleanCheckout.equals("server") && !cleanCheckout.equals("signed")) {
                throw new IllegalArgumentException("checkout must be server or signed, got: " + checkout);
            }
        }
        Fault cleanFault = null;
        if (fault != null && fault.mode() != null && !fault.mode().isBlank()
                && (fault.restart() || FaultSwitch.Mode.parse(fault.mode()) != FaultSwitch.Mode.NONE)) {
            String faultTarget = cleanUrl(fault.target(), "fault target");
            if (!cleaned.contains(faultTarget)) {
                throw new IllegalArgumentException("the fault target must be one of the targets");
            }
            cleanFault = new Fault(faultTarget, fault.mode().trim().toLowerCase(), Math.max(1, fault.factor()));
        }
        if (startRate < 1 || endRate < startRate || endRate > 20_000) {
            throw new IllegalArgumentException("rates must satisfy 1 <= startRate <= endRate <= 20000");
        }
        if (stepRate < 1) {
            throw new IllegalArgumentException("stepRate must be at least 1");
        }
        if (stepSeconds < 1 || stepSeconds > 60) {
            throw new IllegalArgumentException("stepSeconds must be between 1 and 60");
        }
        if ((endRate - startRate) / stepRate + 1 > 60) {
            throw new IllegalArgumentException("that ramp has more than 60 steps; raise stepRate");
        }
        String name = label == null || label.isBlank() ? cleaned.size() + " box(es)" : label.trim();
        String cleanGroup = group == null || group.isBlank() ? "Ad hoc" : group.trim();
        return new RampPlan(name, cleanGroup, List.copyOf(cleaned), cleanBalancer, cleanStrategy, cleanCheckout,
                cleanFault, startRate, endRate, stepRate, stepSeconds);
    }

    private static String cleanUrl(String url, String what) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException(what + " must start with http:// or https://, got: " + url);
        }
        return trimmed;
    }
}
