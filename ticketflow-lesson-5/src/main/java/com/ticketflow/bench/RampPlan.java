package com.ticketflow.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * One experiment: send buyers at {@code startRate} per second, then raise the
 * rate by {@code stepRate} every {@code stepSeconds} until {@code endRate} or
 * until the target falls over.
 */
public record RampPlan(String label, List<String> targets, int startRate, int endRate, int stepRate, int stepSeconds) {

    /** Returns a cleaned copy, or throws with a message a person can act on. */
    public RampPlan validated() {
        if (targets == null || targets.isEmpty() || targets.size() > 8) {
            throw new IllegalArgumentException("give between 1 and 8 target URLs");
        }
        List<String> cleaned = new ArrayList<>();
        for (String target : targets) {
            String trimmed = target == null ? "" : target.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                throw new IllegalArgumentException("target must start with http:// or https://, got: " + target);
            }
            cleaned.add(trimmed);
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
        return new RampPlan(name, List.copyOf(cleaned), startRate, endRate, stepRate, stepSeconds);
    }
}
