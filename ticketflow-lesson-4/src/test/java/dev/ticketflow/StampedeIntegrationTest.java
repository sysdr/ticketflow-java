package dev.ticketflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ticketflow.inventory.SeatInventory;
import dev.ticketflow.loadgen.Stampede;
import dev.ticketflow.logging.TraceSampler;
import dev.ticketflow.metrics.ServerStats;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Real server, real HTTP, small and fast: every buyer is answered and exactly 500 seats sell. */
class StampedeIntegrationTest {

    @Test
    void everyBuyerIsAnsweredAndExactlyFiveHundredSeatsSell() {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(TicketFlowApplication.class,
                "--server.port=18080",
                "--ticketflow.observability.port=18081",
                "--server.tomcat.threads.max=16",
                "--ticketflow.booking.work-ms=5",
                "--spring.main.banner-mode=off")) {

            Stampede stampede = new Stampede("http://localhost:18080", 1_200, 500, 30_000,
                    ctx.getBean(TraceSampler.class));
            stampede.run();

            assertThat(stampede.ok()).isEqualTo(500);
            assertThat(stampede.ok() + stampede.soldOut()).isEqualTo(1_200);
            assertThat(stampede.timedOut() + stampede.failed()).isZero();
            assertThat(ctx.getBean(SeatInventory.class).sold()).isEqualTo(500);
            assertThat(ctx.getBean(ServerStats.class).soldCount()).isEqualTo(500);
            assertThat(ctx.getBean(ServerStats.class).ghostCompleted()).isZero();
        }
    }
}
