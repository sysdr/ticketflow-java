package com.ticketflow;

import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TicketFlow entry point — Lesson 8.
 *
 * Tags every log line with the instance ID so distributed log queries
 * can trace which node processed a request.
 */
@SpringBootApplication
public class TicketFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketFlowApplication.class, args);
    }

    @PostConstruct
    void tagInstance() {
        String id = System.getenv().getOrDefault("INSTANCE_ID", "local");
        MDC.put("instanceId", id);
        // Note: MDC is thread-local; tagInstance() runs on the main thread.
        // Virtual threads (Lesson 15) will need the MDC value propagated
        // explicitly. Leave that for Day 15.
    }
}
