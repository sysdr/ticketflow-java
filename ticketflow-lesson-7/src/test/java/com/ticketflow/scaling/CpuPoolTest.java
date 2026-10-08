package com.ticketflow.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketflow.lifecycle.StageTimer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CpuPoolTest {

    @Test
    void aOneCoreBoxMakesTheSecondRequestWaitInLine() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (CpuPool pool = new CpuPool(1)) {
            CountDownLatch firstIsOnTheCore = new CountDownLatch(1);
            CountDownLatch letFirstFinish = new CountDownLatch(1);
            StageTimer firstTimer = new StageTimer();
            StageTimer secondTimer = new StageTimer();

            Future<String> first = callers.submit(() -> pool.run(() -> {
                firstIsOnTheCore.countDown();
                letFirstFinish.await();
                return "first";
            }, firstTimer));
            assertTrue(firstIsOnTheCore.await(5, TimeUnit.SECONDS));

            Future<String> second = callers.submit(() -> pool.run(() -> "second", secondTimer));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pool.queueDepth() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1, pool.queueDepth());
            assertEquals(1, pool.busy());

            Thread.sleep(60);
            letFirstFinish.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
            assertTrue(secondTimer.millisOf("queue") >= 50, "the second request should have queued behind the first");
            assertTrue(secondTimer.serverTiming().startsWith("queue;dur="));
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void theSignerIsDeterministicAndCostsMoreWithMoreRounds() {
        TicketSigner cheap = TicketSigner.withRounds(1_000);
        assertEquals(cheap.sign("riverside", "R01-01", "ana"), cheap.sign("riverside", "R01-01", "ana"));
        assertEquals(16, cheap.sign("riverside", "R01-01", "ana").length());
        assertTrue(!cheap.sign("riverside", "R01-01", "ana").equals(cheap.sign("riverside", "R01-02", "ana")));
    }
}
