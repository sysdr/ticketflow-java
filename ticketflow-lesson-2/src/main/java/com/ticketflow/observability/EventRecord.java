package com.ticketflow.observability;

import java.util.Map;

/** One thing a component reported, stamped with the request it belongs to. */
public record EventRecord(long seq, long atMs, String requestId, String component, String event,
                          Map<String, Object> fields) {
}
