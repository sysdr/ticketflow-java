package dev.ticketflow.trace;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The latency budget for one booking: a total, a slice for every hop, and a reserve that
 * belongs to nobody. The slices plus the reserve must add up to the total.
 */
@Component
public class Budget {
    public record Hop(String name, int budgetMs) {}

    private final int totalMs;
    private final int reserveMs;
    private final List<Hop> hops;

    public Budget(@Value("${ticketflow.budget.total-ms:300}") int totalMs,
                  @Value("${ticketflow.budget.wire-ms:60}") int wireMs,
                  @Value("${ticketflow.budget.lock-ms:10}") int lockMs,
                  @Value("${ticketflow.budget.domain-ms:15}") int domainMs,
                  @Value("${ticketflow.budget.payment-ms:150}") int paymentMs,
                  @Value("${ticketflow.budget.render-ms:5}") int renderMs,
                  @Value("${ticketflow.budget.reserve-ms:60}") int reserveMs) {
        this.totalMs = totalMs;
        this.reserveMs = reserveMs;
        this.hops = List.of(new Hop("wire", wireMs), new Hop("lock", lockMs), new Hop("domain", domainMs),
                new Hop("payment", paymentMs), new Hop("render", renderMs));
    }

    public int totalMs() { return totalMs; }

    public int reserveMs() { return reserveMs; }

    public List<Hop> hops() { return hops; }

    public int budgetOf(String hop) {
        return hops.stream().filter(h -> h.name().equals(hop)).mapToInt(Hop::budgetMs).findFirst().orElse(0);
    }

    /** True when the hop slices plus the reserve add up to the total. */
    public boolean balanced() {
        return hops.stream().mapToInt(Hop::budgetMs).sum() + reserveMs == totalMs;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"totalMs\":" + totalMs + ",\"reserveMs\":" + reserveMs
                + ",\"balanced\":" + balanced() + ",\"hops\":[");
        for (int i = 0; i < hops.size(); i++) {
            sb.append(i == 0 ? "" : ",").append("{\"name\":\"").append(hops.get(i).name())
                    .append("\",\"budgetMs\":").append(hops.get(i).budgetMs()).append('}');
        }
        return sb.append("]}").toString();
    }
}
