package com.ticketflow.observability;

import com.ticketflow.service.EventSink;
import com.ticketflow.service.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every API request an id (or keeps the caller's X-Request-Id), makes it visible to
 * every layer underneath, echoes it back in the response header, and reports the start and
 * end of any request that changes something. Read-only polling is left out to keep the
 * trace readable.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    private final RecordingEventSink sink;

    public RequestIdFilter(RecordingEventSink sink) {
        this.sink = sink;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String rid = (incoming != null && !incoming.isBlank()) ? incoming : "r-" + UUID.randomUUID().toString().substring(0, 8);

        RequestIds.set(rid);
        MDC.put("rid", rid);
        response.setHeader(HEADER, rid);

        boolean mutating = !"GET".equals(request.getMethod());
        long started = System.nanoTime();
        try {
            if (mutating) {
                sink.emit("api", "request.received", EventSink.fields(
                        "method", request.getMethod(), "path", request.getRequestURI()));
            }
            chain.doFilter(request, response);
        } finally {
            if (mutating) {
                sink.emit("api", "request.completed", EventSink.fields(
                        "status", response.getStatus(), "ms", (System.nanoTime() - started) / 1_000_000));
            }
            MDC.remove("rid");
            RequestIds.clear();
        }
    }
}
