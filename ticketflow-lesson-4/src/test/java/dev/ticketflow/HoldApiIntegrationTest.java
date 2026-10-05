package dev.ticketflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** The real box-office API over real HTTP: hold, collide, confirm, and look at the venue. */
class HoldApiIntegrationTest {
    private static final String BASE = "http://localhost:18082";
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void holdCollideConfirmAndInspect() throws Exception {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(TicketFlowApplication.class,
                "--server.port=18082", "--ticketflow.observability.port=18083",
                "--spring.main.banner-mode=off")) {

            HttpResponse<String> first = send(HttpRequest.newBuilder(URI.create(BASE + "/api/holds?seat=A-01&buyer=sam"))
                    .header("X-Request-Id", "it-0001").POST(BodyPublishers.noBody()));
            assertThat(first.statusCode()).isEqualTo(201);
            assertThat(first.body()).contains("\"state\":\"ACTIVE\"");
            Matcher id = Pattern.compile("\"id\":\"(h-\\d+)\"").matcher(first.body());
            assertThat(id.find()).isTrue();

            HttpResponse<String> clash = send(HttpRequest.newBuilder(URI.create(BASE + "/api/holds?seat=A-01&buyer=alex"))
                    .POST(BodyPublishers.noBody()));
            assertThat(clash.statusCode()).isEqualTo(409);
            assertThat(clash.body()).contains("SEAT_UNAVAILABLE");

            HttpResponse<String> confirmed = send(HttpRequest.newBuilder(URI.create(BASE + "/api/holds/" + id.group(1) + "/confirm"))
                    .POST(BodyPublishers.noBody()));
            assertThat(confirmed.statusCode()).isEqualTo(200);
            assertThat(confirmed.body()).contains("\"seat\":\"A-01\"");

            HttpResponse<String> again = send(HttpRequest.newBuilder(URI.create(BASE + "/api/holds/" + id.group(1)))
                    .DELETE());
            assertThat(again.statusCode()).isEqualTo(409);

            HttpResponse<String> venue = send(HttpRequest.newBuilder(URI.create(BASE + "/api/venue")).GET());
            assertThat(venue.statusCode()).isEqualTo(200);
            assertThat(venue.body()).contains("\"booked\":1");
        }
    }
}
