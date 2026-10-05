package dev.ticketflow.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BudgetTest {

    @Test
    void theDefaultSlicesAndReserveAddUpToTheTotal() {
        Budget budget = new Budget(300, 60, 10, 15, 150, 5, 60);
        assertThat(budget.balanced()).isTrue();
        assertThat(budget.budgetOf("payment")).isEqualTo(150);
        assertThat(budget.budgetOf("nonsense")).isZero();
    }

    @Test
    void aBudgetWithNoReserveOrTooMuchSpendingIsNotBalanced() {
        assertThat(new Budget(300, 60, 10, 15, 150, 5, 0).balanced()).isFalse();
    }

    @Test
    void theServerTimingParserReadsDurationsAndIgnoresDescriptions() {
        String head = "HTTP/1.1 201\r\nServer-Timing: lock;dur=0.12, payment;dur=120.40, left;dur=170.20;desc=\"budget left\"\r\nX: y";
        assertThat(BudgetLab.serverTiming(head)).containsEntry("lock", 0.12).containsEntry("payment", 120.4)
                .containsEntry("left", 170.2);
    }
}
