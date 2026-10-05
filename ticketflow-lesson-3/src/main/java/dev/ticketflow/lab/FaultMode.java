package dev.ticketflow.lab;

/** What the flaky link does to a message passing through it. */
public enum FaultMode {
    /** Pass everything through untouched. */
    NONE,
    /** Delay the request on the way in and the reply on the way out. */
    LATENCY,
    /** Swallow the request. The server never sees it, and the sender hears nothing. */
    LOSE_REQUEST,
    /** Let the server do the work, then swallow the reply. The sender hears nothing. */
    LOSE_RESPONSE,
    /** Trickle the reply out at a fixed number of bytes per second. */
    THROTTLE
}
