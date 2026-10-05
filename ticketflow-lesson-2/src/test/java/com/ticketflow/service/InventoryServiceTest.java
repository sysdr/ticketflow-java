package com.ticketflow.service;

import com.ticketflow.config.DemoClock;
import com.ticketflow.domain.Booking;
import com.ticketflow.domain.DomainException;
import com.ticketflow.domain.DomainException.Code;
import com.ticketflow.domain.Hold;
import com.ticketflow.domain.SeatStatus;
import com.ticketflow.domain.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryServiceTest {

    private DemoClock clock;
    private List<String> events;
    private InventoryService inventory;

    @BeforeEach
    void setUp() {
        clock = new DemoClock();
        events = new ArrayList<>();
        EventSink sink = (component, event, fields) -> events.add(component + "/" + event);
        inventory = new InventoryService(Venue.grid("v", "Test Hall", 2, 3), clock, Duration.ofSeconds(120), sink);
    }

    private SeatStatus statusOf(String seatId) {
        return inventory.seats().stream().filter(s -> s.id().equals(seatId)).findFirst().orElseThrow().status();
    }

    @Test
    void holdThenConfirmProducesOneBookingAndABookedSeat() {
        Hold hold = inventory.placeHold("A1", "alice");
        assertEquals(SeatStatus.HELD, statusOf("A1"));

        Booking booking = inventory.confirm(hold.id());

        assertEquals("A1", booking.seatId());
        assertEquals("alice", booking.buyerId());
        assertEquals(SeatStatus.BOOKED, statusOf("A1"));
        assertEquals(Hold.Status.CONFIRMED, inventory.hold(hold.id()).status());
        assertEquals(1, inventory.bookings().size());
        assertTrue(inventory.invariantViolations().isEmpty());
    }

    @Test
    void aHeldSeatCannotBeHeldAgain() {
        inventory.placeHold("A1", "alice");
        DomainException ex = assertThrows(DomainException.class, () -> inventory.placeHold("A1", "bob"));
        assertEquals(Code.SEAT_UNAVAILABLE, ex.code());
    }

    @Test
    void releasingAHoldFreesTheSeat() {
        Hold hold = inventory.placeHold("B2", "alice");
        inventory.release(hold.id());
        assertEquals(SeatStatus.AVAILABLE, statusOf("B2"));
        inventory.placeHold("B2", "bob"); // someone else can now take it
    }

    @Test
    void anExpiredHoldFreesTheSeatWithoutAnyBackgroundJob() {
        Hold hold = inventory.placeHold("A2", "alice");
        clock.advance(119);
        assertEquals(SeatStatus.HELD, statusOf("A2"));

        clock.advance(1); // exactly at the deadline: the lease is over
        assertEquals(SeatStatus.AVAILABLE, statusOf("A2"));
        assertEquals(Hold.Status.EXPIRED, inventory.hold(hold.id()).status());
        assertTrue(events.contains("inventory/hold.expired"));
    }

    @Test
    void confirmingAfterTheDeadlineFailsAndLeavesTheSeatFree() {
        Hold hold = inventory.placeHold("A3", "alice");
        clock.advance(121);

        DomainException ex = assertThrows(DomainException.class, () -> inventory.confirm(hold.id()));

        assertEquals(Code.HOLD_EXPIRED, ex.code());
        assertEquals(SeatStatus.AVAILABLE, statusOf("A3"));
        assertEquals(0, inventory.bookings().size());
    }

    @Test
    void aSeatWhoseHoldExpiredCanBeSoldToSomeoneElse() {
        Hold first = inventory.placeHold("B1", "alice");
        clock.advance(200);
        Hold second = inventory.placeHold("B1", "bob");
        Booking booking = inventory.confirm(second.id());

        assertEquals("bob", booking.buyerId());
        DomainException ex = assertThrows(DomainException.class, () -> inventory.confirm(first.id()));
        assertEquals(Code.HOLD_EXPIRED, ex.code());
        assertTrue(inventory.invariantViolations().isEmpty());
    }

    @Test
    void aHoldCanOnlyBeConfirmedOnce() {
        Hold hold = inventory.placeHold("A1", "alice");
        inventory.confirm(hold.id());
        DomainException ex = assertThrows(DomainException.class, () -> inventory.confirm(hold.id()));
        assertEquals(Code.HOLD_NOT_ACTIVE, ex.code());
        assertEquals(1, inventory.bookings().size());
    }

    @Test
    void unknownSeatsAndHoldsAreNotFound() {
        assertEquals(Code.NOT_FOUND, assertThrows(DomainException.class, () -> inventory.placeHold("Z9", "a")).code());
        assertEquals(Code.NOT_FOUND, assertThrows(DomainException.class, () -> inventory.confirm("h-nope")).code());
    }

    @Test
    void blankInputIsRejected() {
        assertEquals(Code.INVALID_REQUEST, assertThrows(DomainException.class, () -> inventory.placeHold(" ", "a")).code());
        assertEquals(Code.INVALID_REQUEST, assertThrows(DomainException.class, () -> inventory.placeHold("A1", null)).code());
    }

    @Test
    void everyStateChangeReportsAnEvent() {
        Hold hold = inventory.placeHold("A1", "alice");
        inventory.confirm(hold.id());
        assertEquals(List.of("inventory/hold.created", "inventory/booking.created"), events);
    }

    @Test
    void seatViewsCarryHoldDetailsWhileHeld() {
        Hold hold = inventory.placeHold("A1", "alice");
        var view = inventory.seats().stream().filter(s -> s.id().equals("A1")).findFirst().orElseThrow();
        assertEquals(hold.id(), view.holdId());
        assertEquals("alice", view.heldBy());
        assertNotNull(view.holdExpiresAtMs());
    }
}
