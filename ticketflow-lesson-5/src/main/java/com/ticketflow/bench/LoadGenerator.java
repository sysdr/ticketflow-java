package com.ticketflow.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An open-loop load generator: buyers arrive on a fixed schedule whether or not
 * earlier buyers have been answered.
 *
 * <p>That one property is why this class exists instead of a loop of threads
 * that each send a request and wait. A send-and-wait loop slows down exactly
 * when the server does, so it can never push a box past its limit. A crowd on
 * release day does not wait its turn.
 */
public final class LoadGenerator {

    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    private final HttpClient http;
    private final String venueId;
    private final List<String> seatIds;
    private final int timeoutMillis;
    private final int maxInFlight;
    private final int sampleEvery;

    public LoadGenerator(String venueId, List<String> seatIds, int timeoutMillis, int maxInFlight, int sampleEvery) {
        if (seatIds.isEmpty() || timeoutMillis < 1 || maxInFlight < 1 || sampleEvery < 1) {
            throw new IllegalArgumentException("seatIds, timeoutMillis, maxInFlight and sampleEvery must be positive");
        }
        this.venueId = venueId;
        this.seatIds = List.copyOf(seatIds);
        this.timeoutMillis = timeoutMillis;
        this.maxInFlight = maxInFlight;
        this.sampleEvery = sampleEvery;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
    }

    HttpClient http() {
        return http;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Sends {@code rate} buyers per second for {@code seconds} seconds, spread
     * evenly across the targets, then waits for the stragglers.
     */
    public StepResult runStep(String runId, List<String> targets, int rate, int seconds, int budgetMillis) {
        LatencyRecorder recorder = new LatencyRecorder();
        AtomicInteger inFlight = new AtomicInteger();
        long offered = (long) rate * seconds;
        long intervalNanos = 1_000_000_000L / rate;
        long startNanos = System.nanoTime();
        long windowEndNanos = startNanos + seconds * 1_000_000_000L;

        for (long seq = 0; seq < offered; seq++) {
            long dueNanos = startNanos + seq * intervalNanos;
            sleepUntil(dueNanos);
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (inFlight.get() >= maxInFlight) {
                recorder.shed();
                continue;
            }
            String target = targets.get((int) (seq % targets.size()));
            send(runId, rate, seq, target, dueNanos, windowEndNanos, recorder, inFlight);
        }

        long giveUpNanos = System.nanoTime() + (timeoutMillis + 1_000L) * 1_000_000L;
        while (inFlight.get() > 0 && System.nanoTime() < giveUpNanos) {
            LockSupport.parkNanos(5_000_000L);
        }
        return recorder.toResult(rate, seconds, offered, budgetMillis);
    }

    private void send(String runId, int rate, long seq, String target, long dueNanos, long windowEndNanos,
                      LatencyRecorder recorder, AtomicInteger inFlight) {
        String requestId = String.format("%s-%d-%06d", runId, rate, seq);
        String seatId = seatIds.get(ThreadLocalRandom.current().nextInt(seatIds.size()));
        boolean traced = seq % sampleEvery == 0;
        String body = "{\"venueId\":\"" + venueId + "\",\"seatId\":\"" + seatId
                + "\",\"buyerId\":\"buyer-" + requestId + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(target + "/api/bookings"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", requestId)
                .header("X-Trace", traced ? "1" : "0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        if (traced) {
            log.info("event=buyer.sent request_id={} target={} seat={} offered_rps={}", requestId, target, seatId, rate);
        }
        inFlight.incrementAndGet();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            long now = System.nanoTime();
            long latencyNanos = now - dueNanos;
            boolean insideWindow = now <= windowEndNanos;
            String outcome;
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                if (cause instanceof HttpTimeoutException) {
                    recorder.timeout(latencyNanos);
                    outcome = "TIMEOUT";
                } else {
                    recorder.error(latencyNanos);
                    outcome = "ERROR:" + cause.getClass().getSimpleName();
                }
            } else if (response.statusCode() == 201) {
                recorder.booked(latencyNanos, insideWindow);
                outcome = "BOOKED";
            } else if (response.statusCode() == 409) {
                recorder.soldOut(latencyNanos, insideWindow);
                outcome = "SEAT_TAKEN";
            } else {
                recorder.error(latencyNanos);
                outcome = "HTTP_" + response.statusCode();
            }
            inFlight.decrementAndGet();
            if (traced) {
                log.info("event=buyer.answered request_id={} target={} outcome={} latency_ms={}",
                        requestId, target, outcome, latencyNanos / 1_000_000);
            }
        });
    }

    private static void sleepUntil(long dueNanos) {
        long remaining;
        while ((remaining = dueNanos - System.nanoTime()) > 0) {
            if (remaining > 200_000L) {
                LockSupport.parkNanos(remaining - 100_000L);
            } else {
                Thread.onSpinWait();
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }
}
