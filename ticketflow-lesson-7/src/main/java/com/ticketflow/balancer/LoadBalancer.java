package com.ticketflow.balancer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small HTTP load balancer: pick a box, forward the request, pass the answer back.
 *
 * <p>It is here so you can read every line of the decision. In production use
 * a proven one (NGINX, HAProxy, Envoy, or your cloud's) and keep what you learn
 * here for choosing its settings.
 */
public final class LoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancer.class);

    /** What came back from the chosen box, or a 502 made here if nothing did. */
    public record Forwarded(int status, String contentType, String serverTiming, byte[] body,
                            String backend, double millis) {
    }

    public record BackendView(String name, String url, int inFlight, long sent, long failed) {
    }

    private final List<Backend> backends;
    private final Duration timeout;
    private final HttpClient http;
    private final Object lock = new Object();
    private Strategy strategy;

    public LoadBalancer(List<Backend> backends, String strategyName, int timeoutMillis) {
        this.backends = List.copyOf(backends);
        this.strategy = Strategy.named(strategyName);
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
    }

    /** False when this process was started without any backends, i.e. it is not acting as a balancer. */
    public boolean enabled() {
        return !backends.isEmpty();
    }

    public String strategy() {
        synchronized (lock) {
            return strategy.name();
        }
    }

    public void useStrategy(String name) {
        Strategy replacement = Strategy.named(name);
        synchronized (lock) {
            strategy = replacement;
        }
        log.info("event=lb.strategy strategy={}", replacement.name());
    }

    public void resetCounters() {
        backends.forEach(Backend::resetCounters);
    }

    public List<BackendView> snapshot() {
        List<BackendView> views = new ArrayList<>();
        for (Backend backend : backends) {
            views.add(new BackendView(backend.name(), backend.url(), backend.inFlight(), backend.sent(), backend.failed()));
        }
        return views;
    }

    /** Forwards one request with no routing hint. */
    public CompletableFuture<Forwarded> forward(String method, String pathAndQuery, String contentType,
                                                String requestId, boolean traced, byte[] body) {
        return forward(method, pathAndQuery, contentType, requestId, traced, body, null, null);
    }

    /**
     * Forwards one request to whichever box the current strategy picks.
     *
     * @param traced    when true, the decision and the answer are logged with the request id
     * @param routeHint the box named in the client's {@code X-Route} header, or null
     * @param session   the client's {@code X-Checkout-Session} header, passed through untouched, or null
     */
    public CompletableFuture<Forwarded> forward(String method, String pathAndQuery, String contentType,
                                                String requestId, boolean traced, byte[] body,
                                                String routeHint, String session) {
        if (!enabled()) {
            throw new IllegalStateException("this process has no backends configured");
        }
        Backend backend;
        String strategyName;
        String counts = null;
        synchronized (lock) {
            if (traced) {
                counts = inFlightCounts();
            }
            backend = strategy.pick(backends, routeHint);
            backend.started();
            strategyName = strategy.name();
        }
        if (traced) {
            log.info("event=lb.forwarded request_id={} strategy={} backend={} route_hint={} in_flight_before={}",
                    requestId, strategyName, backend.name(), routeHint == null ? "-" : routeHint, counts);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(backend.url() + pathAndQuery))
                .timeout(timeout)
                .header("X-Request-Id", requestId)
                .header("X-Trace", traced ? "1" : "0");
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (session != null) {
            request.header("X-Checkout-Session", session);
        }
        byte[] payload = body == null ? new byte[0] : body;
        request.method(method, payload.length == 0
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));

        long started = System.nanoTime();
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, failure) -> {
                    double millis = (System.nanoTime() - started) / 1_000_000.0;
                    Forwarded forwarded;
                    if (failure != null) {
                        String reason = failure.getCause() != null
                                ? failure.getCause().getClass().getSimpleName() : failure.getClass().getSimpleName();
                        byte[] error = ("{\"error\":\"BAD_GATEWAY\",\"message\":\"" + backend.name()
                                + " did not answer: " + reason + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        forwarded = new Forwarded(502, "application/json", null, error, backend.name(), millis);
                    } else {
                        forwarded = new Forwarded(response.statusCode(),
                                response.headers().firstValue("Content-Type").orElse(null),
                                response.headers().firstValue("Server-Timing").orElse(null),
                                response.body(), backend.name(), millis);
                    }
                    backend.finished(forwarded.status() < 500);
                    if (traced) {
                        log.info("event=lb.answered request_id={} backend={} status={} lb_ms={}", requestId,
                                backend.name(), forwarded.status(), String.format(Locale.ROOT, "%.1f", millis));
                    }
                    return forwarded;
                });
    }

    private String inFlightCounts() {
        StringBuilder counts = new StringBuilder();
        for (Backend backend : backends) {
            if (counts.length() > 0) {
                counts.append(',');
            }
            counts.append(backend.name()).append(':').append(backend.inFlight());
        }
        return counts.toString();
    }
}
