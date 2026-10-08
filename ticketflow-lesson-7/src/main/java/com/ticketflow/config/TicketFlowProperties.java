package com.ticketflow.config;

import com.ticketflow.bench.RampPlan;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under the {@code ticketflow.*} prefix in application.yml. */
@ConfigurationProperties(prefix = "ticketflow")
public record TicketFlowProperties(Box box, Signing signing, Budget budget, Checkout checkout, Balancer balancer,
                                   Bench bench) {

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
     * The two-step checkout.
     *
     * @param sessions   {@code signed} (the default from lesson 7 on) or {@code server}; switchable at runtime
     * @param signingKey the HMAC key for signed sessions; every box must have the same one
     */
    public record Checkout(String sessions, String signingKey) {
    }

    /**
     * Load-generator settings.
     *
     * @param timeoutMillis how long a simulated buyer waits before giving up
     * @param maxInFlight   the most unanswered requests the bench will hold open at once
     * @param sampleEvery   one request in this many is traced in the logs of both sides
     * @param thinkMillis   in a two-step checkout, how long a buyer takes between holding a seat and paying
     * @param presets       the named experiments offered on the dashboard
     */
    public record Bench(int timeoutMillis, int maxInFlight, int sampleEvery, int thinkMillis, List<Preset> presets) {
    }

    /**
     * Load-balancer settings. A process with no backends is not a balancer.
     *
     * @param strategy      {@code round-robin} or {@code least-connections}; can be switched while running
     * @param timeoutMillis how long the balancer waits for a box before answering 502
     * @param backends      the boxes to spread requests across
     */
    public record Balancer(String strategy, int timeoutMillis, List<BackendSpec> backends) {
        public List<BackendSpec> backendsOrNone() {
            return backends == null ? List.of() : backends;
        }
    }

    public record BackendSpec(String name, String url) {
    }

    /**
     * A named experiment offered on the dashboard. See {@link RampPlan} for what the fields mean.
     *
     * @param group       presets in one group are compared on one chart
     * @param description one sentence shown under the group title
     */
    public record Preset(String name, String label, String group, String description, List<String> targets,
                         String balancer, String strategy, String checkout, RampPlan.Fault fault,
                         int startRate, int endRate, int stepRate, int stepSeconds) {
        public RampPlan toPlan() {
            return new RampPlan(label, group, targets, balancer, strategy, checkout, fault,
                    startRate, endRate, stepRate, stepSeconds);
        }
    }
}
