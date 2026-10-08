package com.ticketflow.balancer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs the real balancer against two real HTTP servers on free local ports. */
class LoadBalancerTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    /** Starts a server that waits {@code delayMillis} and then answers with {@code status}. */
    private Backend box(String name, int delayMillis, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] body = ("{\"node\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return new Backend(name, "http://localhost:" + server.getAddress().getPort());
    }

    /** Sends {@code count} bookings, one every {@code gapMillis}, without waiting for answers in between. */
    private static List<LoadBalancer.Forwarded> send(LoadBalancer balancer, int count, int gapMillis) throws Exception {
        List<CompletableFuture<LoadBalancer.Forwarded>> pending = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pending.add(balancer.forward("POST", "/api/bookings", "application/json", "test-" + i, false,
                    "{}".getBytes(StandardCharsets.UTF_8)));
            Thread.sleep(gapMillis);
        }
        List<LoadBalancer.Forwarded> answers = new ArrayList<>();
        for (CompletableFuture<LoadBalancer.Forwarded> future : pending) {
            answers.add(future.get());
        }
        return answers;
    }

    private static long sentTo(LoadBalancer balancer, String name) {
        return balancer.snapshot().stream().filter(view -> view.name().equals(name)).findFirst().orElseThrow().sent();
    }

    @Test
    void theAnswerComesBackWithTheNameOfTheBoxThatGaveIt() throws Exception {
        LoadBalancer balancer = new LoadBalancer(List.of(box("only", 0, 201)), "round-robin", 2_000);
        LoadBalancer.Forwarded answer = send(balancer, 1, 0).get(0);
        assertEquals(201, answer.status());
        assertEquals("only", answer.backend());
        assertEquals("{\"node\":\"only\"}", new String(answer.body(), StandardCharsets.UTF_8));
        assertEquals(0, balancer.snapshot().get(0).inFlight());
    }

    @Test
    void roundRobinSplitsEvenlyEvenWhenOneBoxIsSlow() throws Exception {
        LoadBalancer balancer = new LoadBalancer(List.of(box("fast", 5, 201), box("slow", 150, 201)), "round-robin", 5_000);
        send(balancer, 40, 5);
        assertEquals(20, sentTo(balancer, "fast"));
        assertEquals(20, sentTo(balancer, "slow"));
    }

    @Test
    void leastConnectionsMovesTrafficAwayFromTheSlowBox() throws Exception {
        LoadBalancer balancer = new LoadBalancer(List.of(box("fast", 5, 201), box("slow", 150, 201)), "least-connections", 5_000);
        send(balancer, 40, 5);
        assertTrue(sentTo(balancer, "fast") > 2 * sentTo(balancer, "slow"),
                "fast=" + sentTo(balancer, "fast") + " slow=" + sentTo(balancer, "slow"));
    }

    @Test
    void leastConnectionsPrefersABoxThatFailsInstantlyOverOneThatWorks() throws Exception {
        LoadBalancer balancer = new LoadBalancer(List.of(box("working", 40, 201), box("broken", 0, 503)), "least-connections", 5_000);
        List<LoadBalancer.Forwarded> answers = send(balancer, 60, 5);

        long toBroken = sentTo(balancer, "broken");
        long toWorking = sentTo(balancer, "working");
        assertTrue(toBroken > toWorking, "broken=" + toBroken + " working=" + toWorking);

        long failures = answers.stream().filter(answer -> answer.status() == 503).count();
        assertEquals(toBroken, failures);
        assertEquals(toBroken, balancer.snapshot().stream().filter(view -> view.name().equals("broken")).findFirst().orElseThrow().failed());
    }

    @Test
    void aBoxThatIsNotThereBecomesA502() throws Exception {
        LoadBalancer balancer = new LoadBalancer(List.of(new Backend("gone", "http://localhost:1")), "round-robin", 1_000);
        LoadBalancer.Forwarded answer = send(balancer, 1, 0).get(0);
        assertEquals(502, answer.status());
        assertEquals(1, balancer.snapshot().get(0).failed());
    }

    @Test
    void theStrategyCanBeSwitchedWhileRunning() throws Exception {
        LoadBalancer balancer = new LoadBalancer(List.of(box("a", 0, 201), box("b", 0, 201)), "round-robin", 2_000);
        assertEquals("round-robin", balancer.strategy());
        balancer.useStrategy("least-connections");
        assertEquals("least-connections", balancer.strategy());
        send(balancer, 4, 20);
        balancer.resetCounters();
        assertEquals(0, sentTo(balancer, "a") + sentTo(balancer, "b"));
    }
}
