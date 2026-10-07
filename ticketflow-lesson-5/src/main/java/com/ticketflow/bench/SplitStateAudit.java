package com.ticketflow.bench;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares what each box thinks it sold.
 *
 * <p>With one box the answer is always "nothing sold twice". With two boxes
 * that each keep their own seat map, this is where the damage shows up.
 */
public final class SplitStateAudit {

    private SplitStateAudit() {
    }

    /** Total bookings across every box, counting a seat once per box that sold it. */
    public static int totalSold(Collection<Set<String>> soldPerBox) {
        int total = 0;
        for (Set<String> sold : soldPerBox) {
            total += sold.size();
        }
        return total;
    }

    /** Seat ids that more than one box sold. */
    public static Set<String> soldMoreThanOnce(Collection<Set<String>> soldPerBox) {
        Map<String, Integer> sellers = new HashMap<>();
        for (Set<String> sold : soldPerBox) {
            for (String seatId : sold) {
                sellers.merge(seatId, 1, Integer::sum);
            }
        }
        Set<String> duplicates = new TreeSet<>();
        sellers.forEach((seatId, count) -> {
            if (count > 1) {
                duplicates.add(seatId);
            }
        });
        return duplicates;
    }
}
