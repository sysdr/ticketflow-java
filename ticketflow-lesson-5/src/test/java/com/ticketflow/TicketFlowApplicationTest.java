package com.ticketflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketflow.bench.BenchService;
import com.ticketflow.bench.RampPlan;
import com.ticketflow.bench.RunReport;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Boots a real box on a free port and talks to it over real HTTP. */
class TicketFlowApplicationTest {

    private static ConfigurableApplicationContext context;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startBox() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        context = SpringApplication.run(TicketFlowApplication.class,
                "--server.port=" + port,
                "--ticketflow.box.node=test-box",
                "--ticketflow.box.cores=2",
                "--ticketflow.signing.rounds=2000");
        base = "http://localhost:" + port;
    }

    @AfterAll
    static void stopBox() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void aSeatIsBookedOnceAndRefusedTheSecondTime() throws Exception {
        post("/api/admin/reset", "");
        String body = "{\"venueId\":\"riverside\",\"seatId\":\"R03-07\",\"buyerId\":\"ana\"}";

        HttpResponse<String> first = post("/api/bookings", body);
        assertEquals(201, first.statusCode());
        assertTrue(first.body().contains("\"node\":\"test-box\""));
        assertTrue(first.headers().firstValue("Server-Timing").orElse("").contains("sign;dur="));
        assertNotNull(first.headers().firstValue("X-Request-Id").orElse(null));

        HttpResponse<String> second = post("/api/bookings", body);
        assertEquals(409, second.statusCode());
        assertTrue(second.body().contains("SEAT_TAKEN"));

        assertEquals("[\"R03-07\"]", get("/api/venues/riverside/sold").body());
    }

    @Test
    void aRampAgainstOneBoxRecordsStepsAndNeverSellsASeatTwice() throws Exception {
        BenchService bench = context.getBean(BenchService.class);
        bench.start(new RampPlan("self-test", List.of(base), 20, 40, 20, 1));
        long deadline = System.currentTimeMillis() + 60_000;
        while (bench.busy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        RunReport run = bench.runs().get(bench.runs().size() - 1);
        assertEquals("FINISHED", run.status(), run.note());
        assertEquals(2, run.steps().size());
        assertEquals(20, run.steps().get(0).offeredRate());
        assertEquals(20, run.steps().get(0).booked() + run.steps().get(0).soldOut());
        assertEquals(0, run.seatsSoldTwice());
        assertTrue(run.seatsSold() > 0 && run.seatsSold() <= 500);
        assertTrue(get("/api/bench/summary").body().contains("self-test"));
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
