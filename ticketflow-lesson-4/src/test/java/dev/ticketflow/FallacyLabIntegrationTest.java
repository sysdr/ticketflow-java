package dev.ticketflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.ticketflow.inventory.VenueService;
import dev.ticketflow.lab.FaultProxy;
import dev.ticketflow.lab.LabRunner;
import dev.ticketflow.lab.LabRunner.Params;
import dev.ticketflow.lab.LabRunner.Row;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Real server, real flaky link, real sockets: what the client heard versus what the server did. */
class FallacyLabIntegrationTest {

    private static List<String> verdicts(List<Row> rows) {
        return rows.stream().map(Row::verdict).distinct().toList();
    }

    @Test
    void theClientAndTheServerDisagreeWhenTheLinkMisbehaves() {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(TicketFlowApplication.class,
                "--server.port=18090", "--ticketflow.observability.port=18091",
                "--ticketflow.lab.proxy-port=18092", "--spring.main.banner-mode=off")) {

            FaultProxy proxy = ctx.getBean(FaultProxy.class);
            LabRunner lab = ctx.getBean(LabRunner.class);
            VenueService venue = ctx.getBean(VenueService.class);

            proxy.configure("NONE", 400, 100, 500);
            assertThat(verdicts(lab.runBlocking(new Params("hold", 5, 1_500, false)))).containsExactly("FINE");

            // The reply is lost: the server holds the seat, the client heard nothing, and the retry
            // runs into the client's own hold.
            proxy.configure("LOSE_RESPONSE", 400, 100, 500);
            List<Row> lostReply = lab.runBlocking(new Params("hold", 5, 600, true));
            assertThat(verdicts(lostReply)).containsExactly("RETRY_BLOCKED");
            assertThat(lostReply).allMatch(Row::applied);

            // The request is lost: nothing happened, and a retry on a recovered link simply works.
            proxy.configure("LOSE_REQUEST", 400, 100, 500);
            List<Row> lostRequest = lab.runBlocking(new Params("hold", 5, 600, false));
            assertThat(verdicts(lostRequest)).containsExactly("LOST_REQUEST");
            assertThat(venue.snapshot().held()).isZero();
            assertThat(verdicts(lab.runBlocking(new Params("hold", 5, 600, true)))).containsExactly("RETRY_RECOVERED");

            // A link slower than the client's patience: the request still lands, after the client left.
            proxy.configure("LATENCY", 400, 100, 500);
            List<Row> slow = lab.runBlocking(new Params("hold", 5, 600, false));
            assertThat(verdicts(slow)).containsExactly("UNHEARD_SUCCESS");
            assertThat(slow).allMatch(Row::applied);

            // A thin pipe: the seat map starts arriving and is cut off by the client's patience.
            proxy.configure("THROTTLE", 400, 100, 300);
            List<Row> thin = lab.runBlocking(new Params("map", 3, 1_500, false));
            assertThat(verdicts(thin)).containsExactly("CUT_OFF");
            assertThat(thin).allMatch(r -> r.firstBytes() > 0);
        }
    }
}
