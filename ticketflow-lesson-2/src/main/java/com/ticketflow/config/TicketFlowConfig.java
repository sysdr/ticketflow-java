package com.ticketflow.config;

import com.ticketflow.domain.Venue;
import com.ticketflow.observability.RecordingEventSink;
import com.ticketflow.service.InventoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires the plain-Java domain and service classes into Spring. They stay free of Spring imports. */
@Configuration
public class TicketFlowConfig {

    @Bean
    public DemoClock demoClock() {
        return new DemoClock();
    }

    @Bean
    public Venue venue(@Value("${ticketflow.venue.rows:5}") int rows,
                       @Value("${ticketflow.venue.seats-per-row:10}") int seatsPerRow) {
        return Venue.grid("aurora-hall", "Aurora Hall", rows, seatsPerRow);
    }

    @Bean
    public InventoryService inventoryService(Venue venue,
                                             DemoClock clock,
                                             RecordingEventSink sink,
                                             @Value("${ticketflow.hold-ttl-seconds:120}") long holdTtlSeconds) {
        return new InventoryService(venue, clock, Duration.ofSeconds(holdTtlSeconds), sink);
    }
}
