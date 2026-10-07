package com.ticketflow;

import com.ticketflow.model.Booking;
import com.ticketflow.model.Seat;
import com.ticketflow.model.Seat.Status;
import com.ticketflow.model.Venue;
import com.ticketflow.repository.BookingRepository;
import com.ticketflow.repository.SeatRepository;
import com.ticketflow.repository.VenueRepository;
import com.ticketflow.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InventoryService — no Spring context, no database.
 *
 * 15 tests that run in milliseconds, matching the Lesson 5 count.
 * The load-balancing logic itself lives in NGINX, not in the app,
 * so these tests stay focused on the booking invariants.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock SeatRepository seatRepo;
    @Mock BookingRepository bookingRepo;
    @Mock VenueRepository venueRepo;

    InventoryService service;
    Venue riverside;
    Seat seatA01;

    @BeforeEach
    void setUp() {
        service = new InventoryService(seatRepo, bookingRepo, venueRepo);
        riverside = new Venue("Riverside Hall", 500);
        seatA01 = new Seat(riverside, "A01");
    }

    // --- booking success path ---

    @Test
    void bookSeat_returnsBooking_whenSeatAvailable() {
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.of(seatA01));
        when(seatRepo.save(seatA01)).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Booking b = service.bookSeat(1L, "alice@example.com");

        assertNotNull(b);
        assertEquals("alice@example.com", b.getBuyerEmail());
    }

    @Test
    void bookSeat_marksSeaAsBooked() {
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bookSeat(1L, "bob@example.com");

        assertEquals(Status.BOOKED, seatA01.getStatus());
    }

    @Test
    void bookSeat_savesBooking() {
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bookSeat(1L, "carol@example.com");

        verify(bookingRepo, times(1)).save(any());
    }

    @Test
    void bookSeat_storesProcessedBy_fromMdc() {
        org.slf4j.MDC.put("instanceId", "app-1");
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Booking b = service.bookSeat(1L, "dave@example.com");

        assertEquals("app-1", b.getProcessedBy());
        org.slf4j.MDC.clear();
    }

    @Test
    void bookSeat_usesUnknown_whenMdcNotSet() {
        org.slf4j.MDC.clear();
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Booking b = service.bookSeat(1L, "eve@example.com");

        assertEquals("unknown", b.getProcessedBy());
    }

    // --- no seats available ---

    @Test
    void bookSeat_throwsWhenNoSeatsAvailable() {
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.bookSeat(1L, "frank@example.com"));
    }

    @Test
    void bookSeat_doesNotSaveSeat_whenNoAvailability() {
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.empty());

        try { service.bookSeat(1L, "grace@example.com"); } catch (IllegalStateException ignored) {}

        verify(seatRepo, never()).save(any());
    }

    // --- seat map ---

    @Test
    void buildSeatMap_returnsCorrectCounts() {
        Seat booked = new Seat(riverside, "A02");
        booked.setStatus(Status.BOOKED);

        when(venueRepo.findById(1L)).thenReturn(Optional.of(riverside));
        when(seatRepo.findByVenueIdOrderBySeatNumber(1L)).thenReturn(List.of(seatA01, booked));

        var map = service.buildSeatMap(1L);

        assertEquals(2, map.totalSeats());
        assertEquals(1, map.available());
        assertEquals(1, map.booked());
    }

    @Test
    void buildSeatMap_throwsForUnknownVenue() {
        when(venueRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.buildSeatMap(99L));
    }

    // --- available seats count ---

    @Test
    void availableSeats_delegates_toRepository() {
        when(seatRepo.countByVenueIdAndStatus(1L, Status.AVAILABLE)).thenReturn(487L);

        assertEquals(487L, service.availableSeats(1L));
    }

    // --- distribution and duplicates ---

    @Test
    void distributionByInstance_returnsRepositoryResult() {
        when(bookingRepo.countByInstance()).thenReturn(
                Collections.singletonList(new Object[]{"app-1", 300L}));

        var dist = service.distributionByInstance();

        assertEquals(1, dist.size());
        assertEquals("app-1", dist.get(0)[0]);
    }

    @Test
    void duplicateBookings_returnsEmptyOnCleanData() {
        when(bookingRepo.findDuplicateBookings()).thenReturn(List.of());

        assertTrue(service.duplicateBookings().isEmpty());
    }

    @Test
    void bookSeat_recordsSeatNumber() {
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Booking b = service.bookSeat(1L, "henry@example.com");

        assertEquals("A01", b.getSeat().getSeatNumber());
    }

    @Test
    void bookSeat_callsFindFirstAvailableExactlyOnce() {
        when(seatRepo.findFirstAvailable(1L)).thenReturn(Optional.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bookSeat(1L, "ivan@example.com");

        verify(seatRepo, times(1)).findFirstAvailable(1L);
    }
}
