package com.ticketflow.service;

/**
 * Holds the id of the request being handled by the current thread, so every
 * layer can stamp its events with it without passing it through every method.
 * (Lesson 7 removes server-side state; this is per-thread scratch, not session state.)
 */
public final class RequestIds {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestIds() {}

    public static void set(String id) { CURRENT.set(id); }

    public static String current() {
        String id = CURRENT.get();
        return id == null ? "none" : id;
    }

    public static void clear() { CURRENT.remove(); }
}
