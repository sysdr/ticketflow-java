package dev.ticketflow.observability;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ticketflow.loadgen.StampedeRunner;
import dev.ticketflow.metrics.ServerStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The dashboard lives on its own port and its own threads. If it shared Tomcat's
 * 50-thread pool, the page you use to watch the overload would queue behind the
 * overload and freeze at the moment it matters most.
 */
@Component
public class ObservabilityServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger("DASHBOARD");

    private final int port;
    private final ServerStats stats;
    private final StampedeRunner runner;
    private HttpServer server;

    public ObservabilityServer(@Value("${ticketflow.observability.port:8081}") int port,
                               ServerStats stats, StampedeRunner runner) {
        this.port = port;
        this.stats = stats;
        this.runner = runner;
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
        server.createContext("/state", this::state);
        server.createContext("/start", this::startRun);
        server.createContext("/reset", this::reset);
        server.start();
        log.info("event=dashboard_up url=http://localhost:{}/", port);
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
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            send(ex, 404, "text/plain", "not found");
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/dashboard/index.html")) {
            if (in == null) {
                send(ex, 500, "text/plain", "dashboard missing from classpath");
                return;
            }
            send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void state(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json", "{\"server\":" + stats.toJson() + ",\"run\":" + runner.toJson() + "}");
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

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    out.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        return out;
    }

    private static int clamp(String value, int fallback, int lo, int hi) {
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(value)));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
