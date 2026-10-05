package dev.ticketflow.booking;

import java.util.UUID;
import java.util.function.Supplier;

import dev.ticketflow.domain.DomainException;
import dev.ticketflow.domain.SeatId;
import dev.ticketflow.inventory.EventLog;
import dev.ticketflow.inventory.VenueService;
import dev.ticketflow.observability.VenueJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The box-office API: look at the venue, hold a seat, confirm or release the hold. */
@RestController
public class HoldController {
    private static final Logger log = LoggerFactory.getLogger("EDGE");

    private final VenueService venue;
    private final EventLog events;

    public HoldController(VenueService venue, EventLog events) {
        this.venue = venue;
        this.events = events;
    }

    @GetMapping("/api/venue")
    public ResponseEntity<String> venue(@RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return call(requestId, "venue_requested", "", 200,
                () -> VenueJson.snapshot(venue.snapshot(), events.recent()));
    }

    @PostMapping("/api/holds")
    public ResponseEntity<String> hold(@RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                       @RequestParam("seat") String seat,
                                       @RequestParam("buyer") String buyer) {
        return call(requestId, "hold_requested", "seat=" + seat + " buyer=" + buyer, 201,
                () -> VenueJson.hold(venue.placeHold(new SeatId(seat), buyer)));
    }

    @PostMapping("/api/holds/{id}/confirm")
    public ResponseEntity<String> confirm(@RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                          @PathVariable("id") String id) {
        return call(requestId, "confirm_requested", "hold=" + id, 200,
                () -> VenueJson.booking(venue.confirm(id)));
    }

    @DeleteMapping("/api/holds/{id}")
    public ResponseEntity<String> release(@RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                          @PathVariable("id") String id) {
        return call(requestId, "release_requested", "hold=" + id, 200,
                () -> VenueJson.hold(venue.release(id)));
    }

    private ResponseEntity<String> call(String requestId, String event, String detail, int okStatus,
                                        Supplier<String> action) {
        String rid = requestId != null ? requestId : "api-" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put("rid", rid);
        try {
            log.info("event={} {}", event, detail);
            return json(okStatus, action.get());
        } catch (DomainException e) {
            log.info("event=rejected code={} message={}", e.code(), e.getMessage());
            return json(e.code().status(), VenueJson.error(e));
        } finally {
            MDC.remove("rid");
        }
    }

    private static ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
