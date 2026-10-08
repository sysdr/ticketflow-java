package com.ticketflow.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts INSTANCE_ID into MDC on every request thread.
 * {@code @PostConstruct} MDC.put only affects the startup thread — Tomcat
 * worker threads would otherwise leave processedBy as "unknown".
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceIdFilter extends OncePerRequestFilter {

    private final String instanceId =
            System.getenv().getOrDefault("INSTANCE_ID", "unknown");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        MDC.put("instanceId", instanceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("instanceId");
        }
    }
}
