package com.ticketflow.config;

import com.ticketflow.bench.BenchService;
import com.ticketflow.bench.LoadGenerator;
import com.ticketflow.domain.Venue;
import com.ticketflow.inventory.BookingService;
import com.ticketflow.inventory.SeatMap;
import com.ticketflow.lifecycle.InFlightGauge;
import com.ticketflow.scaling.CpuPool;
import com.ticketflow.scaling.TicketSigner;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the plain-Java core of TicketFlow into the Spring context. */
@Configuration
public class TicketFlowConfig {

    private static final Logger log = LoggerFactory.getLogger(TicketFlowConfig.class);

    @Bean
    public Venue venue() {
        return new Venue("riverside", "Riverside Hall", 20, 25);
    }

    @Bean
    public SeatMap seatMap(Venue venue) {
        return new SeatMap(venue, Clock.systemUTC());
    }

    @Bean(destroyMethod = "close")
    public CpuPool cpuPool(TicketFlowProperties properties) {
        int cores = properties.box().effectiveCores();
        log.info("event=box.sized node={} cores={}", properties.box().node(), cores);
        return new CpuPool(cores);
    }

    @Bean
    public TicketSigner ticketSigner(TicketFlowProperties properties) {
        TicketFlowProperties.Signing signing = properties.signing();
        TicketSigner signer = signing.rounds() > 0
                ? TicketSigner.withRounds(signing.rounds())
                : TicketSigner.calibrated(signing.targetMillis());
        log.info("event=signer.ready node={} rounds={} target_cpu_ms={}",
                properties.box().node(), signer.rounds(), signing.targetMillis());
        return signer;
    }

    @Bean
    public InFlightGauge inFlightGauge() {
        return new InFlightGauge();
    }

    @Bean
    public BookingService bookingService(SeatMap seatMap, TicketSigner signer, CpuPool cpuPool,
                                         TicketFlowProperties properties) {
        return new BookingService(seatMap, signer, cpuPool, properties.box().node());
    }

    @Bean(destroyMethod = "close")
    public BenchService benchService(Venue venue, SeatMap seatMap, TicketFlowProperties properties) {
        TicketFlowProperties.Bench bench = properties.bench();
        LoadGenerator generator = new LoadGenerator(
                venue.id(), seatMap.seatIds(), bench.timeoutMillis(), bench.maxInFlight(), bench.sampleEvery());
        return new BenchService(generator, venue.id(), properties.budget().totalMillis());
    }
}
