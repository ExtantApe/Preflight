package com.extantape.preflight.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.extantape.preflight.api.Check;
import com.extantape.preflight.api.PreflightTest;
import com.extantape.preflight.api.Severity;
import com.extantape.preflight.api.TestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class TestRunnerTest {
    private final ManualScheduler scheduler = new ManualScheduler();
    private List<TestResult> results;

    private static RegisteredTest test(String name, long timeout, PreflightTest body) {
        return new RegisteredTest("Owner", null, name, Severity.CRITICAL, timeout, body);
    }

    private void start(RegisteredTest... tests) {
        new TestRunner(scheduler, null, index -> null, Logger.getLogger("test"))
                .start(List.of(tests), done -> results = done);
    }

    private TestResult onlyResult() {
        scheduler.runUntilIdle(1000);
        assertNotNull(results, "run never completed");
        assertEquals(1, results.size());
        return results.get(0);
    }

    @Test
    void passingTestPasses() {
        start(test("ok", 100, context -> {}));
        assertEquals(TestResult.Status.PASSED, onlyResult().status());
    }

    @Test
    void failedCheckFailsWithItsMessage() {
        start(test("bad", 100, context -> Check.that(false, "nope")));
        TestResult result = onlyResult();
        assertEquals(TestResult.Status.FAILED, result.status());
        assertTrue(result.message().contains("nope"));
        assertTrue(result.blocking());
    }

    @Test
    void linkageErrorIsCaughtAsAFailure() {
        start(test("broken", 100, context -> {
            throw new NoSuchMethodError("org.bukkit.Removed.method()");
        }));
        assertEquals(TestResult.Status.FAILED, onlyResult().status());
    }

    @Test
    void warningFailureIsNotBlocking() {
        start(new RegisteredTest("Owner", null, "soft", Severity.WARNING, 100, context -> Check.that(false, "meh")));
        TestResult result = onlyResult();
        assertTrue(result.failed());
        assertFalse(result.blocking());
    }

    @Test
    void testStaysOpenUntilLaterStepsRun() {
        boolean[] stepRan = new boolean[1];
        start(test("multi", 100, context -> context.later(5, () -> stepRan[0] = true)));
        scheduler.tick();
        assertFalse(stepRan[0]);
        assertEquals(null, results);
        assertEquals(TestResult.Status.PASSED, onlyResult().status());
        assertTrue(stepRan[0]);
    }

    @Test
    void failureInLaterStepFailsTheTest() {
        start(test("multi", 100, context -> context.later(1, () -> Check.that(false, "late"))));
        assertEquals(TestResult.Status.FAILED, onlyResult().status());
    }

    @Test
    void neverCompletingAwaitTimesOut() {
        start(test("hang", 10, context -> context.await(new CompletableFuture<Void>(), value -> {})));
        assertEquals(TestResult.Status.TIMED_OUT, onlyResult().status());
    }

    @Test
    void timeoutCancelsOutstandingSteps() {
        boolean[] stepRan = new boolean[1];
        start(test("slow", 5, context -> context.later(50, () -> stepRan[0] = true)));
        assertEquals(TestResult.Status.TIMED_OUT, onlyResult().status());
        assertFalse(stepRan[0]);
    }

    @Test
    void awaitDeliversResultOnALaterTick() {
        CompletableFuture<String> future = new CompletableFuture<>();
        List<String> received = new ArrayList<>();
        start(test("await", 100, context -> context.await(future, received::add)));
        future.complete("value");
        assertTrue(received.isEmpty());
        assertEquals(TestResult.Status.PASSED, onlyResult().status());
        assertEquals(List.of("value"), received);
    }

    @Test
    void exceptionalAwaitFailsWithOriginalCause() {
        CompletableFuture<String> future = new CompletableFuture<>();
        start(test("await", 100, context -> context.await(future, value -> {})));
        future.completeExceptionally(new IllegalStateException("database down"));
        TestResult result = onlyResult();
        assertEquals(TestResult.Status.FAILED, result.status());
        assertTrue(result.message().contains("database down"));
    }

    @Test
    void cleanupsRunInReverseOrderEvenOnFailure() {
        List<String> order = new ArrayList<>();
        start(test("cleanup", 100, context -> {
            context.onCleanup(() -> order.add("first"));
            context.onCleanup(() -> order.add("second"));
            throw new IllegalStateException("boom");
        }));
        onlyResult();
        assertEquals(List.of("second", "first"), order);
    }

    @Test
    void throwingCleanupDoesNotStopTheRun() {
        start(
                test("a", 100, context -> context.onCleanup(() -> {
                    throw new IllegalStateException("cleanup boom");
                })),
                test("b", 100, context -> {}));
        scheduler.runUntilIdle(1000);
        assertEquals(2, results.size());
    }

    @Test
    void oneFailureDoesNotStopLaterTestsAndOrderIsKept() {
        start(test("a", 100, context -> Check.that(false, "a failed")), test("b", 100, context -> {}));
        scheduler.runUntilIdle(1000);
        assertEquals(List.of("a", "b"), results.stream().map(result -> result.test().name()).toList());
        assertEquals(TestResult.Status.PASSED, results.get(1).status());
    }

    @Test
    void schedulingAfterFinishIsRejected() {
        TestContext[] captured = new TestContext[1];
        start(test("done", 100, context -> captured[0] = context));
        onlyResult();
        assertThrows(IllegalStateException.class, () -> captured[0].later(1, () -> {}));
    }

    @Test
    void emptyRunCompletesImmediately() {
        start();
        assertEquals(List.of(), results);
    }
}
