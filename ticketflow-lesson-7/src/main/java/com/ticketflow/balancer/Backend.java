package com.ticketflow.balancer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** One box behind the balancer, plus what the balancer currently knows about it. */
public final class Backend {

    private final String name;
    private final String url;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final LongAdder sent = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public Backend(String name, String url) {
        if (name == null || name.isBlank() || url == null || !url.startsWith("http")) {
            throw new IllegalArgumentException("a backend needs a name and an http(s) url");
        }
        this.name = name;
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String name() {
        return name;
    }

    public String url() {
        return url;
    }

    /** Requests forwarded to this box that have not come back yet. This is all least-connections looks at. */
    public int inFlight() {
        return inFlight.get();
    }

    public long sent() {
        return sent.sum();
    }

    /** Requests that came back as HTTP 5xx or did not come back at all. */
    public long failed() {
        return failed.sum();
    }

    void started() {
        inFlight.incrementAndGet();
        sent.increment();
    }

    void finished(boolean ok) {
        inFlight.decrementAndGet();
        if (!ok) {
            failed.increment();
        }
    }

    void resetCounters() {
        sent.reset();
        failed.reset();
    }
}
