package dev.ticketflow.observability;

import java.util.List;
import java.util.stream.Collectors;

import dev.ticketflow.domain.Booking;
import dev.ticketflow.domain.DomainEvent;
import dev.ticketflow.domain.DomainException;
import dev.ticketflow.domain.Hold;
import dev.ticketflow.inventory.VenueService.Snapshot;

/** Hand-built JSON for the domain model, so the project needs no JSON library of its own. */
public final class VenueJson {
    private VenueJson() {}

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String hold(Hold h) {
        return "{\"id\":" + quote(h.id())
                + ",\"seat\":" + quote(h.seatId().value())
                + ",\"buyer\":" + quote(h.buyer())
                + ",\"state\":" + quote(h.state().name())
                + ",\"placedAtMs\":" + h.placedAt().toEpochMilli()
                + ",\"expiresAtMs\":" + h.expiresAt().toEpochMilli() + "}";
    }

    public static String booking(Booking b) {
        return "{\"id\":" + quote(b.id())
                + ",\"hold\":" + quote(b.holdId())
                + ",\"seat\":" + quote(b.seatId().value())
                + ",\"buyer\":" + quote(b.buyer())
                + ",\"confirmedAtMs\":" + b.confirmedAt().toEpochMilli() + "}";
    }

    public static String event(DomainEvent e) {
        return "{\"atMs\":" + e.at().toEpochMilli()
                + ",\"type\":" + quote(e.type())
                + ",\"hold\":" + quote(e.holdId())
                + ",\"seat\":" + quote(e.seat())
                + ",\"buyer\":" + quote(e.buyer()) + "}";
    }

    public static String error(DomainException e) {
        return "{\"error\":" + quote(e.code().name()) + ",\"message\":" + quote(e.getMessage()) + "}";
    }

    public static String snapshot(Snapshot s, List<DomainEvent> events) {
        return "{\"venue\":{\"id\":" + quote(s.venue().id())
                + ",\"name\":" + quote(s.venue().name())
                + ",\"rows\":" + s.venue().rows()
                + ",\"perRow\":" + s.venue().seatsPerRow() + "}"
                + ",\"nowMs\":" + s.nowMs()
                + ",\"counts\":{\"available\":" + s.available() + ",\"held\":" + s.held() + ",\"booked\":" + s.booked() + "}"
                + ",\"map\":" + quote(s.map())
                + ",\"holds\":" + array(s.liveHolds().stream().map(VenueJson::hold).toList())
                + ",\"bookings\":" + array(s.recentBookings().stream().map(VenueJson::booking).toList())
                + ",\"events\":" + array(events.stream().map(VenueJson::event).toList()) + "}";
    }

    private static String array(List<String> items) {
        return items.stream().collect(Collectors.joining(",", "[", "]"));
    }
}
