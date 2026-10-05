package dev.ticketflow.inventory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import dev.ticketflow.domain.DomainEvent;
import org.springframework.stereotype.Component;

/** The last few domain events, kept so the dashboard can show a timeline without parsing logs. */
@Component
public class EventLog {
    private static final int CAPACITY = 60;
    private final ArrayDeque<DomainEvent> ring = new ArrayDeque<>();

    public synchronized void add(DomainEvent event) {
        if (ring.size() == CAPACITY) {
            ring.removeFirst();
        }
        ring.addLast(event);
    }

    /** Oldest first. */
    public synchronized List<DomainEvent> recent() {
        return new ArrayList<>(ring);
    }

    public synchronized void clear() {
        ring.clear();
    }
}
