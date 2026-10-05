package dev.ticketflow.trace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import dev.ticketflow.booking.PaymentGateway;
import dev.ticketflow.inventory.VenueService;
import dev.ticketflow.lab.FaultProxy;
import dev.ticketflow.loadgen.Percentiles;
import dev.ticketflow.observability.VenueJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Books many seats through the (possibly flaky) link, times every request from the client's chair,
 * reads the server's own Server-Timing breakdown, and holds the two against the budget.
 * The gap between them is time the server cannot see: the wire and the queue in front of it.
 */
@Component
public class BudgetLab {
    private static final Logger log = LoggerFactory.getLogger("LAB");
    static final List<String> HOPS = List.of("wire", "lock", "domain", "payment", "render");

    public record Params(int requests, int parallel, int paymentMs) {}

    public record Sample(String rid, String seat, String outcome, double totalMs, double wireMs, double lockMs,
                         double domainMs, double paymentMs, double renderMs, double leftMs, boolean within,
                         String worst) {
        double of(String hop) {
            return switch (hop) {
                case "wire" -> wireMs;
                case "lock" -> lockMs;
                case "domain" -> domainMs;
                case "payment" -> paymentMs;
                default -> renderMs;
            };
        }

        String toJson() {
            return "{\"rid\":" + VenueJson.quote(rid) + ",\"seat\":" + VenueJson.quote(seat)
                    + ",\"outcome\":" + VenueJson.quote(outcome) + ",\"totalMs\":" + BudgetLab.n(totalMs)
                    + ",\"wireMs\":" + BudgetLab.n(wireMs) + ",\"lockMs\":" + BudgetLab.n(lockMs) + ",\"domainMs\":" + BudgetLab.n(domainMs)
                    + ",\"paymentMs\":" + BudgetLab.n(paymentMs) + ",\"renderMs\":" + BudgetLab.n(renderMs)
                    + ",\"leftMs\":" + BudgetLab.n(leftMs) + ",\"within\":" + within
                    + ",\"worst\":" + VenueJson.quote(worst) + "}";
        }
    }

    public record HopStats(double budgetMs, double p50Ms, double p95Ms) {}

    public record Result(Params params, int ok, int failed, int within, double p50Ms, double p95Ms, double p99Ms,
                         double maxMs, Map<String, HopStats> hops, Map<String, Integer> worst,
                         List<Sample> featured, List<Sample> slowest) {}

    private final VenueService venue;
    private final FaultProxy proxy;
    private final PaymentGateway payment;
    private final Budget budget;

    private volatile String state = "idle";
    private volatile Result result;

    public BudgetLab(VenueService venue, FaultProxy proxy, PaymentGateway payment, Budget budget) {
        this.venue = venue;
        this.proxy = proxy;
        this.payment = payment;
        this.budget = budget;
    }

    public synchronized boolean start(Params params) {
        if ("running".equals(state)) {
            return false;
        }
        state = "running";
        Thread.ofPlatform().name("budget-run").start(() -> runBlocking(params));
        return true;
    }

    public Result runBlocking(Params params) {
        state = "running";
        int perRow = venue.venue().seatsPerRow();
        int n = Math.max(1, Math.min(params.requests(), venue.venue().size()));
        int parallel = Math.max(1, Math.min(params.parallel(), 300));
        Params used = new Params(n, parallel, params.paymentMs());

        payment.setLatencyMs(params.paymentMs());
        warmUp();
        venue.reset();
        Sample[] samples = new Sample[n];
        Semaphore permits = new Semaphore(parallel);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        String seat = "%c-%02d".formatted((char) ('A' + idx / perRow), idx % perRow + 1);
                        samples[idx] = attempt("trc-%04d".formatted(idx + 1), seat, "trc-%03d".formatted(idx + 1));
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        Result r = summarize(used, samples);
        result = r;
        state = "done";
        log.info("event=budget_run_done requests={} within={} p50Ms={} p95Ms={}", n, r.within(),
                n(r.p50Ms()), n(r.p95Ms()));
        return r;
    }

