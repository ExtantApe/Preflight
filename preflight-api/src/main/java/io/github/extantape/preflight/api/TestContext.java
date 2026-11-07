package io.github.extantape.preflight.api;

import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.World;

/** What a running test can use. All methods must be called from the main server thread. */
public interface TestContext {

    /** The disposable world tests run in. It is deleted when the run finishes. */
    World world();

    /** A force-loaded spot reserved for this test, up in the air above the flat ground. */
    Location origin();

    /** Runs {@code step} on the main thread after {@code ticks} ticks, keeping the test open until then. */
    void later(long ticks, Step step);

    /**
     * Keeps the test open until {@code stage} completes, then runs {@code then} on the main thread.
     * Use this for database or network checks. If the stage completes exceptionally, the test fails.
     */
    <T> void await(CompletionStage<T> stage, ResultStep<T> then);

    /** Registers work to run when the test ends, pass or fail. Runs in reverse order of registration. */
    void onCleanup(Runnable cleanup);

    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface ResultStep<T> {
        void accept(T value) throws Exception;
    }
}
