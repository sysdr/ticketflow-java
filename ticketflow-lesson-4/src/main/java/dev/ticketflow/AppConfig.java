package dev.ticketflow;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

    /** Time is an input, not a global: tests hand the model a clock they can move. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
