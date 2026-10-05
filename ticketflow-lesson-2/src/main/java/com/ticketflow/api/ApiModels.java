package com.ticketflow.api;

import com.ticketflow.domain.Booking;
import com.ticketflow.domain.Hold;
import com.ticketflow.service.SeatView;

import java.util.List;
import java.util.Map;

/** The JSON shapes of the API. Times are epoch milliseconds so no date-format setting can bite. */
public final class ApiModels {

    private ApiModels() {}

    public record HoldRequest(String seatId, String buyerId) {}

    public record HoldResponse(String id, String seatId, String buyerId, String status,
                               long createdAtMs, long expiresAtMs) {
        static HoldResponse of(Hold h) {
            return new HoldResponse(h.id(), h.seatId(), h.buyerId(), h.status().name(),
                    h.createdAt().toEpochMilli(), h.expiresAt().toEpochMilli());
        }
    }

    public record BookingResponse(String id, String holdId, String seatId, String buyerId, long bookedAtMs) {
        static BookingResponse of(Booking b) {
            return new BookingResponse(b.id(), b.holdId(), b.seatId(), b.buyerId(), b.bookedAt().toEpochMilli());
        }
    }

    public record StateResponse(String venue, long nowMs, long holdTtlSeconds, long clockSkewSeconds,
                                List<SeatView> seats, Map<String, Long> counts,
                                List<String> invariantViolations) {}

    public record ErrorResponse(String code, String message, String requestId) {}
}
