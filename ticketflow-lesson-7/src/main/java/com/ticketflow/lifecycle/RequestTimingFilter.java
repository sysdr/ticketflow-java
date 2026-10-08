package com.ticketflow.lifecycle;

import com.ticketflow.config.TicketFlowProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every API request an id and a stage timer (lesson 4), and for booking
 * requests (one-shot, or either step of the two-step checkout) keeps count of
 * how many are in the building and whether each one was answered inside the
 * latency budget (lesson 5).
 *
 * <p>Requests marked {@code X-Trace: 0} are counted but not logged. The bench
 * marks all but a sample that way, because writing a log line per request is
 * itself work the box would have to do while it is being measured.
 */
@Component
public class RequestTimingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTimingFilter.class);

    private final InFlightGauge gauge;
    private final String node;
    private final int budgetMillis;
    private final AtomicLong localIds = new AtomicLong();

    private static final Set<String> BOOKING_PATHS = Set.of("/api/bookings", "/api/checkout/start", "/api/checkout/confirm");

    public RequestTimingFilter(InFlightGauge gauge, TicketFlowProperties properties) {
        this.gauge = gauge;
        this.node = properties.box().node();
        this.budgetMillis = properties.budget().totalMillis();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied == null || supplied.isBlank()
                ? node + "-" + localIds.incrementAndGet() : supplied;
        StageTimer timer = new StageTimer();
        request.setAttribute(StageTimer.ATTRIBUTE, timer);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Node", node);

        String path = request.getRequestURI();
        boolean booking = "POST".equals(request.getMethod()) && BOOKING_PATHS.contains(path);
        boolean traced = booking && !"0".equals(request.getHeader("X-Trace"));
        if (booking) {
            gauge.arrived();
        }
        if (traced) {
            log.info("event=booking.received request_id={} node={} path={} in_flight={}", requestId, node, path, gauge.inFlight());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (booking) {
                double total = timer.totalMillis();
                boolean withinBudget = total <= budgetMillis;
                gauge.answered(withinBudget);
                if (traced) {
                    log.info("event=booking.answered request_id={} node={} path={} status={} queue_ms={} sign_ms={} "
                                    + "reserve_ms={} total_ms={} budget_ms={} budget={}",
                            requestId, node, path, response.getStatus(), fmt(timer.millisOf("queue")),
                            fmt(timer.millisOf("sign")), fmt(timer.millisOf("reserve")), fmt(total),
                            budgetMillis, withinBudget ? "OK" : "BLOWN");
                }
            }
        }
    }

    private static String fmt(double millis) {
        return String.format(Locale.ROOT, "%.1f", millis);
    }
}
