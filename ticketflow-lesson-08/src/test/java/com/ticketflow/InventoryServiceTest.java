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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InventoryService — no Spring context, no real database.
 *
 * 18 tests. The HashMap is gone; repository mocks take its place.
 * Tests verify that the service correctly delegates to JPA repositories,
 * marks seats BOOKED, and stores processedBy — all the observable
 * behaviors a real DB would confirm at integration-test time.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock SeatRepository    seatRepo;
    @Mock BookingRepository bookingRepo;
    @Mock VenueRepository   venueRepo;

    InventoryService service;
    Venue riverside;
    Seat  seatA01;

    @BeforeEach
    void setUp() {
        service   = new InventoryService(seatRepo, bookingRepo, venueRepo);
        riverside = new Venue("Riverside Hall", 500);
        seatA01   = new Seat(riverside, "A01");
    }

    // ── booking success ───────────────────────────────────────────────────────

    @Test
    void bookSeat_returnsBooking_whenSeatAvailable() {
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Booking b = service.bookSeat(1L, "alice@example.com");

        assertNotNull(b);
        assertEquals("alice@example.com", b.getBuyerEmail());
    }

    @Test
    void bookSeat_marksSeatAsBooked() {
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bookSeat(1L, "bob@example.com");

        assertEquals(Status.BOOKED, seatA01.getStatus());
    }

    @Test
    void bookSeat_savesBooking() {
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bookSeat(1L, "carol@example.com");

        verify(bookingRepo, times(1)).save(any());
    }

    @Test
    void bookSeat_storesProcessedBy_fromMdc() {
        org.slf4j.MDC.put("instanceId", "app-1");
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Booking b = service.bookSeat(1L, "dave@example.com");

        assertEquals("app-1", b.getProcessedBy());
        org.slf4j.MDC.clear();
    }

    @Test
    void bookSeat_usesUnknown_whenMdcNotSet() {
        org.slf4j.MDC.clear();
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Booking b = service.bookSeat(1L, "eve@example.com");

        assertEquals("unknown", b.getProcessedBy());
    }

    @Test
    void bookSeat_recordsSeatOnBooking() {
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Booking b = service.bookSeat(1L, "frank@example.com");

        assertEquals("A01", b.getSeat().getSeatNumber());
    }

    @Test
    void bookSeat_savesUpdatedSeat() {
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of(seatA01));
        when(seatRepo.save(any())).thenReturn(seatA01);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bookSeat(1L, "grace@example.com");

        verify(seatRepo, times(1)).save(seatA01);
    }

    // ── no seats available ────────────────────────────────────────────────────

    @Test
    void bookSeat_throwsWhenNoSeatsAvailable() {
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> service.bookSeat(1L, "henry@example.com"));
    }

    @Test
    void bookSeat_doesNotSaveSeat_whenNoAvailability() {
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of());

        try { service.bookSeat(1L, "ivan@example.com"); } catch (IllegalStateException ignored) {}

        verify(seatRepo, never()).save(any());
    }

    @Test
    void bookSeat_doesNotSaveBooking_whenNoAvailability() {
        when(seatRepo.findAvailableForVenue(1L)).thenReturn(List.of());

        try { service.bookSeat(1L, "julia@example.com"); } catch (IllegalStateException ignored) {}

        verify(bookingRepo, never()).save(any());
    }

    // ── seat map ──────────────────────────────────────────────────────────────

    @Test
    void buildSeatMap_returnsCorrectCounts() {
        when(venueRepo.findById(1L)).thenReturn(Optional.of(riverside));
        when(seatRepo.countByVenueIdAndStatus(1L, Status.AVAILABLE)).thenReturn(499L);
        when(seatRepo.countByVenueIdAndStatus(1L, Status.BOOKED)).thenReturn(1L);

        var map = service.buildSeatMap(1L);

        assertEquals(499L, map.available());
        assertEquals(1L,   map.booked());
    }

    @Test
    void buildSeatMap_throwsForUnknownVenue() {
        when(venueRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.buildSeatMap(99L));
    }

    // ── admin queries ─────────────────────────────────────────────────────────

    @Test
    void availableSeats_delegates_toRepository() {
        when(seatRepo.countByVenueIdAndStatus(1L, Status.AVAILABLE)).thenReturn(487L);

        assertEquals(487L, service.availableSeats(1L));
    }

    @Test
    void distributionByInstance_returnsRepositoryResult() {
        when(bookingRepo.countByInstance()).thenReturn(List.<Object[]>of(new Object[]{"app-1", 300L}));

        var dist = service.distributionByInstance();

        assertEquals(1, dist.size());
        assertEquals("app-1", dist.get(0)[0]);
    }

    @Test
    void duplicateBookings_returnsEmptyOnCleanData() {
        when(bookingRepo.findDuplicateBookings()).thenReturn(List.of());

        assertTrue(service.duplicateBookings().isEmpty());
    }

    // ── booking lookup ────────────────────────────────────────────────────────

    @Test
    void findBooking_throwsForUnknownId() {
        when(bookingRepo.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.findBooking(999L));
    }

    @Test
    void bookingHistory_delegatesToRepository() {
        when(bookingRepo.findByVenueIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        assertTrue(service.bookingHistory(1L).isEmpty());
        verify(bookingRepo, times(1)).findByVenueIdOrderByCreatedAtDesc(1L);
    }
}