    /**
     * A cold JVM and cold connections make the first requests slow for reasons that have nothing to
     * do with the budget. Run a few throwaway bookings first, then start from an empty venue.
     */
    private void warmUp() {
        venue.reset();
        for (int i = 0; i < 6; i++) {
            attempt("warm-%04d".formatted(i + 1), "A-%02d".formatted(i + 1), "warm-%03d".formatted(i + 1));
        }
    }

    // ---- one booking, timed from the client's chair -------------------------------------

    private Sample attempt(String rid, String seat, String buyer) {
        MDC.put("rid", rid);
        long start = System.nanoTime();
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        try (Socket socket = new Socket()) {
            log.info("event=send seat={} budgetMs={}", seat, budget.totalMs());
            socket.connect(new InetSocketAddress("localhost", proxy.port()), 10_000);
            socket.setSoTimeout(15_000);
            String request = "POST /api/book?seat=" + seat + "&buyer=" + buyer + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "X-Request-Id: " + rid + "\r\nX-Budget-Ms: " + budget.totalMs()
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) >= 0) {
                got.write(buf, 0, read);
            }
        } catch (IOException e) {
            log.info("event=outcome result=ERROR reason={}", e.getClass().getSimpleName());
            return failed(rid, seat, "ERROR", elapsedMs(start));
        } finally {
            MDC.remove("rid");
        }
        double totalMs = elapsedMs(start);
        String text = got.toString(StandardCharsets.ISO_8859_1);
        String head = text.contains("\r\n\r\n") ? text.substring(0, text.indexOf("\r\n\r\n")) : text;
        String first = head.split("\r\n")[0];
        String[] parts = first.split(" ");
        String outcome = parts.length >= 2 ? "HTTP_" + parts[1].replaceAll("[^0-9]", "") : "GARBLED";

        Map<String, Double> timing = serverTiming(head);
        double lock = timing.getOrDefault("lock", 0.0);
        double domain = timing.getOrDefault("domain", 0.0);
        double pay = timing.getOrDefault("payment", 0.0);
        double render = timing.getOrDefault("render", 0.0);
        double wire = Math.max(0, totalMs - (lock + domain + pay + render));
        double left = timing.getOrDefault("left", -1.0);
        boolean within = totalMs <= budget.totalMs();
        Sample s = new Sample(rid, seat, outcome, totalMs, wire, lock, domain, pay, render, left, within, "none");
        s = new Sample(rid, seat, outcome, totalMs, wire, lock, domain, pay, render, left, within, worstHop(s));
        MDC.put("rid", rid);
        log.info("event=outcome result={} totalMs={} wireMs={} serverMs={} within={} worst={}", outcome, n(totalMs),
                n(wire), n(lock + domain + pay + render), within, s.worst());
        MDC.remove("rid");
        return s;
    }

    private Sample failed(String rid, String seat, String outcome, double totalMs) {
        return new Sample(rid, seat, outcome, totalMs, totalMs, 0, 0, 0, 0, -1, false, "wire");
    }

    private String worstHop(Sample s) {
        String worst = "none";
        double over = 0;
        for (String hop : HOPS) {
            double d = s.of(hop) - budget.budgetOf(hop);
            if (d > over) {
                over = d;
                worst = hop;
            }
        }
        return worst;
    }

    /** Parses "lock;dur=0.12, payment;dur=120.4, left;dur=170.2;desc=\"budget left\"" into name to ms. */
    static Map<String, Double> serverTiming(String head) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Server-Timing")) {
                for (String entry : line.substring(colon + 1).split(",")) {
                    String[] fields = entry.trim().split(";");
                    for (String f : fields) {
                        if (f.trim().startsWith("dur=")) {
                            try {
                                out.put(fields[0].trim(), Double.parseDouble(f.trim().substring(4)));
                            } catch (NumberFormatException ignored) {
                                // skip a malformed entry
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---- summary ------------------------------------------------------------------------

    private Result summarize(Params used, Sample[] all) {
        List<Sample> okRows = new ArrayList<>();
        int failed = 0;
        for (Sample s : all) {
            if (s != null && "HTTP_201".equals(s.outcome())) {
                okRows.add(s);
            } else {
                failed++;
            }
        }
        long[] totals = micros(okRows, null);
        long[] sortedTotals = Percentiles.sortedNonNegative(totals);
        Map<String, HopStats> hops = new LinkedHashMap<>();
        for (String hop : HOPS) {
            long[] sorted = Percentiles.sortedNonNegative(micros(okRows, hop));
            hops.put(hop, new HopStats(budget.budgetOf(hop), ms(Percentiles.of(sorted, 50)), ms(Percentiles.of(sorted, 95))));
        }
        Map<String, Integer> worst = new LinkedHashMap<>();
        okRows.forEach(s -> worst.merge(s.worst(), 1, Integer::sum));

        List<Sample> byTotal = new ArrayList<>(okRows);
        byTotal.sort(Comparator.comparingDouble(Sample::totalMs));
        List<Sample> featured = new ArrayList<>();
        List<Sample> slowest = new ArrayList<>();
        if (!byTotal.isEmpty()) {
            featured.add(byTotal.get(byTotal.size() / 2));
            featured.add(byTotal.get(byTotal.size() - 1));
            for (int i = byTotal.size() - 1; i >= Math.max(0, byTotal.size() - 8); i--) {
                slowest.add(byTotal.get(i));
            }
        }
        return new Result(used, okRows.size(), failed, (int) okRows.stream().filter(Sample::within).count(),
                ms(Percentiles.of(sortedTotals, 50)), ms(Percentiles.of(sortedTotals, 95)),
                ms(Percentiles.of(sortedTotals, 99)),
                sortedTotals.length == 0 ? -1 : ms(sortedTotals[sortedTotals.length - 1]),
                hops, worst, featured, slowest);
    }

    private static long[] micros(List<Sample> rows, String hop) {
        long[] out = new long[rows.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.round((hop == null ? rows.get(i).totalMs() : rows.get(i).of(hop)) * 1000);
        }
        return out;
    }

    private static double ms(long micros) {
        return micros < 0 ? -1 : micros / 1000.0;
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static String n(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ---- JSON for the dashboard ---------------------------------------------------------

    public String toJson() {
        Result r = result;
        String head = "{\"state\":\"" + state + "\",\"budget\":" + budget.toJson()
                + ",\"paymentMs\":" + payment.latencyMs();
        if (r == null) {
            return head + "}";
        }
        StringBuilder hops = new StringBuilder("[");
        for (String hop : HOPS) {
            HopStats h = r.hops().get(hop);
            hops.append(hops.length() > 1 ? "," : "").append("{\"name\":\"").append(hop).append("\",\"budgetMs\":")
                    .append(n(h.budgetMs())).append(",\"p50Ms\":").append(n(h.p50Ms()))
                    .append(",\"p95Ms\":").append(n(h.p95Ms())).append('}');
        }
        hops.append(']');
        StringBuilder worst = new StringBuilder("{");
        r.worst().forEach((k, v) -> worst.append(worst.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        worst.append('}');
        return head + ",\"run\":{\"requests\":" + r.params().requests() + ",\"parallel\":" + r.params().parallel()
                + ",\"ok\":" + r.ok() + ",\"failed\":" + r.failed() + ",\"within\":" + r.within()
                + ",\"p50Ms\":" + n(r.p50Ms()) + ",\"p95Ms\":" + n(r.p95Ms()) + ",\"p99Ms\":" + n(r.p99Ms())
                + ",\"maxMs\":" + n(r.maxMs()) + ",\"hops\":" + hops + ",\"worst\":" + worst
                + ",\"featured\":" + list(r.featured()) + ",\"slowest\":" + list(r.slowest()) + "}}";
    }

    private static String list(List<Sample> samples) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < samples.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(samples.get(i).toJson());
        }
        return sb.append(']').toString();
    }
}
