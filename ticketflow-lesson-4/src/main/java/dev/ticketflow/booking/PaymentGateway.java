package dev.ticketflow.booking;

import java.util.concurrent.atomic.AtomicLong;

import dev.ticketflow.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the remote payment provider. A real charge is a blocking call to someone else's
 * network, so this one blocks the calling thread for a configurable time. It becomes a real
 * service of its own when TicketFlow is split up in Module 3.
 */
@Component
public class PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger("PAYMENT");

    private final AtomicLong latencyMs;

    public PaymentGateway(@Value("${ticketflow.payment.latency-ms:120}") long latencyMs) {
        this.latencyMs = new AtomicLong(latencyMs);
    }

    public void charge(String holdId, String buyer) {
        long start = System.nanoTime();
        try {
            log.info("event=charge_start hold={} buyer={} latencyMs={}", holdId, buyer, latencyMs.get());
            Thread.sleep(latencyMs.get());
            log.info("event=charge_ok hold={}", holdId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while charging", e);
        } finally {
            TraceContext.add("payment", System.nanoTime() - start);
        }
    }

    public long latencyMs() { return latencyMs.get(); }

    public void setLatencyMs(long ms) { latencyMs.set(Math.max(0, Math.min(5_000, ms))); }
}
