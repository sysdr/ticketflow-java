package dev.ticketflow.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import dev.ticketflow.domain.SeatId;
import dev.ticketflow.inventory.EventLog;
import dev.ticketflow.inventory.VenueService;
import org.junit.jupiter.api.Test;

class RequestTraceTest {

    @Test
    void stagesAccumulateAndRenderAsServerTiming() {
        RequestTrace trace = TraceContext.begin(300);
        try {
            trace.add("payment", 120_000_000L);
            trace.add("payment", 5_000_000L);
            assertThat(trace.ms("payment")).isEqualTo(125.0);
            assertThat(trace.serverTiming()).startsWith("lock;dur=0.00, domain;dur=0.00, payment;dur=125.00")
                    .contains("left;dur=").contains("desc=\"budget left\"");
        } finally {
            TraceContext.end();
        }
    }

    @Test
    void theVenueReportsLockAndDomainTimeOnceEvenWhenCallsNest() {
        VenueService venue = new VenueService(Clock.systemUTC(), new EventLog(), 30, 20, 25);
        RequestTrace trace = TraceContext.begin(0);
        try {
            long start = System.nanoTime();
            venue.bookFirstAvailable("sam");
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;

            assertThat(trace.ms("domain")).isGreaterThan(0);
            assertThat(trace.ms("lock") + trace.ms("domain")).isLessThanOrEqualTo(elapsedMs);
        } finally {
            TraceContext.end();
        }
    }

    @Test
    void nothingIsRecordedWhenNoRequestIsBeingTraced() {
        VenueService venue = new VenueService(Clock.systemUTC(), new EventLog(), 30, 20, 25);
        venue.placeHold(new SeatId("A-01"), "sam");
        TraceContext.add("payment", 1);   // must not throw
    }
}
