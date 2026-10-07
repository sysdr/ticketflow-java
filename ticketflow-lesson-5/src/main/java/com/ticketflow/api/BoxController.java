package com.ticketflow.api;

import com.ticketflow.config.TicketFlowProperties;
import com.ticketflow.inventory.SeatMap;
import com.ticketflow.lifecycle.InFlightGauge;
import com.ticketflow.scaling.CpuPool;
import com.ticketflow.scaling.TicketSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets you look inside one box: how big it is and how far behind it is. */
@RestController
@RequestMapping("/api")
public class BoxController {

    private static final Logger log = LoggerFactory.getLogger(BoxController.class);

    /**
     * @param inFlight   booking requests received and not yet answered
     * @param queueDepth of those, how many are waiting for a core
     * @param overBudget answers that took longer than the latency budget
     */
    public record BoxStatus(String node, int cores, int busyCores, int queueDepth, int inFlight,
                            long answered, long overBudget, int seatsBooked, int seatsTotal,
                            int signRounds, int budgetMillis) {
    }

    private final TicketFlowProperties properties;
    private final CpuPool cpuPool;
    private final InFlightGauge gauge;
    private final SeatMap seatMap;
    private final TicketSigner signer;

    public BoxController(TicketFlowProperties properties, CpuPool cpuPool, InFlightGauge gauge,
                         SeatMap seatMap, TicketSigner signer) {
        this.properties = properties;
        this.cpuPool = cpuPool;
        this.gauge = gauge;
        this.seatMap = seatMap;
        this.signer = signer;
    }

    @GetMapping("/box")
    public BoxStatus box() {
        return new BoxStatus(properties.box().node(), cpuPool.cores(), cpuPool.busy(), cpuPool.queueDepth(),
                gauge.inFlight(), gauge.answered(), gauge.overBudget(), seatMap.bookedCount(),
                seatMap.venue().capacity(), signer.rounds(), properties.budget().totalMillis());
    }

    /** Empties the hall and zeroes the counters so the next experiment starts clean. */
    @PostMapping("/admin/reset")
    public BoxStatus reset() {
        seatMap.reset();
        gauge.reset();
        log.info("event=box.reset node={} seats_free={}", properties.box().node(), seatMap.venue().capacity());
        return box();
    }
}
