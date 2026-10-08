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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ScheduledExecutorService restarts = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bench-restart");
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
        text.append(String.format(Locale.ROOT, "%-7s %-34s %-18s %-8s %-10s %8s %8s %9s %6s %10s  %s%n",
                "RUN", "LABEL", "STRATEGY", "SESSIONS", "STATUS", "KNEE_RPS", "FAILED%", "LOST_SESS", "SOLD",
                "SOLD_TWICE", "TRAFFIC_SPLIT"));
        for (RunReport run : runs()) {
            long offered = 0;
            long failed = 0;
            long lost = 0;
            for (StepResult step : run.steps()) {
                offered += step.offered();
                failed += step.errors() + step.timeouts() + step.sessionLost() + step.shed();
                lost += step.sessionLost();
            }
            StringBuilder split = new StringBuilder();
            long forwarded = run.shares().stream().mapToLong(BackendShare::sent).sum();
            for (BackendShare share : run.shares()) {
                split.append(String.format(Locale.ROOT, "%s=%.0f%% ", share.name(),
                        forwarded == 0 ? 0.0 : 100.0 * share.sent() / forwarded));
            }
            text.append(String.format(Locale.ROOT, "%-7s %-34s %-18s %-8s %-10s %8d %8.1f %9d %6d %10d  %s%n",
                    run.id(), run.label(), run.strategy().isEmpty() ? "-" : run.strategy(),
                    run.checkout().isEmpty() ? "-" : run.checkout(), run.status(),
                    run.kneeRate(), offered == 0 ? 0.0 : 100.0 * failed / offered, lost,
                    run.seatsSold(), run.seatsSoldTwice(), split.toString().trim()));
            for (StepResult step : run.steps()) {
                text.append(String.format(Locale.ROOT,
                        "        offered=%-5d answered/s=%-7.1f p50_ms=%-7.1f p99_ms=%-8.1f failed=%-5.1f%% %s%n",
                        step.offeredRate(), step.answeredPerSecond(), step.p50Millis(), step.p99Millis(),
                        step.failureRatio() * 100, verdict(step, run.budgetMillis())));
            }
        }
        return text.toString();
    }

    /** Says which rule a step broke: too many failures, or a p99 outside the budget. */
    static String verdict(StepResult step, int budgetMillis) {
        if (step.fellOver()) {
            return "FELL_OVER";
        }
        if (step.healthy()) {
            return "healthy";
        }
        return step.p99Millis() > budgetMillis ? "over budget" : "failures";
    }

    private void execute(RunState state) throws Exception {
        RampPlan plan = state.plan;
        List<String> targets = plan.targets();
        List<String> entryPoints = plan.entryPoints();
        log.info("event=run.started run={} label=\"{}\" strategy={} sessions={} fault=\"{}\" entry={} boxes={} start_rps={} end_rps={} step_rps={} step_seconds={}",
                state.id, plan.label(), state.strategy(), plan.twoStep() ? plan.checkout() : "one-shot", state.fault(),
                entryPoints, targets, plan.startRate(), plan.endRate(), plan.stepRate(), plan.stepSeconds());

        for (String target : targets) {
            boxes.reset(target);
            if (plan.twoStep()) {
                boxes.useSessions(target, plan.checkout());
            }
        }
        if (plan.balancer() != null) {
            boxes.useStrategy(plan.balancer(), plan.strategy());
        }
        // A cold JVM is slow for reasons that have nothing to do with capacity. Warm it, then throw the numbers away.
        generator.runStep(state.id + "-warmup", entryPoints, plan.startRate(), WARM_UP_SECONDS, budgetMillis,
                plan.twoStep());
        boxes.awaitDrained(targets, Duration.ofSeconds(15));
        for (String target : targets) {
            boxes.reset(target);
        }
        log.info("event=run.warmed run={} warm_up_seconds={}", state.id, WARM_UP_SECONDS);

        boolean fellOver = false;
        try {
            if (plan.fault() != null && plan.fault().restart()) {
                String target = plan.fault().target();
                long halfway = plan.stepSeconds() * 500L;
                restarts.schedule(() -> {
                    try {
                        boxes.restart(target);
                        log.info("event=fault.restart run={} target={}", state.id, target);
                    } catch (Exception failed) {
                        log.warn("event=fault.restart_failed run={} target={} reason=\"{}\"", state.id, target, failed);
                    }
                }, halfway, TimeUnit.MILLISECONDS);
            } else if (plan.fault() != null) {
                boxes.fault(plan.fault().target(), plan.fault().mode(), plan.fault().factor());
                log.info("event=fault.on run={} target={} fault=\"{}\"", state.id, plan.fault().target(), plan.fault().describe());
            }
            if (plan.balancer() != null) {
                boxes.resetBalancerCounters(plan.balancer());
            }
            for (int rate = plan.startRate(); rate <= plan.endRate(); rate += plan.stepRate()) {
                StepResult step = generator.runStep(state.id, entryPoints, rate, plan.stepSeconds(), budgetMillis,
                        plan.twoStep());
                state.steps.add(step);
                log.info("event=step.done run={} offered_rps={} answered_rps={} p50_ms={} p99_ms={} failed_pct={} verdict={}",
                        state.id, rate, String.format(Locale.ROOT, "%.1f", step.answeredPerSecond()),
                        String.format(Locale.ROOT, "%.1f", step.p50Millis()),
                        String.format(Locale.ROOT, "%.1f", step.p99Millis()),
                        String.format(Locale.ROOT, "%.1f", step.failureRatio() * 100),
                        verdict(step, budgetMillis).toUpperCase(Locale.ROOT).replace(' ', '_'));
                if (step.fellOver()) {
                    fellOver = true;
                    break;
                }
                boxes.awaitDrained(targets, Duration.ofSeconds(generator.timeoutMillis() / 1000 + 20));
            }
            boxes.awaitDrained(targets, Duration.ofSeconds(30));

            if (plan.balancer() != null) {
                state.shares = boxes.shares(plan.balancer());
                for (BackendShare share : state.shares) {
                    log.info("event=audit.share run={} strategy={} backend={} sent={} failed={}",
                            state.id, plan.strategy(), share.name(), share.sent(), share.failed());
                }
            }
            List<Set<String>> soldPerBox = new ArrayList<>();
            for (String target : targets) {
                Set<String> sold = boxes.soldSeatIds(target, venueId);
                soldPerBox.add(sold);
                log.info("event=audit.box run={} target={} seats_sold={}", state.id, target, sold.size());
            }
            state.seatsSold = SplitStateAudit.totalSold(soldPerBox);
            state.seatsSoldTwice = SplitStateAudit.soldMoreThanOnce(soldPerBox).size();
        } finally {
            if (plan.fault() != null && !plan.fault().restart()) {
                boxes.fault(plan.fault().target(), "none", 1);
                log.info("event=fault.off run={} target={}", state.id, plan.fault().target());
            }
        }
        state.status = fellOver ? "FELL_OVER" : "FINISHED";
        log.info("event=run.finished run={} status={} knee_rps={} seats_sold={} seats_sold_twice={}",
                state.id, state.status, state.kneeRate(), state.seatsSold, state.seatsSoldTwice);
    }

    @Override
    public void close() {
        restarts.shutdownNow();
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
        private volatile List<BackendShare> shares = List.of();

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

        private String strategy() {
            return plan.strategy() == null ? "" : plan.strategy();
        }

        private String fault() {
            return plan.fault() == null ? "" : plan.fault().describe() + " on " + plan.fault().target();
        }

        private RunReport report() {
            return new RunReport(id, plan.label(), plan.group(), plan.targets(), strategy(),
                    plan.checkout() == null ? "" : plan.checkout(), fault(), status,
                    List.copyOf(steps), kneeRate(), budgetMillis, seatsSold, seatsSoldTwice, shares, startedAt, note);
        }
    }
}
