package com.ticketflow.api;

import com.ticketflow.domain.Seat;
import com.ticketflow.domain.SeatStatus;
import com.ticketflow.inventory.SeatMap;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only views of the hall as this box sees it. */
@RestController
@RequestMapping("/api/venues")
public class VenueController {

    public record VenueView(String id, String name, int capacity, int available, int held, int booked) {
    }

    public record SeatView(String id, int row, int number, SeatStatus status) {
    }

    private final SeatMap seatMap;

    public VenueController(SeatMap seatMap) {
        this.seatMap = seatMap;
    }

    @GetMapping("/{venueId}")
    public VenueView venue(@PathVariable String venueId) {
        requireVenue(venueId);
        int capacity = seatMap.venue().capacity();
        int booked = seatMap.bookedCount();
        int held = seatMap.heldCount();
        return new VenueView(venueId, seatMap.venue().name(), capacity, capacity - booked - held, held, booked);
    }

    @GetMapping("/{venueId}/seats")
    public List<SeatView> seats(@PathVariable String venueId) {
        requireVenue(venueId);
        List<SeatView> views = new ArrayList<>();
        for (Seat seat : seatMap.seats()) {
            views.add(new SeatView(seat.id(), seat.row(), seat.number(), seatMap.statusOf(seat.id())));
        }
        return views;
    }

    /** The seat ids this box has sold. The bench compares this list across boxes. */
    @GetMapping("/{venueId}/sold")
    public List<String> sold(@PathVariable String venueId) {
        requireVenue(venueId);
        return seatMap.soldSeatIds();
    }

    private void requireVenue(String venueId) {
        if (!seatMap.venue().id().equals(venueId)) {
            throw new IllegalArgumentException("unknown venue: " + venueId);
        }
    }
}
