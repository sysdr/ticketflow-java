package com.ticketflow.inventory;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.Hold;
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
    private final String node;

    public BookingService(SeatMap seatMap, TicketSigner signer, CpuPool cpuPool, String node) {
        this.seatMap = seatMap;
        this.signer = signer;
        this.cpuPool = cpuPool;
        this.node = node;
    }

    public Booking book(String seatId, String buyerId, StageTimer timer) {
        if (buyerId == null || buyerId.isBlank()) {
            throw new IllegalArgumentException("buyerId is required");
        }
        String venueId = seatMap.venue().id();
        String ticketCode = cpuPool.run(() -> signer.sign(venueId, seatId, buyerId), timer);

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
