package com.ticketflow.api;

import com.ticketflow.balancer.LoadBalancer;
import com.ticketflow.config.TicketFlowProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The balancer's front door. Anything sent to {@code /lb/...} is forwarded to
 * one of the boxes as {@code /...}; for example {@code POST /lb/api/bookings}
 * becomes {@code POST /api/bookings} on whichever box the strategy picks.
 */
@RestController
public class BalancerController {

    private static final String PREFIX = "/lb";

    public record BalancerStatus(String node, String strategy, List<LoadBalancer.BackendView> backends) {
    }

    private final LoadBalancer balancer;
    private final String node;
    private final AtomicLong localIds = new AtomicLong();

    public BalancerController(LoadBalancer balancer, TicketFlowProperties properties) {
        this.balancer = balancer;
        this.node = properties.box().node();
    }

    @RequestMapping(PREFIX + "/**")
    public CompletableFuture<ResponseEntity<byte[]>> proxy(HttpServletRequest request,
                                                           @RequestBody(required = false) byte[] body) {
        if (!balancer.enabled()) {
            byte[] error = "{\"error\":\"NOT_A_BALANCER\",\"message\":\"this process has no backends configured\"}"
                    .getBytes(StandardCharsets.UTF_8);
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(503).header("Content-Type", "application/json").body(error));
        }
        String path = request.getRequestURI().substring(PREFIX.length());
        String pathAndQuery = request.getQueryString() == null ? path : path + "?" + request.getQueryString();
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied == null || supplied.isBlank() ? node + "-" + localIds.incrementAndGet() : supplied;
        boolean traced = !"0".equals(request.getHeader("X-Trace"));

        return balancer.forward(request.getMethod(), pathAndQuery, request.getContentType(), requestId, traced, body,
                        request.getHeader("X-Route"), request.getHeader("X-Checkout-Session"))
                .thenApply(forwarded -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.status(forwarded.status())
                            .header("X-Request-Id", requestId)
                            .header("X-Backend", forwarded.backend())
                            // What a sticky balancer's cookie would carry: send it back to stay on this box.
                            .header("X-Route", forwarded.backend());
                    if (forwarded.contentType() != null) {
                        response.header("Content-Type", forwarded.contentType());
                    }
                    // The balancer is one more hop in the lesson 4 budget, so it adds its own entry.
                    String hop = "lb;dur=" + String.format(Locale.ROOT, "%.1f", forwarded.millis());
                    response.header("Server-Timing",
                            forwarded.serverTiming() == null ? hop : forwarded.serverTiming() + ", " + hop);
                    return response.body(forwarded.body());
                });
    }

    @GetMapping("/api/balancer")
    public BalancerStatus status() {
        return new BalancerStatus(node, balancer.strategy(), balancer.snapshot());
    }

    @PostMapping("/api/balancer/strategy/{name}")
    public BalancerStatus useStrategy(@PathVariable String name) {
        balancer.useStrategy(name);
        return status();
    }

    /** Zeroes the sent and failed counters. In-flight counts are live and are never reset. */
    @PostMapping("/api/balancer/reset")
    public BalancerStatus reset() {
        balancer.resetCounters();
        return status();
    }
}
