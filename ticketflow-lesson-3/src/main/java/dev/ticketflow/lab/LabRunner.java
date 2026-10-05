package dev.ticketflow.lab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import dev.ticketflow.domain.Hold;
import dev.ticketflow.domain.SeatId;
import dev.ticketflow.inventory.VenueService;
import dev.ticketflow.observability.VenueJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends real requests to TicketFlow through the flaky link, then compares what each client
 * <em>heard</em> with what the server <em>actually did</em>. Nothing is repaired here: this lesson
 * is about seeing the gap, not closing it.
 */
@Component
public class LabRunner {
    private static final Logger log = LoggerFactory.getLogger("LAB");

    public record Params(String call, int requests, int patienceMs, boolean retry) {}

    record Attempt(String outcome, long ms, int bytes) {
        boolean heard() { return outcome.startsWith("HTTP_2"); }
    }

    public record Row(String rid, String seat, String call, String first, long firstMs, int firstBytes,
                      boolean applied, String retry, long retryMs, String verdict) {
        String toJson() {
            return "{\"rid\":" + VenueJson.quote(rid) + ",\"seat\":" + VenueJson.quote(seat)
                    + ",\"call\":" + VenueJson.quote(call) + ",\"first\":" + VenueJson.quote(first)
                    + ",\"firstMs\":" + firstMs + ",\"firstBytes\":" + firstBytes
                    + ",\"applied\":" + applied + ",\"retry\":" + VenueJson.quote(retry)
                    + ",\"retryMs\":" + retryMs + ",\"verdict\":" + VenueJson.quote(verdict) + "}";
        }
    }

    private final VenueService venue;
    private final FaultProxy proxy;

    private volatile String state = "idle";
    private volatile Params current;
    private volatile List<Row> rows = List.of();

    public LabRunner(VenueService venue, FaultProxy proxy) {
        this.venue = venue;
        this.proxy = proxy;
    }

    public synchronized boolean start(Params params) {
        if ("running".equals(state)) {
            return false;
        }
        state = "running";
        current = params;
        rows = List.of();
        Thread.ofPlatform().name("lab-run").start(() -> runBlocking(params));
        return true;
    }

    public String toJson() {
        Params p = current;
        if (p == null) {
            return "{\"state\":\"idle\",\"rows\":[]}";
        }
        StringBuilder sb = new StringBuilder("[");
        List<Row> snapshot = rows;
        for (int i = 0; i < snapshot.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(snapshot.get(i).toJson());
        }
        return "{\"state\":\"" + state + "\",\"call\":" + VenueJson.quote(p.call())
                + ",\"requests\":" + p.requests() + ",\"patienceMs\":" + p.patienceMs()
                + ",\"retry\":" + p.retry() + ",\"rows\":" + sb.append("]") + "}";
    }

