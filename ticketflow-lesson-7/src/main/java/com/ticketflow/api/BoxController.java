package com.ticketflow.api;

import com.ticketflow.checkout.CheckoutService;
import com.ticketflow.config.TicketFlowProperties;
import com.ticketflow.faults.FaultSwitch;
import com.ticketflow.inventory.SeatMap;
import com.ticketflow.lifecycle.InFlightGauge;
import com.ticketflow.scaling.CpuPool;
import com.ticketflow.scaling.TicketSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
     * @param fault      the fault currently switched on: none, slow xN, or fail-fast
     * @param sessions   the kind of checkout session this box hands out: server or signed
     * @param liveSessions server-side sessions held in this box's memory right now
     */
    public record BoxStatus(String node, int cores, int busyCores, int queueDepth, int inFlight,
                            long answered, long overBudget, int seatsBooked, int seatsTotal,
                            int signRounds, int budgetMillis, String fault, String sessions, int liveSessions) {
    }

    private final TicketFlowProperties properties;
    private final CpuPool cpuPool;
    private final InFlightGauge gauge;
    private final SeatMap seatMap;
    private final TicketSigner signer;
    private final FaultSwitch faults;
    private final CheckoutService checkout;

    public BoxController(TicketFlowProperties properties, CpuPool cpuPool, InFlightGauge gauge,
                         SeatMap seatMap, TicketSigner signer, FaultSwitch faults, CheckoutService checkout) {
        this.properties = properties;
        this.cpuPool = cpuPool;
        this.gauge = gauge;
        this.seatMap = seatMap;
        this.signer = signer;
        this.faults = faults;
        this.checkout = checkout;
    }

    @GetMapping("/box")
    public BoxStatus box() {
        return new BoxStatus(properties.box().node(), cpuPool.cores(), cpuPool.busy(), cpuPool.queueDepth(),
                gauge.inFlight(), gauge.answered(), gauge.overBudget(), seatMap.bookedCount(),
                seatMap.venue().capacity(), signer.rounds(), properties.budget().totalMillis(), faults.describe(),
                checkout.sessionKind(), checkout.liveSessionsInMemory());
    }

    /** Empties the hall, forgets every session, zeroes the counters and switches off any fault. */
    @PostMapping("/admin/reset")
    public BoxStatus reset() {
        checkout.forgetEverythingInMemory();
        gauge.reset();
        faults.clear();
        log.info("event=box.reset node={} seats_free={}", properties.box().node(), seatMap.venue().capacity());
        return box();
    }

    /** Switches the kind of checkout session this box hands out: {@code server} or {@code signed}. */
    @PostMapping("/admin/checkout/{kind}")
    public BoxStatus useSessions(@PathVariable String kind) {
        checkout.use(kind);
        log.info("event=box.checkout node={} sessions={}", properties.box().node(), checkout.sessionKind());
        return box();
    }

    /**
     * Does to this box's memory what a restart would: every server-side session, hold and booking is gone.
     * The process, its counters and any fault stay, so a run can carry on measuring through it.
     * To see the real thing, restart the process instead (README, "Restart a box for real").
     */
    @PostMapping("/admin/restart")
    public BoxStatus restart() {
        int lost = checkout.liveSessionsInMemory();
        checkout.forgetEverythingInMemory();
        log.info("event=box.restart node={} server_sessions_lost={}", properties.box().node(), lost);
        return box();
    }

    /**
     * Makes this box misbehave on purpose. Modes: {@code none}, {@code slow} (with {@code factor}), {@code fail-fast}.
     */
    @PostMapping("/admin/fault/{mode}")
    public BoxStatus fault(@PathVariable String mode, @RequestParam(defaultValue = "3") int factor) {
        faults.set(FaultSwitch.Mode.parse(mode), factor);
        log.info("event=box.fault node={} fault=\"{}\"", properties.box().node(), faults.describe());
        return box();
    }
}
