package com.ticketflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * One TicketFlow process. In lesson 5 the same jar plays two parts depending on
 * how it is started: a "box" that sells seats, or the "bench" that sends buyers
 * at one or more boxes and draws what happened.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TicketFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketFlowApplication.class, args);
    }
}
