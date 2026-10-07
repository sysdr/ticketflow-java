package com.ticketflow.loadgen;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mixed load generator for TicketFlow Lesson 6 — compares round-robin vs least-connections.
 *
 * Usage:
 *   java MixedLoadGenerator.java --algorithm rr --output reports/rr-results.csv --rps 80
 *   java MixedLoadGenerator.java --algorithm lc --output reports/lc-results.csv --rps 80
 *
 * Traffic mix (fixed at 80/20 per the lesson homework baseline):
 *   80% POST /bookings          — fast path, ~30ms
 *   20% GET /venues/1/seatmap   — slow path, ~400ms (intentional Thread.sleep in service)
 *
 * Output CSV columns:
 *   timestamp_ms, request_type (booking|seatmap), latency_ms, status_code, algorithm
 *
 * Run duration: 60 seconds, then writes CSV and exits.
 */
public class MixedLoadGenerator {

    // ── configuration ────────────────────────────────────────────────────────

    private static final int DEFAULT_RPS          = 80;
    private static final int DEFAULT_DURATION_SEC = 60;
    private static final String DEFAULT_BASE_URL  = "http://localhost:80";
    private static final long   VENUE_ID          = 1L;

    private final String    algorithm;   // "rr" or "lc"
    private final String    outputPath;
    private final int       rps;
    private final int       durationSec;
    private final String    baseUrl;

    // ── counters ─────────────────────────────────────────────────────────────

    private final AtomicInteger sentBooking  = new AtomicInteger(0);
    private final AtomicInteger sentSeatmap  = new AtomicInteger(0);
    private final AtomicLong    totalLatency = new AtomicLong(0);

    // ── result accumulator ────────────────────────────────────────────────────

    private final List<String[]> rows = new ArrayList<>(DEFAULT_RPS * DEFAULT_DURATION_SEC + 100);

    // ── HTTP client ───────────────────────────────────────────────────────────

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ── body templates ────────────────────────────────────────────────────────

    // Cycle through dummy email addresses so the service always finds a seat (until sold out).
    private final AtomicInteger userCounter = new AtomicInteger(0);

    private String nextBookingBody() {
        int n = userCounter.getAndIncrement() % 100_000;
        return """
               {"venueId":%d,"buyerEmail":"user%d@loadtest.example"}
               """.formatted(VENUE_ID, n).strip();
    }

    // ─────────────────────────────────────────────────────────────────────────

    public MixedLoadGenerator(String algorithm, String outputPath, int rps,
                               int durationSec, String baseUrl) {
        this.algorithm   = algorithm;
        this.outputPath  = outputPath;
        this.rps         = rps;
        this.durationSec = durationSec;
        this.baseUrl     = baseUrl;
    }

    // ── main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String algorithm   = "rr";
        String outputPath  = "reports/results.csv";
        int    rps         = DEFAULT_RPS;
        int    durationSec = DEFAULT_DURATION_SEC;
        String baseUrl     = DEFAULT_BASE_URL;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--algorithm" -> algorithm   = args[++i];
                case "--output"    -> outputPath  = args[++i];
                case "--rps"       -> rps         = Integer.parseInt(args[++i]);
                case "--duration"  -> durationSec = Integer.parseInt(args[++i]);
                case "--base-url"  -> baseUrl     = args[++i];
            }
        }

        System.out.printf("Starting load generator: algorithm=%s rps=%d duration=%ds output=%s%n",
                algorithm, rps, durationSec, outputPath);

        var gen = new MixedLoadGenerator(algorithm, outputPath, rps, durationSec, baseUrl);
        gen.run();
    }

    // ── run loop ──────────────────────────────────────────────────────────────

    public void run() throws Exception {
        // Use a virtual-thread-per-task executor so slow seatmap requests don't
        // block the scheduler. Each request fires independently.
        var pool = Executors.newVirtualThreadPerTaskExecutor();

        // Scheduler fires "rps" tasks per second.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        long intervalMicros = 1_000_000L / rps; // microsecond spacing between requests
        long stopAt = System.currentTimeMillis() + (durationSec * 1000L);

        AtomicInteger tick = new AtomicInteger(0);

        scheduler.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() >= stopAt) {
                scheduler.shutdown();
                return;
            }
            int t = tick.getAndIncrement();
            // 80/20 split: every 5th request (indices 0,1,2,3 = booking; 4 = seatmap)
            boolean isSeatmap = (t % 5 == 4);

            pool.submit(() -> {
                if (isSeatmap) {
                    sendSeatmap();
                } else {
                    sendBooking();
                }
            });
        }, 0, intervalMicros, TimeUnit.MICROSECONDS);

        // Wait for duration + 2s drain time
        scheduler.awaitTermination(durationSec + 2, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        printSummary();
        writeCsv();
        System.out.println("Done. Results written to " + outputPath);
    }

    // ── request senders ───────────────────────────────────────────────────────

    private void sendBooking() {
        // Path prefix selects the upstream block in NGINX
        String path = algorithm.equals("rr") ? "/rr/bookings" : "/lc/bookings";
        String url  = baseUrl + path;
        String body = nextBookingBody();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        long start = System.currentTimeMillis();
        int  status = 0;
        try {
            HttpResponse<Void> resp = http.send(request, HttpResponse.BodyHandlers.discarding());
            status = resp.statusCode();
        } catch (Exception e) {
            status = -1;
        }
        long latency = System.currentTimeMillis() - start;

        sentBooking.incrementAndGet();
        totalLatency.addAndGet(latency);
        record(start, "booking", latency, status);
    }

    private void sendSeatmap() {
        String path = algorithm.equals("rr")
                ? "/rr/venues/" + VENUE_ID + "/seatmap"
                : "/lc/venues/" + VENUE_ID + "/seatmap";
        String url = baseUrl + path;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        long start = System.currentTimeMillis();
        int  status = 0;
        try {
            HttpResponse<Void> resp = http.send(request, HttpResponse.BodyHandlers.discarding());
            status = resp.statusCode();
        } catch (Exception e) {
            status = -1;
        }
        long latency = System.currentTimeMillis() - start;

        sentSeatmap.incrementAndGet();
        totalLatency.addAndGet(latency);
        record(start, "seatmap", latency, status);
    }

    // ── recording ─────────────────────────────────────────────────────────────

    private synchronized void record(long timestampMs, String type, long latencyMs, int statusCode) {
        rows.add(new String[]{ String.valueOf(timestampMs), type,
                               String.valueOf(latencyMs), String.valueOf(statusCode),
                               algorithm });
    }

    // ── output ────────────────────────────────────────────────────────────────

    private void printSummary() {
        int total = sentBooking.get() + sentSeatmap.get();
        System.out.printf("""
                ── Summary ─────────────────────────────
                  Algorithm : %s
                  Requests  : %d (booking=%d, seatmap=%d)
                  Avg lat   : %.1f ms
                ────────────────────────────────────────
                """,
                algorithm, total, sentBooking.get(), sentSeatmap.get(),
                total > 0 ? (double) totalLatency.get() / total : 0.0);
    }

    private void writeCsv() throws IOException {
        // Ensure parent directory exists
        java.nio.file.Path p = java.nio.file.Path.of(outputPath);
        if (p.getParent() != null) {
            java.nio.file.Files.createDirectories(p.getParent());
        }

        try (var out = new PrintWriter(new FileWriter(outputPath))) {
            out.println("timestamp_ms,request_type,latency_ms,status_code,algorithm");
            for (String[] row : rows) {
                out.println(String.join(",", row));
            }
        }
    }
}
