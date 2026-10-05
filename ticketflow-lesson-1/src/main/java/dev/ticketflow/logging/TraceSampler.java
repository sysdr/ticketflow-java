package dev.ticketflow.logging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides which request ids get a full trace (a log line in every component).
 * Request ids look like "b-00100"; every Nth buyer is traced end to end.
 * The decision depends only on the id, so the client, the edge, and the booking
 * service all agree without sharing any state.
 */
@Component
public final class TraceSampler {
    private final int every;

    public TraceSampler(@Value("${ticketflow.log.sample-every:100}") int every) {
        this.every = Math.max(1, every);
    }

    public boolean traced(String requestId) {
        int dash = requestId.lastIndexOf('-');
        try {
            return Integer.parseInt(requestId.substring(dash + 1)) % every == 0;
        } catch (RuntimeException notANumber) {
            return false;
        }
    }
}
