package com.ticketflow.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SignedSessionTokensTest {

    private static final String KEY = "a-test-key-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-08T20:00:00Z");
    private static final CheckoutSession SESSION =
            new CheckoutSession("riverside", "R07-12", "ana", NOW.plusSeconds(120), "box-small-1");

    private static SignedSessionTokens tokens(String key, Instant now) {
        return new SignedSessionTokens(key, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aTokenIssuedByOneBoxIsReadByAnotherWithTheSameKey() {
        String token = tokens(KEY, NOW).issue(SESSION);
        CheckoutSession read = tokens(KEY, NOW.plusSeconds(30)).resolve(token);
        assertEquals(SESSION, read);
    }

    @Test
    void changingTheSeatInsideTheTokenIsDetected() {
        String token = tokens(KEY, NOW).issue(SESSION);
        String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8);
        String forgedPayload = payload.replace("R07-12", "R01-01");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + token.substring(token.indexOf('.'));

        SessionLostException lost = assertThrows(SessionLostException.class, () -> tokens(KEY, NOW).resolve(forged));
        assertEquals("tampered", lost.reason());
    }

    @Test
    void aBoxWithADifferentKeyRejectsTheToken() {
        String token = tokens(KEY, NOW).issue(SESSION);
        SessionLostException lost = assertThrows(SessionLostException.class,
                () -> tokens("some-other-key-that-is-also-long-enough-99", NOW).resolve(token));
        assertEquals("tampered", lost.reason());
    }

    @Test
    void aTokenStopsWorkingWhenTheHoldWouldHaveExpired() {
        String token = tokens(KEY, NOW).issue(SESSION);
        SessionLostException lost = assertThrows(SessionLostException.class,
                () -> tokens(KEY, NOW.plusSeconds(121)).resolve(token));
        assertEquals("expired", lost.reason());
    }

    @Test
    void garbageIsRejectedWithoutAStackTrace() {
        assertEquals("missing", assertThrows(SessionLostException.class, () -> tokens(KEY, NOW).resolve(null)).reason());
        assertEquals("malformed", assertThrows(SessionLostException.class, () -> tokens(KEY, NOW).resolve("abc")).reason());
        assertEquals("malformed", assertThrows(SessionLostException.class, () -> tokens(KEY, NOW).resolve("a.b.c")).reason());
        assertEquals("malformed", assertThrows(SessionLostException.class, () -> tokens(KEY, NOW).resolve("!!.??")).reason());
    }

    @Test
    void aShortKeyIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> tokens("too-short", NOW));
    }
}
