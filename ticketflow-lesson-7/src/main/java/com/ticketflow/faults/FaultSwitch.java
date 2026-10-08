package com.ticketflow.faults;

/**
 * A test knob that makes one box misbehave on purpose, so you can watch how a
 * load balancer reacts.
 *
 * <ul>
 *   <li>{@code SLOW}: every signature costs {@code factor} times the usual CPU,
 *       like a box sharing its machine with a noisy neighbour.</li>
 *   <li>{@code FAIL_FAST}: every booking is refused at once with HTTP 503,
 *       like a box whose dependency is down.</li>
 * </ul>
 */
public final class FaultSwitch {

    public enum Mode {
        NONE, SLOW, FAIL_FAST;

        /** Accepts {@code none}, {@code slow}, {@code fail-fast} in any case. */
        public static Mode parse(String text) {
            String normal = text == null ? "" : text.trim().toUpperCase().replace('-', '_');
            for (Mode mode : values()) {
                if (mode.name().equals(normal)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("unknown fault mode: " + text + " (use none, slow or fail-fast)");
        }
    }

    private record State(Mode mode, int factor) {
    }

    private volatile State state = new State(Mode.NONE, 1);

    public void set(Mode mode, int factor) {
        if (factor < 1 || factor > 100) {
            throw new IllegalArgumentException("factor must be between 1 and 100");
        }
        this.state = new State(mode, mode == Mode.SLOW ? factor : 1);
    }

    public void clear() {
        this.state = new State(Mode.NONE, 1);
    }

    public boolean failFast() {
        return state.mode() == Mode.FAIL_FAST;
    }

    /** How many times the signing work is repeated: 1 when healthy. */
    public int signMultiplier() {
        return state.factor();
    }

    /** For example {@code none}, {@code slow x3} or {@code fail-fast}. */
    public String describe() {
        State now = state;
        return switch (now.mode()) {
            case NONE -> "none";
            case SLOW -> "slow x" + now.factor();
            case FAIL_FAST -> "fail-fast";
        };
    }
}
