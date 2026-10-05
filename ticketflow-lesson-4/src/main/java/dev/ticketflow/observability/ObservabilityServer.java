package dev.ticketflow.observability;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ticketflow.domain.DomainException;
import dev.ticketflow.domain.SeatId;
import dev.ticketflow.inventory.EventLog;
import dev.ticketflow.inventory.VenueService;
import dev.ticketflow.lab.FaultProxy;
import dev.ticketflow.lab.LabRunner;
import dev.ticketflow.loadgen.StampedeRunner;
import dev.ticketflow.trace.BudgetLab;
import dev.ticketflow.metrics.ServerStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The dashboards live on their own port and their own threads. If they shared Tomcat's
 * 50-thread pool, the page you use to watch an overload would queue behind the overload.
 */
@Component
public class ObservabilityServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger("DASHBOARD");

    private static final Map<String, String> PAGES = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/box-office", "box-office.html",
            "/fallacy-lab", "fallacy-lab.html",
            "/latency-budget", "latency-budget.html");

    private final int port;
    private final ServerStats stats;
    private final StampedeRunner runner;
    private final VenueService venue;
    private final EventLog events;
    private final FaultProxy proxy;
    private final LabRunner lab;
    private final BudgetLab budgetLab;
    private HttpServer server;

    public ObservabilityServer(@Value("${ticketflow.observability.port:8081}") int port,
                               ServerStats stats, StampedeRunner runner, VenueService venue, EventLog events,
                               FaultProxy proxy, LabRunner lab, BudgetLab budgetLab) {
        this.port = port;
        this.stats = stats;
        this.runner = runner;
        this.venue = venue;
        this.events = events;
        this.proxy = proxy;
        this.lab = lab;
        this.budgetLab = budgetLab;
    }

    @Override
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind observability port " + port, e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::page);
        server.createContext("/state", ex -> send(ex, 200, "application/json",
                "{\"server\":" + stats.toJson() + ",\"run\":" + runner.toJson() + "}"));
        server.createContext("/start", this::startRun);
        server.createContext("/reset", this::reset);
        server.createContext("/venue", ex -> send(ex, 200, "application/json",
                VenueJson.snapshot(venue.snapshot(), events.recent())));
        server.createContext("/hold", ex -> action(ex, "ui_hold", q ->
                VenueJson.hold(venue.placeHold(new SeatId(q.getOrDefault("seat", "")), q.get("buyer"))), 201));
        server.createContext("/confirm", ex -> action(ex, "ui_confirm", q ->
                VenueJson.booking(venue.confirm(q.getOrDefault("hold", ""))), 200));
        server.createContext("/release", ex -> action(ex, "ui_release", q ->
                VenueJson.hold(venue.release(q.getOrDefault("hold", ""))), 200));
        server.createContext("/lab/state", ex -> send(ex, 200, "application/json",
                "{\"fault\":" + proxy.config().toJson() + ",\"proxy\":" + proxy.statsJson()
                        + ",\"run\":" + lab.toJson() + "}"));
        server.createContext("/budget/state", ex -> send(ex, 200, "application/json",
                "{\"link\":" + proxy.config().toJson() + ",\"lab\":" + budgetLab.toJson() + "}"));
        server.createContext("/budget/run", this::budgetRun);
        server.createContext("/lab/fault", this::labFault);
        server.createContext("/lab/run", this::labRun);
        server.start();
        log.info("event=dashboard_up url=http://localhost:{}/ boxOffice=http://localhost:{}/box-office", port, port);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    private void page(HttpExchange ex) throws IOException {
        String file = PAGES.get(ex.getRequestURI().getPath());
        if (file == null) {
            send(ex, 404, "text/plain", "not found");
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/dashboard/" + file)) {
            if (in == null) {
                send(ex, 500, "text/plain", "dashboard page missing from classpath");
                return;
            }
            send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private interface DomainAction {
        String run(Map<String, String> query);
    }

    private void action(HttpExchange ex, String event, DomainAction action, int okStatus) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        Map<String, String> q = query(ex);
        String rid = "ui-" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put("rid", rid);
        try {
            log.info("event={} {}", event, q);
            send(ex, okStatus, "application/json", action.run(q));
        } catch (DomainException e) {
            log.info("event=rejected code={} message={}", e.code(), e.getMessage());
            send(ex, e.code().status(), "application/json", VenueJson.error(e));
        } finally {
            MDC.remove("rid");
        }
    }

    private void labFault(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        Map<String, String> q = query(ex);
        proxy.configure(q.get("mode"), clamp(q.get("delayMs"), 400, 0, 5_000),
                clamp(q.get("percent"), 100, 0, 100), clamp(q.get("bytesPerSec"), 2_000, 50, 1_000_000));
        send(ex, 200, "application/json", proxy.config().toJson());
    }

    private void budgetRun(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        Map<String, String> q = query(ex);
        boolean started = budgetLab.start(new BudgetLab.Params(
                clamp(q.get("requests"), 100, 1, 500),
                clamp(q.get("parallel"), 10, 1, 300),
                clamp(q.get("paymentMs"), 120, 0, 2_000)));
        send(ex, started ? 202 : 409, "application/json", "{\"started\":" + started + "}");
    }

    private void labRun(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        Map<String, String> q = query(ex);
        boolean started = lab.start(new LabRunner.Params(
                "map".equals(q.get("call")) ? "map" : "hold",
                clamp(q.get("requests"), 10, 1, 25),
                clamp(q.get("patienceMs"), 1_500, 100, 5_000),
                "true".equals(q.get("retry"))));
        send(ex, started ? 202 : 409, "application/json", "{\"started\":" + started + "}");
    }

    private void startRun(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        Map<String, String> q = query(ex);
        int buyers = clamp(q.get("buyers"), 10_000, 1, 20_000);
        long windowMs = clamp(q.get("windowMs"), 1_000, 100, 60_000);
        long patienceMs = clamp(q.get("patienceMs"), 2_000, 100, 60_000);
        boolean started = runner.start(buyers, windowMs, patienceMs);
        send(ex, started ? 202 : 409, "application/json", "{\"started\":" + started + "}");
    }

    private void reset(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        boolean ok = runner.reset();
        send(ex, ok ? 200 : 409, "application/json", "{\"reset\":" + ok + "}");
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    static int clamp(String value, int fallback, int lo, int hi) {
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(value)));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
