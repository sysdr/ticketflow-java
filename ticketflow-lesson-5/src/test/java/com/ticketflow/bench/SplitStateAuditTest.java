package com.ticketflow.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketflow.domain.Venue;
import com.ticketflow.inventory.SeatMap;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SplitStateAuditTest {

    private static final Venue VENUE = new Venue("riverside", "Riverside Hall", 20, 25);

    @Test
    void oneBoxCannotSellASeatTwice() {
        SeatMap only = new SeatMap(VENUE, Clock.systemUTC());
        only.confirm(only.hold("R01-01", "ana"), "CODE", "box-a");
        only.confirm(only.hold("R01-02", "ben"), "CODE", "box-a");

        List<Set<String>> sold = List.of(Set.copyOf(only.soldSeatIds()));
        assertEquals(2, SplitStateAudit.totalSold(sold));
        assertTrue(SplitStateAudit.soldMoreThanOnce(sold).isEmpty());
    }

    @Test
    void twoBoxesWithTheirOwnSeatMapsBothSellTheSameSeat() {
        SeatMap boxA = new SeatMap(VENUE, Clock.systemUTC());
        SeatMap boxB = new SeatMap(VENUE, Clock.systemUTC());

        // Each box is correct by its own records. Neither refuses, because neither knows about the other.
        boxA.confirm(boxA.hold("R01-01", "ana"), "CODE-A", "box-a");
        boxB.confirm(boxB.hold("R01-01", "ben"), "CODE-B", "box-b");
        boxB.confirm(boxB.hold("R01-02", "cal"), "CODE-B", "box-b");

        List<Set<String>> sold = List.of(Set.copyOf(boxA.soldSeatIds()), Set.copyOf(boxB.soldSeatIds()));
        assertEquals(3, SplitStateAudit.totalSold(sold));
        assertEquals(Set.of("R01-01"), SplitStateAudit.soldMoreThanOnce(sold));
    }

    @Test
    void theSoldEndpointShapeIsParsed() {
        assertEquals(Set.of("R01-01", "R20-25"), BoxClient.parseSeatIds("[\"R01-01\",\"R20-25\"]"));
        assertTrue(BoxClient.parseSeatIds("[]").isEmpty());
    }
}
