package com.ticketflow.api;

import com.ticketflow.api.ApiModels.BookingResponse;
import com.ticketflow.api.ApiModels.HoldRequest;
import com.ticketflow.api.ApiModels.HoldResponse;
import com.ticketflow.api.ApiModels.StateResponse;
import com.ticketflow.config.DemoClock;
import com.ticketflow.domain.SeatStatus;
import com.ticketflow.observability.EventRecord;
import com.ticketflow.observability.RecordingEventSink;
import com.ticketflow.service.EventSink;
import com.ticketflow.service.InventoryService;
import com.ticketflow.service.SeatView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InventoryController {

    private final InventoryService inventory;
    private final DemoClock clock;
    private final RecordingEventSink events;

    public InventoryController(InventoryService inventory, DemoClock clock, RecordingEventSink events) {
        this.inventory = inventory;
        this.clock = clock;
        this.events = events;
    }

    @GetMapping("/state")
    public StateResponse state() {
        List<SeatView> seats = inventory.seats();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SeatStatus s : SeatStatus.values()) {
            counts.put(s.name(), seats.stream().filter(v -> v.status() == s).count());
        }
        return new StateResponse(inventory.venueName(), inventory.now().toEpochMilli(),
                inventory.holdTtlSeconds(), clock.skewSeconds(), seats, counts, inventory.invariantViolations());
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse placeHold(@RequestBody HoldRequest body) {
        events.emit("api", "hold.requested", EventSink.fields("seat", body.seatId(), "buyer", body.buyerId()));
        return HoldResponse.of(inventory.placeHold(body.seatId(), body.buyerId()));
    }

    @PostMapping("/holds/{holdId}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse confirm(@PathVariable String holdId) {
        events.emit("api", "confirm.requested", EventSink.fields("hold", holdId));
        return BookingResponse.of(inventory.confirm(holdId));
    }

    @DeleteMapping("/holds/{holdId}")
    public HoldResponse release(@PathVariable String holdId) {
        events.emit("api", "release.requested", EventSink.fields("hold", holdId));
        return HoldResponse.of(inventory.release(holdId));
    }

    @GetMapping("/holds/{holdId}")
    public HoldResponse hold(@PathVariable String holdId) {
        return HoldResponse.of(inventory.hold(holdId));
    }

    @GetMapping("/bookings")
    public List<BookingResponse> bookings() {
        return inventory.bookings().stream().map(BookingResponse::of).toList();
    }

    @GetMapping("/events")
    public List<EventRecord> events(@RequestParam(defaultValue = "0") long after) {
        return events.since(after);
    }

    /** Demo only: pushes the clock forward so a hold can expire without waiting for it. */
    @PostMapping("/dev/clock/advance")
    public Map<String, Object> advance(@RequestParam long seconds) {
        clock.advance(seconds);
        events.emit("api", "clock.advanced", EventSink.fields("seconds", seconds, "totalSkew", clock.skewSeconds()));
        return Map.of("skewSeconds", clock.skewSeconds());
    }
}
