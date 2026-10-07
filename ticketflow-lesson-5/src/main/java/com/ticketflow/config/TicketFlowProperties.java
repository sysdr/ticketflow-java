package com.ticketflow.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under the {@code ticketflow.*} prefix in application.yml. */
@ConfigurationProperties(prefix = "ticketflow")
public record TicketFlowProperties(Box box, Signing signing, Budget budget, Bench bench) {

    /**
     * The size of this box.
     *
     * @param node  the name this process stamps on every booking and log line
     * @param cores how many threads may do CPU work at once; 0 means "every core the JVM can see"
     */
    public record Box(String node, int cores) {
        public int effectiveCores() {
            return cores > 0 ? cores : Runtime.getRuntime().availableProcessors();
        }
    }

    /**
     * The CPU cost of signing one ticket offer.
     *
     * @param targetMillis CPU milliseconds one signature should cost; used to calibrate at startup
     * @param rounds       fixed hash rounds; when above 0 it overrides calibration
     */
    public record Signing(int targetMillis, int rounds) {
    }

    /** The latency budget for one booking request, carried over from lesson 4. */
    public record Budget(int totalMillis) {
    }

    /**
     * Load-generator settings.
     *
     * @param timeoutMillis how long a simulated buyer waits before giving up
     * @param maxInFlight   the most unanswered requests the bench will hold open at once
     * @param sampleEvery   one request in this many is traced in the logs of both sides
     * @param presets       the named experiments offered on the dashboard
     */
    public record Bench(int timeoutMillis, int maxInFlight, int sampleEvery, List<Preset> presets) {
    }

    /** A named experiment: which boxes the buyers are sent to. */
    public record Preset(String name, String label, List<String> targets) {
    }
}
