package com.ticketflow.observability;

import com.ticketflow.service.EventSink;
import com.ticketflow.service.RequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Writes every event to the log as one key=value line AND keeps the last 300 in memory
 * so the dashboard can show them. Both views carry the same request id.
 */
@Component
public class RecordingEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger("ticketflow.events");
    private static final int CAPACITY = 300;

    private final Deque<EventRecord> buffer = new ArrayDeque<>();
    private long nextSeq = 1;

    @Override
    public void emit(String component, String event, Map<String, Object> fields) {
        String rid = RequestIds.current();
        EventRecord record;
        synchronized (this) {
            record = new EventRecord(nextSeq++, System.currentTimeMillis(), rid, component, event, fields);
            buffer.addLast(record);
            while (buffer.size() > CAPACITY) {
                buffer.removeFirst();
            }
        }
        StringJoiner line = new StringJoiner(" ");
        line.add("rid=" + rid).add("component=" + component).add("event=" + event);
        fields.forEach((k, v) -> line.add(k + "=" + v));
        log.info(line.toString());
    }

    /** Events newest-last, optionally only those with sequence number above {@code afterSeq}. */
    public synchronized List<EventRecord> since(long afterSeq) {
        List<EventRecord> out = new ArrayList<>();
        for (EventRecord r : buffer) {
            if (r.seq() > afterSeq) {
                out.add(r);
            }
        }
        return out;
    }
}
