package com.ticketflow.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Where components report what they just did. One implementation logs and remembers; tests collect. */
public interface EventSink {

    void emit(String component, String event, Map<String, Object> fields);

    /** Builds an ordered field map from alternating key, value arguments. */
    static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return m;
    }
}
