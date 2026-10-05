package dev.ticketflow.booking;

import java.util.UUID;

import dev.ticketflow.logging.TraceSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {
    private static final Logger log = LoggerFactory.getLogger("EDGE");

    private final BookingService service;
    private final TraceSampler sampler;

    public BookingController(BookingService service, TraceSampler sampler) {
        this.service = service;
        this.sampler = sampler;
    }

    @PostMapping("/api/bookings")
    public ResponseEntity<String> book(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Deadline-Epoch-Ms", defaultValue = "0") long deadlineEpochMs) {
        String rid = requestId != null ? requestId : "srv-" + UUID.randomUUID();
        MDC.put("rid", rid);
        try {
            boolean traced = sampler.traced(rid);
            if (traced) {
                long left = deadlineEpochMs > 0 ? deadlineEpochMs - System.currentTimeMillis() : -1;
                log.info("event=received buyerPatienceLeftMs={}", left);
            }
            BookingService.Outcome outcome = service.book(rid, traced, deadlineEpochMs);
            return outcome.sold()
                    ? ResponseEntity.ok("SOLD seat=" + outcome.seat())
                    : ResponseEntity.status(409).body("SOLD_OUT");
        } finally {
            MDC.remove("rid");
        }
    }
}
