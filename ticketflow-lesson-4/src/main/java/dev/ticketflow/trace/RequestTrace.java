package dev.ticketflow.trace;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Where one request's server-side time went, hop by hop. It records durations only, never
 * timestamps, so no two machines ever need to agree on what time it is.
 */
public final class RequestTrace {
    private final Map<String, Long> nanos = new LinkedHashMap<>();
    private final long startNanos = System.nanoTime();
    private final long budgetMs;

    RequestTrace(long budgetMs) {
        this.budgetMs = budgetMs;
        for (String stage : new String[] {"lock", "domain", "payment", "render"}) {
            nanos.put(stage, 0L);
        }
    }

    public void add(String stage, long durationNanos) {
        nanos.merge(stage, durationNanos, Long::sum);
    }

    public <T> T time(String stage, Supplier<T> work) {
        long t0 = System.nanoTime();
        try {
            return work.get();
        } finally {
            add(stage, System.nanoTime() - t0);
        }
    }

    public double ms(String stage) {
        return nanos.getOrDefault(stage, 0L) / 1_000_000.0;
    }

    public double serverMs() {
        return nanos.values().stream().mapToLong(Long::longValue).sum() / 1_000_000.0;
    }

    /**
     * Budget left, by this server's own stopwatch only. Whatever the request spent on the wire
     * and in queues before it arrived is invisible here, so this is always an optimistic number.
     */
    public double leftMs() {
        return budgetMs > 0 ? budgetMs - (System.nanoTime() - startNanos) / 1_000_000.0 : -1;
    }

    /** The W3C Server-Timing header value: name;dur=milliseconds, comma separated. */
    public String serverTiming() {
        StringBuilder sb = new StringBuilder();
        nanos.forEach((stage, n) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(stage).append(";dur=").append(fmt(n / 1_000_000.0));
        });
        if (budgetMs > 0) {
            sb.append(", left;dur=").append(fmt(leftMs())).append(";desc=\"budget left\"");
        }
        return sb.toString();
    }

    /** One log-friendly line of the same numbers. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        nanos.forEach((stage, n) -> sb.append(stage).append("Ms=").append(fmt(n / 1_000_000.0)).append(' '));
        if (budgetMs > 0) {
            sb.append("budgetLeftMs=").append(fmt(leftMs()));
        }
        return sb.toString().trim();
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
