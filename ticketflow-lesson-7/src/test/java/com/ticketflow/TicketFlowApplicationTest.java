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
                "--ticketflow.signing.rounds=2000",
                "--ticketflow.balancer.backends[0].name=itself",
                "--ticketflow.balancer.backends[0].url=http://localhost:" + port);
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
        bench.start(RampPlan.direct("self-test", List.of(base), 20, 40, 20, 1));
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

    @Test
    void aBookingSentThroughTheBalancerReachesABoxAndComesBackLabelled() throws Exception {
        String body = "{\"venueId\":\"riverside\",\"seatId\":\"R09-09\",\"buyerId\":\"lin\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/lb/api/bookings"))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "through-the-balancer-1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> answer = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertTrue(answer.statusCode() == 201 || answer.statusCode() == 409, "status " + answer.statusCode());
        assertEquals("itself", answer.headers().firstValue("X-Backend").orElse(""));
        assertEquals("through-the-balancer-1", answer.headers().firstValue("X-Request-Id").orElse(""));
        assertTrue(answer.headers().firstValue("Server-Timing").orElse("").contains("lb;dur="));
        assertTrue(get("/api/balancer").body().contains("\"strategy\":\"round-robin\""));
    }

    @Test
    void aBoxInFailFastModeRefusesWith503UntilTheFaultIsSwitchedOff() throws Exception {
        String body = "{\"venueId\":\"riverside\",\"seatId\":\"R10-10\",\"buyerId\":\"mo\"}";
        assertEquals(200, post("/api/admin/fault/fail-fast", "").statusCode());
        try {
            HttpResponse<String> refused = post("/api/bookings", body);
            assertEquals(503, refused.statusCode());
            assertTrue(refused.body().contains("BOX_BROKEN"));
        } finally {
            post("/api/admin/fault/none", "");
        }
        assertTrue(get("/api/box").body().contains("\"fault\":\"none\""));
    }

    @Test
    void aSignedCheckoutCanBeConfirmedAndATamperedOneCannot() throws Exception {
        post("/api/admin/reset", "");
        String body = "{\"venueId\":\"riverside\",\"seatId\":\"R12-04\",\"buyerId\":\"noor\"}";
        HttpResponse<String> started = post("/api/checkout/start", body);
        assertEquals(201, started.statusCode());
        assertTrue(started.body().contains("\"sessionKind\":\"signed\""));
        String token = started.body().replaceAll(".*\"session\":\"([^\"]+)\".*", "$1");

        char last = token.charAt(token.length() - 1);
        HttpResponse<String> tampered = confirm(token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A'));
        assertEquals(401, tampered.statusCode());
        assertTrue(tampered.body().contains("SESSION_TAMPERED"));

        HttpResponse<String> booked = confirm(token);
        assertEquals(201, booked.statusCode());
        assertTrue(booked.body().contains("\"buyerId\":\"noor\""));
    }

    @Test
    void aServerSessionIsGoneAfterARestart() throws Exception {
        post("/api/admin/reset", "");
        post("/api/admin/checkout/server", "");
        try {
            String body = "{\"venueId\":\"riverside\",\"seatId\":\"R13-05\",\"buyerId\":\"oli\"}";
            String started = post("/api/checkout/start", body).body();
            String session = started.replaceAll(".*\"session\":\"([^\"]+)\".*", "$1");
            assertTrue(get("/api/box").body().contains("\"liveSessions\":1"));

            post("/api/admin/restart", "");
            HttpResponse<String> lost = confirm(session);
            assertEquals(401, lost.statusCode());
            assertTrue(lost.body().contains("SESSION_UNKNOWN"));
        } finally {
            post("/api/admin/checkout/signed", "");
        }
    }

    private static HttpResponse<String> confirm(String session) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/checkout/confirm"))
                .header("Content-Type", "application/json")
                .header("X-Checkout-Session", session)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
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
