package io.github.extantape.preflight.api;

import org.bukkit.plugin.Plugin;

/**
 * Where plugins register their tests. Get it from the services manager during {@code onEnable}:
 *
 * <pre>{@code
 * PreflightRegistry registry = Bukkit.getServicesManager().load(PreflightRegistry.class);
 * }</pre>
 *
 * <p>Tests run once all plugins have enabled, so registering after that throws
 * {@link IllegalStateException}.
 */
public interface PreflightRegistry {

    long DEFAULT_TIMEOUT_TICKS = 100;

    /** Registers a {@link Severity#CRITICAL} test with the default timeout of 5 seconds. */
    default void register(Plugin owner, String name, PreflightTest test) {
        register(owner, name, Severity.CRITICAL, DEFAULT_TIMEOUT_TICKS, test);
    }

    /**
     * @param name unique within {@code owner}; reported and skipped as {@code owner:name}
     * @param timeoutTicks how long the test may stay open. This only bounds waiting on
     *     {@link TestContext#later} and {@link TestContext#await}: code that blocks the main thread
     *     stalls the server regardless.
     */
    void register(Plugin owner, String name, Severity severity, long timeoutTicks, PreflightTest test);
}
