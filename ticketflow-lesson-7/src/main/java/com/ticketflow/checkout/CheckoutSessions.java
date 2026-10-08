package com.ticketflow.checkout;

/**
 * Turns a {@link CheckoutSession} into a string the buyer carries between the
 * two checkout calls, and back again.
 *
 * <p>The two implementations differ in one thing only: where the session lives.
 * {@link ServerSessionStore} keeps it in this process and hands out a key to it.
 * {@link SignedSessionTokens} hands out the session itself, signed.
 */
public interface CheckoutSessions {

    /** {@code server} or {@code signed}. */
    String kind();

    String issue(CheckoutSession session);

    /** Returns the session, or throws {@link SessionLostException} saying why it cannot. */
    CheckoutSession resolve(String handle);

    /** Called once the booking is made, so the session cannot be confirmed twice. */
    void finished(String handle);

    /** What a process restart does to this kind of session. */
    void forgetAll();

    /** Sessions this process is holding in memory right now. Always 0 for signed tokens. */
    int liveInMemory();
}
