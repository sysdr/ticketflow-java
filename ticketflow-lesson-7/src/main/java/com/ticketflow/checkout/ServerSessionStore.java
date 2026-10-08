package com.ticketflow.checkout;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The "before" picture: sessions kept in this box's memory, the buyer holding
 * only a key to them. Any other box, or this box after a restart, has never
 * heard of that key.
 *
 * <p>Kept in the codebase so the lesson can compare the two side by side.
 */
public final class ServerSessionStore implements CheckoutSessions {

    public static final String KIND = "server";

    private final String node;
    private final Clock clock;
    private final ConcurrentHashMap<String, CheckoutSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public ServerSessionStore(String node, Clock clock) {
        this.node = node;
        this.clock = clock;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String issue(CheckoutSession session) {
        String key = "S-" + node + "-" + ids.incrementAndGet();
        sessions.put(key, session);
        return key;
    }

    @Override
    public CheckoutSession resolve(String handle) {
        if (handle == null || handle.isBlank()) {
            throw new SessionLostException("missing", "no checkout session was sent");
        }
        CheckoutSession session = sessions.get(handle);
        if (session == null) {
            throw new SessionLostException("unknown", node + " has no session " + handle);
        }
        if (session.expiredAt(clock.instant())) {
            sessions.remove(handle);
            throw new SessionLostException("expired", "checkout session " + handle + " has expired");
        }
        return session;
    }

    @Override
    public void finished(String handle) {
        sessions.remove(handle);
    }

    @Override
    public void forgetAll() {
        sessions.clear();
    }

    @Override
    public int liveInMemory() {
        return sessions.size();
    }
}
