package com.ticketflow.checkout;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The "after" picture: the session travels with the buyer as a signed token.
 * Any box that holds the same key can check it, so any box can confirm.
 *
 * <p>Format: {@code base64url(payload) "." base64url(HMAC-SHA256(payload))},
 * where the payload is {@code v1|venue|seat|buyer|expiresEpochSeconds|issuedBy}.
 * The payload is signed, not encrypted: the buyer can read it but cannot change it.
 */
public final class SignedSessionTokens implements CheckoutSessions {

    public static final String KIND = "signed";
    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final Clock clock;

    public SignedSessionTokens(String secret, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("the checkout signing key must be at least 32 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.clock = clock;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String issue(CheckoutSession session) {
        for (String field : new String[] {session.venueId(), session.seatId(), session.buyerId(), session.issuedBy()}) {
            if (field == null || field.indexOf('|') >= 0) {
                throw new IllegalArgumentException("session fields must be present and must not contain '|'");
            }
        }
        String payload = String.join("|", VERSION, session.venueId(), session.seatId(), session.buyerId(),
                Long.toString(session.expiresAt().getEpochSecond()), session.issuedBy());
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payloadBytes) + "." + ENCODER.encodeToString(sign(payloadBytes));
    }

    @Override
    public CheckoutSession resolve(String handle) {
        if (handle == null || handle.isBlank()) {
            throw new SessionLostException("missing", "no checkout session was sent");
        }
        int dot = handle.indexOf('.');
        if (dot <= 0 || dot != handle.lastIndexOf('.')) {
            throw new SessionLostException("malformed", "a checkout token has exactly one '.'");
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = DECODER.decode(handle.substring(0, dot));
            signature = DECODER.decode(handle.substring(dot + 1));
        } catch (IllegalArgumentException notBase64) {
            throw new SessionLostException("malformed", "the checkout token is not base64url");
        }
        // Compare in constant time, so response timing does not reveal how much of a forged signature was right.
        if (!MessageDigest.isEqual(sign(payloadBytes), signature)) {
            throw new SessionLostException("tampered", "the checkout token's signature does not match");
        }
        String[] fields = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields.length != 6 || !VERSION.equals(fields[0])) {
            throw new SessionLostException("malformed", "unexpected checkout token layout");
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[4]));
        } catch (NumberFormatException badNumber) {
            throw new SessionLostException("malformed", "the checkout token's expiry is not a number");
        }
        CheckoutSession session = new CheckoutSession(fields[1], fields[2], fields[3], expiresAt, fields[5]);
        if (session.expiredAt(clock.instant())) {
            throw new SessionLostException("expired", "the checkout token expired at " + expiresAt);
        }
        return session;
    }

    /** Nothing to remove: the token is the session. A used token is refused by the seat map instead. */
    @Override
    public void finished(String handle) {
    }

    /** Nothing to forget: a restart does not touch tokens the buyers are holding. */
    @Override
    public void forgetAll() {
    }

    @Override
    public int liveInMemory() {
        return 0;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("every JDK ships HmacSHA256", impossible);
        }
    }
}
