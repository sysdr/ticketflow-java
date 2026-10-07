package com.ticketflow.config;

import com.ticketflow.model.Seat;
import com.ticketflow.model.Venue;
import com.ticketflow.repository.SeatRepository;
import com.ticketflow.repository.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seeds the database with Riverside Hall and its 500 seats on first startup.
 *
 * Both instances share the same PostgreSQL database, so only one will actually
 * insert rows — the other will find the seat count already at 500 and skip.
 * This is a preview of the shared-state problem that Module 2 solves properly.
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String VENUE_NAME = "Riverside Hall";
    private static final int TOTAL_SEATS = 500;

    @Bean
    ApplicationRunner seedDatabase(VenueRepository venues, SeatRepository seats) {
        return args -> {
            if (venues.count() > 0) {
                log.info("Database already seeded — skipping");
                return;
            }

            Venue hall = venues.save(new Venue(VENUE_NAME, TOTAL_SEATS));
            log.info("Created venue: {} (id={})", hall.getName(), hall.getId());

            for (int row = 1; row <= 20; row++) {
                for (int col = 1; col <= 25; col++) {
                    String number = String.format("%c%02d", (char) ('A' + row - 1), col);
                    seats.save(new Seat(hall, number));
                }
            }

            log.info("Seeded {} seats for {}", TOTAL_SEATS, VENUE_NAME);
        };
    }
}
