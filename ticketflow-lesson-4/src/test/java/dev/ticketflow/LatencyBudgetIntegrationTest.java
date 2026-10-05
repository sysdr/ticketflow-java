package dev.ticketflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ticketflow.lab.FaultProxy;
import dev.ticketflow.trace.BudgetLab;
import dev.ticketflow.trace.BudgetLab.Params;
import dev.ticketflow.trace.BudgetLab.Result;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Real server, real link, real stopwatch: the budget points at the hop that is actually slow. */
class LatencyBudgetIntegrationTest {

    @Test
    void theHopThatIsActuallySlowGetsTheBlame() {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(TicketFlowApplication.class,
                "--server.port=18100", "--ticketflow.observability.port=18101",
                "--ticketflow.lab.proxy-port=18102", "--spring.main.banner-mode=off")) {

            FaultProxy proxy = ctx.getBean(FaultProxy.class);
            BudgetLab lab = ctx.getBean(BudgetLab.class);

            // Healthy: payment takes what it takes, everything else is small, the whole booking fits.
            proxy.configure("NONE", 100, 100, 500);
            Result healthy = lab.runBlocking(new Params(30, 5, 120));
            assertThat(healthy.ok()).isEqualTo(30);
            assertThat(healthy.hops().get("payment").p50Ms()).isBetween(118.0, 250.0);
            assertThat(healthy.hops().get("wire").p50Ms()).isLessThan(60.0);
            assertThat(healthy.within()).isEqualTo(30);

            // A slow link: the server's own numbers do not change, but the wire hop does.
            proxy.configure("LATENCY", 100, 100, 500);
            Result slowLink = lab.runBlocking(new Params(20, 5, 120));
            assertThat(slowLink.hops().get("wire").p50Ms()).isGreaterThanOrEqualTo(180.0);
            assertThat(slowLink.worst().getOrDefault("wire", 0)).isEqualTo(20);

            // A slow payment provider: the budget is blown, and the blame lands on payment.
            proxy.configure("NONE", 100, 100, 500);
            Result slowPayment = lab.runBlocking(new Params(20, 5, 400));
            assertThat(slowPayment.within()).isZero();
            assertThat(slowPayment.worst().getOrDefault("payment", 0)).isEqualTo(20);
        }
    }
}