    public List<Row> runBlocking(Params params) {
        state = "running";
        current = params;
        boolean hold = !"map".equals(params.call());
        int n = Math.max(1, Math.min(params.requests(), Math.min(25, venue.venue().seatsPerRow())));
        int patience = Math.max(100, Math.min(params.patienceMs(), 5_000));
        char lastRow = (char) ('A' + venue.venue().rows() - 1);
        venue.releaseHoldsOf("lab-");

        Attempt[] first = new Attempt[n];
        Attempt[] second = new Attempt[n];
        String[] seats = new String[n];
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                seats[i] = hold ? "%c-%02d".formatted(lastRow, i + 1) : "-";
                pool.submit(() -> {
                    String buyer = "lab-%02d".formatted(idx + 1);
                    String base = "lab-%04d".formatted(idx + 1);
                    first[idx] = attempt(base + "-a1", hold, seats[idx], buyer, patience, false);
                    if (params.retry() && hold && !first[idx].heard()) {
                        second[idx] = attempt(base + "-a2", true, seats[idx], buyer, patience, true);
                    }
                });
            }
        }

        // Late arrivals (a slow link) may still land after the client gave up. Let them settle
        // before asking the server what really happened.
        FaultConfig cfg = proxy.config();
        pause(cfg.mode() == FaultMode.LATENCY ? Math.min(5_000, cfg.delayMs() * 2L + 500) : 300);

        List<Row> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String buyer = "lab-%02d".formatted(i + 1);
            boolean applied = hold && venue.liveHoldOn(new SeatId(seats[i])).map(Hold::buyer).filter(buyer::equals).isPresent();
            Attempt a = first[i];
            Attempt r = second[i];
            out.add(new Row("lab-%04d".formatted(i + 1), seats[i], hold ? "hold" : "map",
                    a.outcome(), a.ms(), a.bytes(), applied,
                    r == null ? "none" : r.outcome(), r == null ? 0 : r.ms(),
                    verdict(hold, patience, a, r, applied)));
            log.info("event=verdict rid=lab-{} heard={} serverApplied={} verdict={}",
                    "%04d".formatted(i + 1), a.heard(), applied, out.get(i).verdict());
        }
        rows = List.copyOf(out);
        state = "done";
        return rows;
    }

    static String verdict(boolean hold, int patience, Attempt a, Attempt r, boolean applied) {
        if (a.heard()) {
            return a.ms() > patience / 2 ? "SLOW" : "FINE";
        }
        if (!hold) {
            return a.bytes() > 0 ? "CUT_OFF" : "NO_REPLY";
        }
        if ("HTTP_409".equals(a.outcome()) && !applied) {
            return "SEAT_TAKEN";
        }
        if (r != null && r.heard()) {
            return "RETRY_RECOVERED";
        }
        if (applied) {
            return r != null && "HTTP_409".equals(r.outcome()) ? "RETRY_BLOCKED" : "UNHEARD_SUCCESS";
        }
        return "LOST_REQUEST";
    }

    // ---- one client, one request, one patience ------------------------------------------

    private Attempt attempt(String rid, boolean hold, String seat, String buyer, int patienceMs, boolean linkRecovered) {
        MDC.put("rid", rid);
        long start = System.nanoTime();
        long deadline = start + patienceMs * 1_000_000L;
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        try (Socket socket = new Socket()) {
            log.info("event=send call={} seat={} patienceMs={}", hold ? "hold" : "map", seat, patienceMs);
            socket.connect(new InetSocketAddress("localhost", proxy.port()), patienceMs);
            String request = hold
                    ? "POST /api/holds?seat=" + seat + "&buyer=" + buyer + " HTTP/1.1\r\nHost: localhost\r\n"
                      + "X-Request-Id: " + rid + "\r\n" + (linkRecovered ? "X-Lab-Link: healthy\r\n" : "")
                      + "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    : "GET /api/venue HTTP/1.1\r\nHost: localhost\r\n"
                      + "X-Request-Id: " + rid + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            while (true) {
                long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0) {
                    throw new SocketTimeoutException("patience ran out");
                }
                socket.setSoTimeout((int) leftMs);
                int read = in.read(buf);
                if (read < 0) {
                    break;
                }
                got.write(buf, 0, read);
            }
            long ms = elapsed(start);
            String outcome = got.size() == 0 ? "NO_REPLY" : status(got.toByteArray());
            log.info("event=outcome result={} ms={} bytes={}", outcome, ms, got.size());
            return new Attempt(outcome, ms, got.size());
        } catch (SocketTimeoutException e) {
            long ms = elapsed(start);
            log.info("event=gave_up afterMs={} bytesSoFar={}", ms, got.size());
            return new Attempt("TIMEOUT", ms, got.size());
        } catch (IOException e) {
            long ms = elapsed(start);
            log.info("event=outcome result=ERROR reason={} ms={}", e.getClass().getSimpleName(), ms);
            return new Attempt("ERROR", ms, got.size());
        } finally {
            MDC.remove("rid");
        }
    }

    private static String status(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 32), StandardCharsets.ISO_8859_1);
        String[] parts = head.split(" ");
        return parts.length >= 2 ? "HTTP_" + parts[1].replaceAll("[^0-9]", "") : "GARBLED";
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
