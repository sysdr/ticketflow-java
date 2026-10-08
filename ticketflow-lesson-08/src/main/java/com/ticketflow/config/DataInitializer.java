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
 * Seeds the database on first startup.
 *
 * Guard: venueRepository.count() == 0 — safe to call repeatedly; idempotent.
 *
 * Creates "Riverside Hall" with 500 seats arranged as 20 rows (A–T) × 25 cols (01–25).
 * After Day 8, these seats persist across restarts — the whole point of this lesson.
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    ApplicationRunner seedDatabase(VenueRepository venueRepo, SeatRepository seatRepo) {
        return args -> {
            if (venueRepo.count() > 0) {
                log.info("Database already seeded — skipping");
                return;
            }

            log.info("Seeding database: creating Riverside Hall with 500 seats");
            Venue venue = venueRepo.save(new Venue("Riverside Hall", 500));

            for (char row = 'A'; row <= 'T'; row++) {        // 20 rows
                for (int col = 1; col <= 25; col++) {         // 25 columns
                    String seatNum = String.format("%c%02d", row, col);
                    seatRepo.save(new Seat(venue, seatNum));
                }
            }
            log.info("Seed complete: venue.id={}, seats=500", venue.getId());
        };
    }
}
