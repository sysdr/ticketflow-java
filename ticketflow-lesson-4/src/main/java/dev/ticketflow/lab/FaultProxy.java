package dev.ticketflow.lab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * A flaky link between a client and TicketFlow, on a real TCP port.
 *
 * <p>It reads one HTTP request, forwards it to the real server over a real socket, reads the real
 * reply, and then misbehaves in the way the current {@link FaultConfig} says. It works at message
 * level and handles one request per connection, which is all the lab client sends.
 */
@Component
public class FaultProxy implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger("PROXY");
    private static final byte[] HEAD_END = {'\r', '\n', '\r', '\n'};

    private final int port;
    private final int upstreamPort;
    private final AtomicReference<FaultConfig> config = new AtomicReference<>(FaultConfig.healthy());
    private final AtomicLong seen = new AtomicLong();
    private final AtomicLong faulted = new AtomicLong();
    private final AtomicInteger anonymous = new AtomicInteger();
    private volatile ServerSocket serverSocket;

    public FaultProxy(@Value("${ticketflow.lab.proxy-port:8082}") int port,
                      @Value("${server.port:8080}") int upstreamPort) {
        this.port = port;
        this.upstreamPort = upstreamPort;
    }

    public int port() { return port; }

    public FaultConfig config() { return config.get(); }

    public void configure(String mode, int delayMs, int percent, int bytesPerSec) {
        FaultMode parsed;
        try {
            parsed = FaultMode.valueOf(mode == null ? "NONE" : mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            parsed = FaultMode.NONE;
        }
        FaultConfig next = new FaultConfig(parsed,
                Math.max(0, Math.min(5_000, delayMs)),
                Math.max(0, Math.min(100, percent)),
                Math.max(50, Math.min(1_000_000, bytesPerSec)));
        config.set(next);
        log.info("event=fault_configured mode={} delayMs={} percent={} bytesPerSec={}",
                next.mode(), next.delayMs(), next.percent(), next.bytesPerSec());
    }

    public String statsJson() {
        return "{\"seen\":" + seen.get() + ",\"faulted\":" + faulted.get() + "}";
    }

    // ---- lifecycle ----------------------------------------------------------------------

    @Override
    public void start() {
        try {
            serverSocket = new ServerSocket(port, 256);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind lab proxy port " + port, e);
        }
        Thread.ofPlatform().daemon().name("fault-proxy-accept").start(this::acceptLoop);
        log.info("event=proxy_up listen={} upstream={}", port, upstreamPort);
    }

    @Override
    public void stop() {
        ServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // shutting down
            }
        }
    }

    @Override
    public boolean isRunning() {
        return serverSocket != null;
    }

    // ---- the link -----------------------------------------------------------------------

    private void acceptLoop() {
        ServerSocket s = serverSocket;
        while (s != null && !s.isClosed()) {
            try {
                Socket client = s.accept();
                Thread.startVirtualThread(() -> handle(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(10_000);
            byte[] head = readHead(client.getInputStream());
            if (head == null) {
                return;
            }
            String rid = header(new String(head, StandardCharsets.ISO_8859_1), "X-Request-Id");
            MDC.put("rid", rid != null ? rid : "proxy-" + anonymous.incrementAndGet());

            FaultConfig cfg = config.get();
            // A retry that carries X-Lab-Link: healthy models "the blip is over by the time you try again".
            boolean recovered = "healthy".equalsIgnoreCase(header(new String(head, StandardCharsets.ISO_8859_1), "X-Lab-Link"));
            boolean hit = !recovered && cfg.mode() != FaultMode.NONE
                    && ThreadLocalRandom.current().nextInt(100) < cfg.percent();
            FaultMode fault = hit ? cfg.mode() : FaultMode.NONE;
            seen.incrementAndGet();
            if (hit) {
                faulted.incrementAndGet();
            }
            log.info("event=request_seen bytes={} fault={}{}", head.length, fault, recovered ? " link=recovered" : "");

            if (fault == FaultMode.LOSE_REQUEST) {
                log.info("event=fault_applied mode=LOSE_REQUEST detail=request_swallowed_server_never_sees_it");
                waitForClientToLeave(client);
                return;
            }
            if (fault == FaultMode.LATENCY) {
                pause(cfg.delayMs());
            }

            byte[] reply;
            try (Socket upstream = new Socket()) {
                upstream.connect(new InetSocketAddress("localhost", upstreamPort), 5_000);
                upstream.setSoTimeout(15_000);
                upstream.getOutputStream().write(head);
                upstream.getOutputStream().flush();
                reply = upstream.getInputStream().readAllBytes();
            }
            log.info("event=upstream_replied bytes={}", reply.length);

            switch (fault) {
                case LOSE_RESPONSE -> {
                    log.info("event=fault_applied mode=LOSE_RESPONSE detail=reply_swallowed_server_already_did_the_work");
                    waitForClientToLeave(client);
                }
                case LATENCY -> {
                    pause(cfg.delayMs());
                    deliver(client, reply);
                }
                case THROTTLE -> deliverSlowly(client, reply, cfg.bytesPerSec());
                default -> deliver(client, reply);
            }
        } catch (IOException e) {
            log.info("event=link_error reason={}", e.getClass().getSimpleName());
        } finally {
            MDC.remove("rid");
        }
    }

    private static void deliver(Socket client, byte[] reply) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(reply);
        out.flush();
        log.info("event=reply_delivered bytes={}", reply.length);
    }

    private static void deliverSlowly(Socket client, byte[] reply, int bytesPerSec) throws IOException {
        int chunk = Math.max(1, bytesPerSec / 10);
        OutputStream out = client.getOutputStream();
        int sent = 0;
        try {
            while (sent < reply.length) {
                int n = Math.min(chunk, reply.length - sent);
                out.write(reply, sent, n);
                out.flush();
                sent += n;
                pause(100);
            }
            log.info("event=fault_applied mode=THROTTLE bytesPerSec={} delivered={}", bytesPerSec, sent);
        } catch (IOException e) {
            log.info("event=client_gone mode=THROTTLE delivered={} of={}", sent, reply.length);
        }
    }

    private static void waitForClientToLeave(Socket client) {
        try {
            client.setSoTimeout(30_000);
            int b = client.getInputStream().read();
            log.info("event=client_gave_up {}", b < 0 ? "closed_connection" : "sent_more_data");
        } catch (IOException e) {
            log.info("event=client_wait_ended reason={}", e.getClass().getSimpleName());
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        while (buf.size() < 16_384) {
            int b = in.read();
            if (b < 0) {
                return buf.size() == 0 ? null : buf.toByteArray();
            }
            buf.write(b);
            matched = b == HEAD_END[matched] ? matched + 1 : (b == HEAD_END[0] ? 1 : 0);
            if (matched == HEAD_END.length) {
                return buf.toByteArray();
            }
        }
        return buf.toByteArray();
    }

    private static String header(String head, String name) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
