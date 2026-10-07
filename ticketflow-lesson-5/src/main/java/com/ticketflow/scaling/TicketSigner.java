package com.ticketflow.scaling;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces the code printed on a ticket by hashing the offer many times over.
 *
 * <p>This is the CPU cost of one booking request. It is real work on a real
 * core, and the number of rounds is calibrated at startup so one signature
 * costs about the same CPU time on any laptop.
 */
public final class TicketSigner {

    private static final byte[] SECRET = "ticketflow-lesson-5-signing-key".getBytes(StandardCharsets.UTF_8);

    private final int rounds;

    private TicketSigner(int rounds) {
        if (rounds < 1) {
            throw new IllegalArgumentException("rounds must be positive");
        }
        this.rounds = rounds;
    }

    public static TicketSigner withRounds(int rounds) {
        return new TicketSigner(rounds);
    }

    /**
     * Picks a round count so that one signature costs roughly {@code targetCpuMillis}
     * of CPU time. CPU time is measured, not wall time, so several boxes starting
     * together on one machine still calibrate to the same cost.
     */
    public static TicketSigner calibrated(int targetCpuMillis) {
        if (targetCpuMillis < 1) {
            throw new IllegalArgumentException("targetCpuMillis must be positive");
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuClock = threads.isCurrentThreadCpuTimeSupported();
        TicketSigner probe = new TicketSigner(2_000);

        // Warm long enough for the JIT to finish with the hash loop; a cold measurement would undercount.
        long warmUntil = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < warmUntil) {
            probe.sign("warm", "warm", "warm");
        }

        // Take the fastest of several batches: anything slower was the machine being busy, not the work.
        int samples = 60;
        double nanosPerRound = Double.MAX_VALUE;
        for (int batch = 0; batch < 5; batch++) {
            long before = cpuClock ? threads.getCurrentThreadCpuTime() : System.nanoTime();
            for (int i = 0; i < samples; i++) {
                probe.sign("calibrate", "seat", "buyer-" + i);
            }
            long after = cpuClock ? threads.getCurrentThreadCpuTime() : System.nanoTime();
            nanosPerRound = Math.min(nanosPerRound, (after - before) / (double) (samples * probe.rounds));
        }
        nanosPerRound = Math.max(1.0, nanosPerRound);
        long rounds = Math.round(targetCpuMillis * 1_000_000.0 / nanosPerRound);
        return new TicketSigner((int) Math.max(1_000, Math.min(rounds, 50_000_000L)));
    }

    public int rounds() {
        return rounds;
    }

    public String sign(String venueId, String seatId, String buyerId) {
        MessageDigest sha256 = newDigest();
        sha256.update(SECRET);
        byte[] state = sha256.digest((venueId + '|' + seatId + '|' + buyerId).getBytes(StandardCharsets.UTF_8));
        for (int round = 1; round < rounds; round++) {
            state = sha256.digest(state);
        }
        return HexFormat.of().formatHex(state, 0, 8).toUpperCase();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JDK ships SHA-256", impossible);
        }
    }
}
