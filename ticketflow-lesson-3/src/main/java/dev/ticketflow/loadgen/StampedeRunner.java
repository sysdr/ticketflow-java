package dev.ticketflow.loadgen;

import dev.ticketflow.inventory.SeatInventory;
import dev.ticketflow.logging.TraceSampler;
import dev.ticketflow.metrics.ServerStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Starts one stampede at a time against this same server, over real HTTP on localhost. */
@Component
public class StampedeRunner {
    private final TraceSampler sampler;
    private final ServerStats stats;
    private final SeatInventory inventory;
    private final int port;
    private volatile Stampede current;

    public StampedeRunner(TraceSampler sampler, ServerStats stats, SeatInventory inventory,
                          @Value("${server.port:8080}") int port) {
        this.sampler = sampler;
        this.stats = stats;
        this.inventory = inventory;
        this.port = port;
    }

    public synchronized boolean start(int buyers, long windowMs, long patienceMs) {
        if (current != null && current.running()) {
            return false;
        }
        inventory.reset();
        stats.reset();
        Stampede next = new Stampede("http://localhost:" + port, buyers, windowMs, patienceMs, sampler);
        current = next;
        Thread.ofPlatform().name("stampede").start(next::run);
        return true;
    }

    public synchronized boolean reset() {
        if (current != null && current.running()) {
            return false;
        }
        inventory.reset();
        stats.reset();
        current = null;
        return true;
    }

    public String toJson() {
        Stampede c = current;
        return c == null ? "{\"state\":\"idle\"}" : c.toJson();
    }
}
