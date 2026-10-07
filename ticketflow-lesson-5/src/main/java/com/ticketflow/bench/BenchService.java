package com.ticketflow.bench;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs ramps one at a time and keeps their results for the dashboard. */
public final class BenchService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BenchService.class);
    private static final int WARM_UP_SECONDS = 3;

    private final LoadGenerator generator;
    private final BoxClient boxes;
    private final String venueId;
    private final int budgetMillis;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bench-ramp");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicInteger runIds = new AtomicInteger();
    private final List<RunState> runs = new CopyOnWriteArrayList<>();

    public BenchService(LoadGenerator generator, String venueId, int budgetMillis) {
        this.generator = generator;
        this.boxes = new BoxClient(generator.http());
        this.venueId = venueId;
        this.budgetMillis = budgetMillis;
    }

    /** Starts a ramp in the background. Throws IllegalStateException if one is already running. */
    public RunReport start(RampPlan requested) {
        RampPlan plan = requested.validated();
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("a ramp is already running; wait for it to finish");
        }
        RunState state = new RunState("run-" + runIds.incrementAndGet(), plan);
        runs.add(state);
        runner.submit(() -> {
            try {
                execute(state);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                state.fail("interrupted");
            } catch (Exception failure) {
                state.fail(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                log.warn("event=run.failed run={} reason=\"{}\"", state.id, state.note);
            } finally {
                busy.set(false);
            }
        });
        return state.report();
    }

    public boolean busy() {
        return busy.get();
    }

    public List<RunReport> runs() {
        List<RunReport> reports = new ArrayList<>();
        for (RunState state : runs) {
            reports.add(state.report());
        }
        return reports;
    }

    /** Forgets finished runs. A run in progress is kept. */
    public void clear() {
        runs.removeIf(state -> !"RUNNING".equals(state.status));
    }

    /** A plain-text table of every run, for people verifying from a terminal. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "%-8s %-28s %-10s %10s %8s %12s%n",
                "RUN", "LABEL", "STATUS", "KNEE_RPS", "SOLD", "SOLD_TWICE"));
        for (RunReport run : runs()) {
            text.append(String.format(Locale.ROOT, "%-8s %-28s %-10s %10d %8d %12d%n",
                    run.id(), run.label(), run.status(), run.kneeRate(), run.seatsSold(), run.seatsSoldTwice()));
            for (StepResult step : run.steps()) {
                text.append(String.format(Locale.ROOT,
                        "         offered=%-5d answered/s=%-7.1f p50_ms=%-7.1f p99_ms=%-8.1f failed=%-5.1f%% %s%n",
                        step.offeredRate(), step.answeredPerSecond(), step.p50Millis(), step.p99Millis(),
                        step.failureRatio() * 100, step.fellOver() ? "FELL_OVER" : step.healthy() ? "healthy" : "over budget"));
            }
        }
        return text.toString();
    }

    private void execute(RunState state) throws Exception {
        RampPlan plan = state.plan;
        List<String> targets = plan.targets();
        log.info("event=run.started run={} label=\"{}\" targets={} start_rps={} end_rps={} step_rps={} step_seconds={}",
                state.id, plan.label(), targets, plan.startRate(), plan.endRate(), plan.stepRate(), plan.stepSeconds());

        for (String target : targets) {
            boxes.reset(target);
        }
        // A cold JVM is slow for reasons that have nothing to do with capacity. Warm it, then throw the numbers away.
        generator.runStep(state.id + "-warmup", targets, plan.startRate(), WARM_UP_SECONDS, budgetMillis);
        boxes.awaitDrained(targets, Duration.ofSeconds(15));
        for (String target : targets) {
            boxes.reset(target);
        }
        log.info("event=run.warmed run={} warm_up_seconds={}", state.id, WARM_UP_SECONDS);

        boolean fellOver = false;
        for (int rate = plan.startRate(); rate <= plan.endRate(); rate += plan.stepRate()) {
            StepResult step = generator.runStep(state.id, targets, rate, plan.stepSeconds(), budgetMillis);
            state.steps.add(step);
            log.info("event=step.done run={} offered_rps={} answered_rps={} p50_ms={} p99_ms={} failed_pct={} verdict={}",
                    state.id, rate, String.format(Locale.ROOT, "%.1f", step.answeredPerSecond()),
                    String.format(Locale.ROOT, "%.1f", step.p50Millis()),
                    String.format(Locale.ROOT, "%.1f", step.p99Millis()),
                    String.format(Locale.ROOT, "%.1f", step.failureRatio() * 100),
                    step.fellOver() ? "FELL_OVER" : step.healthy() ? "HEALTHY" : "OVER_BUDGET");
            if (step.fellOver()) {
                fellOver = true;
                break;
            }
            boxes.awaitDrained(targets, Duration.ofSeconds(generator.timeoutMillis() / 1000 + 20));
        }
        boxes.awaitDrained(targets, Duration.ofSeconds(30));

        List<Set<String>> soldPerBox = new ArrayList<>();
        for (String target : targets) {
            Set<String> sold = boxes.soldSeatIds(target, venueId);
            soldPerBox.add(sold);
            log.info("event=audit.box run={} target={} seats_sold={}", state.id, target, sold.size());
        }
        Set<String> duplicates = SplitStateAudit.soldMoreThanOnce(soldPerBox);
        state.seatsSold = SplitStateAudit.totalSold(soldPerBox);
        state.seatsSoldTwice = duplicates.size();
        state.status = fellOver ? "FELL_OVER" : "FINISHED";
        log.info("event=run.finished run={} status={} knee_rps={} seats_sold={} seats_sold_twice={}",
                state.id, state.status, state.kneeRate(), state.seatsSold, state.seatsSoldTwice);
    }

    @Override
    public void close() {
        runner.shutdownNow();
    }

    /** Mutable progress for one run; read by HTTP threads while the ramp thread writes. */
    private final class RunState {
        private final String id;
        private final RampPlan plan;
        private final String startedAt = Instant.now().toString();
        private final List<StepResult> steps = new CopyOnWriteArrayList<>();
        private volatile String status = "RUNNING";
        private volatile String note = "";
        private volatile int seatsSold;
        private volatile int seatsSoldTwice;

        private RunState(String id, RampPlan plan) {
            this.id = id;
            this.plan = plan;
        }

        private void fail(String reason) {
            this.note = reason;
            this.status = "FAILED";
        }

        private int kneeRate() {
            int knee = 0;
            for (StepResult step : steps) {
                if (step.healthy()) {
                    knee = Math.max(knee, step.offeredRate());
                }
            }
            return knee;
        }

        private RunReport report() {
            return new RunReport(id, plan.label(), plan.targets(), status, List.copyOf(steps), kneeRate(),
                    budgetMillis, seatsSold, seatsSoldTwice, startedAt, note);
        }
    }
}
