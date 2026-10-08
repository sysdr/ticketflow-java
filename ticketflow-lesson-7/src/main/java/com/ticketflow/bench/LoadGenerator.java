package com.ticketflow.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * <p>Each buyer either makes one booking call (lessons 5 and 6) or, for a
 * two-step run (lesson 7), holds a seat, thinks for a moment, and then confirms
 * with the session it was given, as a person paying at checkout would.
 */
public final class LoadGenerator {

    private static final Pattern SESSION = Pattern.compile("\"session\"\\s*:\\s*\"([^\"]+)\"");

    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    private final HttpClient http;
    private final String venueId;
    private final List<String> seatIds;
    private final int timeoutMillis;
    private final int maxInFlight;
    private final int sampleEvery;
    private final int thinkMillis;
    private final ScheduledExecutorService thinking = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bench-think");
        thread.setDaemon(true);
        return thread;
    });

    public LoadGenerator(String venueId, List<String> seatIds, int timeoutMillis, int maxInFlight, int sampleEvery,
                         int thinkMillis) {
        if (seatIds.isEmpty() || timeoutMillis < 1 || maxInFlight < 1 || sampleEvery < 1 || thinkMillis < 0) {
            throw new IllegalArgumentException("seatIds, timeoutMillis, maxInFlight and sampleEvery must be positive");
        }
        this.thinkMillis = thinkMillis;
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

    /** One-shot bookings, as in lessons 5 and 6. */
    public StepResult runStep(String runId, List<String> targets, int rate, int seconds, int budgetMillis) {
        return runStep(runId, targets, rate, seconds, budgetMillis, false);
    }

    /**
     * Sends {@code rate} buyers per second for {@code seconds} seconds, spread
     * evenly across the targets, then waits for the stragglers.
     *
     * @param twoStep when true, each buyer does start, thinks, then confirm
     */
    public StepResult runStep(String runId, List<String> targets, int rate, int seconds, int budgetMillis,
                              boolean twoStep) {
        LatencyRecorder recorder = new LatencyRecorder();
        AtomicInteger inFlight = new AtomicInteger();
        long offered = (long) rate * seconds;
        long intervalNanos = 1_000_000_000L / rate;
        long startNanos = System.nanoTime();
        // A two-step buyer's answer comes a think-time later; count it if the journey started inside the window.
        long windowEndNanos = startNanos + seconds * 1_000_000_000L + (twoStep ? thinkMillis * 1_000_000L : 0);

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
            if (twoStep) {
                startCheckout(runId, rate, seq, target, dueNanos, windowEndNanos, recorder, inFlight);
            } else {
                send(runId, rate, seq, target, dueNanos, windowEndNanos, recorder, inFlight);
            }
        }

        long giveUpNanos = System.nanoTime() + (2L * timeoutMillis + thinkMillis + 1_000L) * 1_000_000L;
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

    /** Step 1 of a two-step buyer: hold a seat and keep the session that comes back. */
    private void startCheckout(String runId, int rate, long seq, String target, long dueNanos, long windowEndNanos,
                               LatencyRecorder recorder, AtomicInteger inFlight) {
        String buyerKey = String.format("%s-%d-%06d", runId, rate, seq);
        String seatId = seatIds.get(ThreadLocalRandom.current().nextInt(seatIds.size()));
        boolean traced = seq % sampleEvery == 0;
        String body = "{\"venueId\":\"" + venueId + "\",\"seatId\":\"" + seatId
                + "\",\"buyerId\":\"buyer-" + buyerKey + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(target + "/api/checkout/start"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", buyerKey + ".start")
                .header("X-Trace", traced ? "1" : "0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        if (traced) {
            log.info("event=buyer.start request_id={}.start target={} seat={} offered_rps={}", buyerKey, target, seatId, rate);
        }
        inFlight.incrementAndGet();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, failure) -> {
            long startNanos = System.nanoTime() - dueNanos;
            if (failure != null) {
                recordFailure(failure, startNanos, recorder);
                finishJourney(buyerKey, traced, failure.getClass().getSimpleName(), startNanos, inFlight);
                return;
            }
            if (response.statusCode() == 409) {
                recorder.soldOut(startNanos, System.nanoTime() <= windowEndNanos);
                finishJourney(buyerKey, traced, "SEAT_TAKEN_AT_START", startNanos, inFlight);
                return;
            }
            Matcher session = SESSION.matcher(response.body());
            if (response.statusCode() != 201 || !session.find()) {
                recorder.error(startNanos);
                finishJourney(buyerKey, traced, "START_HTTP_" + response.statusCode(), startNanos, inFlight);
                return;
            }
            String route = response.headers().firstValue("X-Route").orElse(null);
            if (traced) {
                log.info("event=buyer.holding request_id={}.start session_kind={} route={} think_ms={}", buyerKey,
                        session.group(1).contains(".") ? "signed" : "server", route == null ? "-" : route, thinkMillis);
            }
            thinking.schedule(() -> confirmCheckout(buyerKey, target, session.group(1), route, traced, startNanos,
                    windowEndNanos, recorder, inFlight), thinkMillis, TimeUnit.MILLISECONDS);
        });
    }

    /** Step 2 of a two-step buyer: come back with the session and pay. */
    private void confirmCheckout(String buyerKey, String target, String session, String route, boolean traced,
                                 long startNanos, long windowEndNanos, LatencyRecorder recorder, AtomicInteger inFlight) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target + "/api/checkout/confirm"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", buyerKey + ".confirm")
                .header("X-Trace", traced ? "1" : "0")
                .header("X-Checkout-Session", session)
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (route != null) {
            request.header("X-Route", route);
        }
        long sentNanos = System.nanoTime();
        http.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            long now = System.nanoTime();
            long latencyNanos = startNanos + (now - sentNanos);
            boolean insideWindow = now <= windowEndNanos;
            String outcome;
            if (failure != null) {
                outcome = recordFailure(failure, latencyNanos, recorder);
            } else if (response.statusCode() == 201) {
                recorder.booked(latencyNanos, insideWindow);
                outcome = "BOOKED";
            } else if (response.statusCode() == 409) {
                recorder.soldOut(latencyNanos, insideWindow);
                outcome = "SEAT_TAKEN";
            } else if (response.statusCode() == 401) {
                recorder.sessionLost(latencyNanos);
                outcome = "SESSION_LOST";
            } else {
                recorder.error(latencyNanos);
                outcome = "HTTP_" + response.statusCode();
            }
            finishJourney(buyerKey, traced, outcome, latencyNanos, inFlight);
        });
    }

    private static String recordFailure(Throwable failure, long latencyNanos, LatencyRecorder recorder) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof HttpTimeoutException) {
            recorder.timeout(latencyNanos);
            return "TIMEOUT";
        }
        recorder.error(latencyNanos);
        return "ERROR:" + cause.getClass().getSimpleName();
    }

    private static void finishJourney(String buyerKey, boolean traced, String outcome, long latencyNanos,
                                      AtomicInteger inFlight) {
        inFlight.decrementAndGet();
        if (traced) {
            log.info("event=buyer.answered request_id={} outcome={} latency_ms={}", buyerKey, outcome,
                    latencyNanos / 1_000_000);
        }
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
