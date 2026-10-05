package dev.ticketflow.loadgen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import dev.ticketflow.logging.TraceSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Ten thousand buyers, spread evenly over a short window, each sending one real
 * HTTP request through Tomcat and giving up after a fixed patience.
 * One virtual thread per buyer keeps the client side from becoming the bottleneck.
 */
public final class Stampede {
    private static final Logger log = LoggerFactory.getLogger("STAMPEDE");

    private final String baseUrl;
    private final int buyers;
    private final long windowMs;
    private final long patienceMs;
    private final TraceSampler sampler;

    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger responded = new AtomicInteger();
    private final AtomicInteger ok = new AtomicInteger();
    private final AtomicInteger soldOut = new AtomicInteger();
    private final AtomicInteger timedOut = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final long[] latencies;

    private volatile boolean running;
    private volatile boolean done;
    private volatile long startedNanos;
    private volatile long elapsedMs;
    private volatile long p50 = -1, p95 = -1, p99 = -1, max = -1;

    public Stampede(String baseUrl, int buyers, long windowMs, long patienceMs, TraceSampler sampler) {
        this.baseUrl = baseUrl;
        this.buyers = buyers;
        this.windowMs = windowMs;
        this.patienceMs = patienceMs;
        this.sampler = sampler;
        this.latencies = new long[buyers];
        Arrays.fill(latencies, -1);
    }

    public void run() {
        running = true;
        long t0 = System.nanoTime();
        startedNanos = t0;
        log.info("event=stampede_start buyers={} windowMs={} patienceMs={}", buyers, windowMs, patienceMs);
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        try {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < buyers; i++) {
                    final int n = i;
                    pool.submit(() -> buyer(http, n, t0));
                }
            }
        } finally {
            // Buyers who gave up walk away; the server may keep working for them.
            http.shutdownNow();
            elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            long[] sorted = Percentiles.sortedNonNegative(latencies);
            p50 = Percentiles.of(sorted, 50);
            p95 = Percentiles.of(sorted, 95);
            p99 = Percentiles.of(sorted, 99);
            max = sorted.length == 0 ? -1 : sorted[sorted.length - 1];
            done = true;
            running = false;
            log.info("event=stampede_done elapsedMs={} seatsSold={} soldOutReplies={} gaveUp={} failed={}",
                    elapsedMs, ok.get(), soldOut.get(), timedOut.get(), failed.get());
        }
    }

    private void buyer(HttpClient http, int n, long t0) {
        String rid = "b-%05d".formatted(n + 1);
        boolean traced = sampler.traced(rid);
        MDC.put("rid", rid);
        try {
            long waitNanos = t0 + (long) n * windowMs * 1_000_000L / buyers - System.nanoTime();
            if (waitNanos > 0) {
                Thread.sleep(Duration.ofNanos(waitNanos));
            }
            long deadline = System.currentTimeMillis() + patienceMs;
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/bookings"))
                    .header("X-Request-Id", rid)
                    .header("X-Deadline-Epoch-Ms", Long.toString(deadline))
                    .POST(BodyPublishers.noBody())
                    .build();
            sent.incrementAndGet();
            if (traced) {
                log.info("event=send patienceMs={}", patienceMs);
            }
            long start = System.nanoTime();
            try {
                // Patience is enforced on the buyer's own clock, so a slow server cannot stretch it.
                CompletableFuture<HttpResponse<Void>> pending = http
                        .sendAsync(request, BodyHandlers.discarding())
                        .orTimeout(patienceMs, TimeUnit.MILLISECONDS);
                HttpResponse<Void> response = pending.get();
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (ms > patienceMs) {
                    // The answer arrived after the buyer's own clock ran out: they had already left.
                    timedOut.incrementAndGet();
                    if (traced) {
                        log.info("event=gave_up afterMs={} answerArrivedAtMs={}", patienceMs, ms);
                    }
                    return;
                }
                latencies[n] = ms;
                responded.incrementAndGet();
                int status = response.statusCode();
                if (status == 200) {
                    ok.incrementAndGet();
                } else if (status == 409) {
                    soldOut.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
                if (traced) {
                    log.info("event=response status={} ms={}", status, ms);
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
                    timedOut.incrementAndGet();
                    if (traced) {
                        log.info("event=gave_up afterMs={}", patienceMs);
                    }
                } else {
                    failed.incrementAndGet();
                    if (traced) {
                        log.info("event=failed reason={}", cause.getClass().getSimpleName());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed.incrementAndGet();
        } finally {
            MDC.remove("rid");
        }
    }

    public boolean running() { return running; }
    public int ok() { return ok.get(); }
    public int soldOut() { return soldOut.get(); }
    public int timedOut() { return timedOut.get(); }
    public int failed() { return failed.get(); }

    public String toJson() {
        String state = done ? "done" : running ? "running" : "idle";
        long elapsed = running ? (System.nanoTime() - startedNanos) / 1_000_000 : elapsedMs;
        return "{\"state\":\"" + state + "\""
                + ",\"buyers\":" + buyers
                + ",\"windowMs\":" + windowMs
                + ",\"patienceMs\":" + patienceMs
                + ",\"sent\":" + sent.get()
                + ",\"responded\":" + responded.get()
                + ",\"ok\":" + ok.get()
                + ",\"soldOut\":" + soldOut.get()
                + ",\"timedOut\":" + timedOut.get()
                + ",\"failed\":" + failed.get()
                + ",\"elapsedMs\":" + elapsed
                + ",\"p50\":" + p50 + ",\"p95\":" + p95 + ",\"p99\":" + p99 + ",\"max\":" + max + "}";
    }
}
