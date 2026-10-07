package com.ticketflow;

import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TicketFlow — Lesson 6 checkpoint.
 *
 * New in this lesson: two instances run behind NGINX. Each instance tags every
 * structured log line with its INSTANCE_ID env var so you can see distribution
 * in real time with: docker compose logs --tail=40 -f app-1 app-2
 */
@SpringBootApplication
public class TicketFlowApplication {

    @PostConstruct
    void tagInstance() {
        // INSTANCE_ID is set in docker-compose.yml per service.
        // Without it, every log line reads "unknown" — useful signal that the
        // env var wasn't injected correctly.
        String id = System.getenv().getOrDefault("INSTANCE_ID", "unknown");
        MDC.put("instanceId", id);
    }

    public static void main(String[] args) {
        SpringApplication.run(TicketFlowApplication.class, args);
    }
}
