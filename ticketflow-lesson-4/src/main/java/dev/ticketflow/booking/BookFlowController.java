package dev.ticketflow.booking;

import java.util.UUID;

import dev.ticketflow.domain.Booking;
import dev.ticketflow.domain.DomainException;
import dev.ticketflow.domain.Hold;
import dev.ticketflow.domain.SeatId;
import dev.ticketflow.inventory.VenueService;
import dev.ticketflow.observability.VenueJson;
import dev.ticketflow.trace.RequestTrace;
import dev.ticketflow.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One whole booking, the way a buyer experiences it: hold the seat, charge the card, confirm.
 * Every hop is timed, and the numbers travel back in a standard Server-Timing response header.
 */
@RestController
public class BookFlowController {
    private static final Logger log = LoggerFactory.getLogger("EDGE");

    private final VenueService venue;
    private final PaymentGateway payment;

    public BookFlowController(VenueService venue, PaymentGateway payment) {
        this.venue = venue;
        this.payment = payment;
    }

    @PostMapping("/api/book")
    public ResponseEntity<String> book(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Budget-Ms", defaultValue = "0") long budgetMs,
            @RequestParam("seat") String seat,
            @RequestParam("buyer") String buyer) {
        String rid = requestId != null ? requestId : "api-" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put("rid", rid);
        RequestTrace trace = TraceContext.begin(budgetMs);
        try {
            log.info("event=booking_requested seat={} buyer={} budgetMs={}", seat, buyer, budgetMs);

            Hold hold = venue.placeHold(new SeatId(seat), buyer);
            log.info("event=hop_done hop=hold {}", trace.summary());

            payment.charge(hold.id(), buyer);
            log.info("event=hop_done hop=payment {}", trace.summary());

            Booking booking = venue.confirm(hold.id());
            log.info("event=hop_done hop=confirm {}", trace.summary());

            String body = trace.time("render", () -> VenueJson.booking(booking));
            log.info("event=booking_done booking={} hold={} serverMs={} {}", booking.id(), hold.id(),
                    "%.2f".formatted(trace.serverMs()), trace.summary());
            return respond(201, trace, body);
        } catch (DomainException e) {
            log.info("event=rejected code={} message={} {}", e.code(), e.getMessage(), trace.summary());
            return respond(e.code().status(), trace, VenueJson.error(e));
        } finally {
            TraceContext.end();
            MDC.remove("rid");
        }
    }

    private static ResponseEntity<String> respond(int status, RequestTrace trace, String body) {
        return ResponseEntity.status(status)
                .header("Server-Timing", trace.serverTiming())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
