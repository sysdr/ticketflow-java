package com.ticketflow.lifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Records how long each hop of one request took, so the total can be checked
 * against the latency budget from lesson 4 and sent back as a Server-Timing header.
 */
public final class StageTimer {

    public static final String ATTRIBUTE = StageTimer.class.getName();

    private record Stage(String name, long nanos) {
    }

    private final long startedNanos = System.nanoTime();
    private final List<Stage> stages = new ArrayList<>(4);

    public synchronized void record(String name, long nanos) {
        stages.add(new Stage(name, Math.max(0, nanos)));
    }

    public synchronized double millisOf(String name) {
        long total = 0;
        for (Stage stage : stages) {
            if (stage.name().equals(name)) {
                total += stage.nanos();
            }
        }
        return total / 1_000_000.0;
    }

    public double totalMillis() {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    /** For example {@code queue;dur=41.2, sign;dur=8.1, reserve;dur=0.0}. */
    public synchronized String serverTiming() {
        StringBuilder header = new StringBuilder();
        for (Stage stage : stages) {
            if (header.length() > 0) {
                header.append(", ");
            }
            header.append(stage.name()).append(";dur=")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", stage.nanos() / 1_000_000.0));
        }
        return header.toString();
    }
}
