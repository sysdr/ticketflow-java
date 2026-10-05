package dev.ticketflow.lab;

/** One setting of the flaky link. {@code percent} is the share of requests the fault hits. */
public record FaultConfig(FaultMode mode, int delayMs, int percent, int bytesPerSec) {

    public static FaultConfig healthy() {
        return new FaultConfig(FaultMode.NONE, 400, 100, 500);
    }

    public String toJson() {
        return "{\"mode\":\"" + mode + "\",\"delayMs\":" + delayMs
                + ",\"percent\":" + percent + ",\"bytesPerSec\":" + bytesPerSec + "}";
    }
}
