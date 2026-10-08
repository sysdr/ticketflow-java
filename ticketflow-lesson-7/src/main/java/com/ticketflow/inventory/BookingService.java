package com.ticketflow.inventory;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.Hold;
import com.ticketflow.faults.BoxBrokenException;
import com.ticketflow.faults.FaultSwitch;
import com.ticketflow.lifecycle.StageTimer;
import com.ticketflow.scaling.CpuPool;
import com.ticketflow.scaling.TicketSigner;

/**
 * The booking path: sign the offer, then try to take the seat.
 *
 * <p>Signing happens first, so every buyer costs CPU whether or not they win a
 * seat. That mirrors a real on-sale, where most of the work is done for people
 * who leave empty-handed.
 */
public final class BookingService {

    private final SeatMap seatMap;
    private final TicketSigner signer;
    private final CpuPool cpuPool;
    private final FaultSwitch faults;
    private final String node;

    public BookingService(SeatMap seatMap, TicketSigner signer, CpuPool cpuPool, FaultSwitch faults, String node) {
        this.seatMap = seatMap;
        this.signer = signer;
        this.cpuPool = cpuPool;
        this.faults = faults;
        this.node = node;
    }

    public Booking book(String seatId, String buyerId, StageTimer timer) {
        if (buyerId == null || buyerId.isBlank()) {
            throw new IllegalArgumentException("buyerId is required");
        }
        if (faults.failFast()) {
            throw new BoxBrokenException(node);
        }
        String venueId = seatMap.venue().id();
        int repeats = faults.signMultiplier();
        String ticketCode = cpuPool.run(() -> {
            String code = signer.sign(venueId, seatId, buyerId);
            for (int extra = 1; extra < repeats; extra++) {
                code = signer.sign(venueId, seatId, buyerId);
            }
            return code;
        }, timer);

        long started = System.nanoTime();
        try {
            Hold hold = seatMap.hold(seatId, buyerId);
            return seatMap.confirm(hold, ticketCode, node);
        } finally {
            timer.record("reserve", System.nanoTime() - started);
        }
    }

    public String node() {
        return node;
    }
}
