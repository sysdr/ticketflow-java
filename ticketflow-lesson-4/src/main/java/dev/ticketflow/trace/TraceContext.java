package dev.ticketflow.trace;

/** The trace of the request running on this thread, if any. Code outside a traced request is unaffected. */
public final class TraceContext {
    private static final ThreadLocal<RequestTrace> CURRENT = new ThreadLocal<>();

    private TraceContext() {}

    public static RequestTrace begin(long budgetMs) {
        RequestTrace trace = new RequestTrace(budgetMs);
        CURRENT.set(trace);
        return trace;
    }

    public static void end() {
        CURRENT.remove();
    }

    /** Adds time to a stage of the current trace; does nothing when no request is being traced. */
    public static void add(String stage, long durationNanos) {
        RequestTrace trace = CURRENT.get();
        if (trace != null) {
            trace.add(stage, durationNanos);
        }
    }
}
