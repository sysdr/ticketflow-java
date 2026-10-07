package com.ticketflow.model;

/**
 * The back-of-the-envelope model from lesson 1: how far behind does one box
 * fall when a crowd arrives inside a short window?
 */
public final class RushModel {

    private RushModel() {
    }

    /**
     * @param arrivalsPerSecond requests arriving each second during the rush
     * @param capacityPerSecond requests the box can finish each second
     * @param backlogAtWindowEnd requests still waiting when the rush stops
     * @param secondsToDrain    how long the last buyer waits for an answer
     */
    public record Result(int buyers, int seats, double windowSeconds, double cpuMillis, int cores,
                         double arrivalsPerSecond, double capacityPerSecond,
                         double backlogAtWindowEnd, double secondsToDrain) {
    }

    public static Result estimate(int buyers, int seats, double windowSeconds, double cpuMillis, int cores) {
        if (buyers < 1 || seats < 1 || windowSeconds <= 0 || cpuMillis <= 0 || cores < 1) {
            throw new IllegalArgumentException("buyers, seats, windowSeconds, cpuMillis and cores must be positive");
        }
        double arrivals = buyers / windowSeconds;
        double capacity = cores * 1000.0 / cpuMillis;
        double served = Math.min(buyers, capacity * windowSeconds);
        double backlog = buyers - served;
        double drain = backlog / capacity;
        return new Result(buyers, seats, windowSeconds, cpuMillis, cores, arrivals, capacity, backlog, drain);
    }
}
