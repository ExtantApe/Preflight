package io.github.extantape.preflight.plugin;

import io.github.extantape.preflight.api.PreflightTest;
import io.github.extantape.preflight.api.TestContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;

/** Runs tests one at a time on the main thread, with a tick between each. */
final class TestRunner {
    private final TickScheduler scheduler;
    private final World world;
    private final IntFunction<Location> origins;
    private final Logger logger;
    private final List<TestResult> results = new ArrayList<>();

    private List<RegisteredTest> tests;
    private Consumer<List<TestResult>> onDone;
    private int index;

    TestRunner(TickScheduler scheduler, World world, IntFunction<Location> origins, Logger logger) {
        this.scheduler = scheduler;
        this.world = world;
        this.origins = origins;
        this.logger = logger;
    }

    void start(List<RegisteredTest> tests, Consumer<List<TestResult>> onDone) {
        this.tests = tests;
        this.onDone = onDone;
        runNext();
    }

    private void runNext() {
        if (index >= tests.size()) {
            onDone.accept(List.copyOf(results));
            return;
        }
        int current = index++;
        new Execution(tests.get(current), origins.apply(current)).begin();
    }

    private final class Execution implements TestContext {
        private final RegisteredTest test;
        private final Location origin;
        private final Set<TickScheduler.Task> tasks = new HashSet<>();
        private final Deque<Runnable> cleanups = new ArrayDeque<>();
        private long startedAt;
        private int pending;
        private boolean finished;

        Execution(RegisteredTest test, Location origin) {
            this.test = test;
            this.origin = origin;
        }

        void begin() {
            startedAt = System.nanoTime();
            tasks.add(scheduler.runLater(test.timeoutTicks(), () -> finish(
                    TestResult.Status.TIMED_OUT, "Timed out after " + test.timeoutTicks() + " ticks", null)));
            PreflightTest body = test.body();
            invoke(() -> body.run(this));
        }

        @Override
        public World world() {
            return world;
        }

        @Override
        public Location origin() {
            return origin;
        }

        @Override
        public void later(long ticks, Step step) {
            requireRunning();
            pending++;
            TickScheduler.Task[] self = new TickScheduler.Task[1];
            self[0] = scheduler.runLater(Math.max(1, ticks), () -> {
                tasks.remove(self[0]);
                pending--;
                invoke(step);
            });
            tasks.add(self[0]);
        }

        @Override
        public <T> void await(CompletionStage<T> stage, ResultStep<T> then) {
            requireRunning();
            pending++;
            stage.whenComplete((value, error) -> scheduler.runLater(1, () -> {
                if (finished) {
                    return;
                }
                pending--;
                if (error != null) {
                    fail(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
                } else {
                    invoke(() -> then.accept(value));
                }
            }));
        }

        @Override
        public void onCleanup(Runnable cleanup) {
            requireRunning();
            cleanups.push(cleanup);
        }

        private void requireRunning() {
            if (finished) {
                throw new IllegalStateException("Test " + test.id() + " has already finished");
            }
        }

        private void invoke(Step step) {
            try {
                step.run();
            } catch (Throwable thrown) {
                fail(thrown);
                return;
            }
            if (pending == 0) {
                finish(TestResult.Status.PASSED, null, null);
            }
        }

        private void fail(Throwable thrown) {
            String message = thrown.getMessage() != null
                    ? thrown.getClass().getSimpleName() + ": " + thrown.getMessage()
                    : thrown.getClass().getName();
            finish(TestResult.Status.FAILED, message, thrown);
        }

        private void finish(TestResult.Status status, String message, Throwable cause) {
            if (finished) {
                return;
            }
            finished = true;
            tasks.forEach(TickScheduler.Task::cancel);
            tasks.clear();
            for (Runnable cleanup : cleanups) {
                try {
                    cleanup.run();
                } catch (Throwable thrown) {
                    logger.log(Level.WARNING, "Cleanup failed for " + test.id(), thrown);
                }
            }
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            results.add(new TestResult(test, status, message, cause, millis));
            scheduler.runLater(1, TestRunner.this::runNext);
        }
    }
}
