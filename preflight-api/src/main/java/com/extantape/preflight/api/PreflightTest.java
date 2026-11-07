package com.extantape.preflight.api;

/**
 * A single boot-time check. Runs on the main server thread.
 *
 * <p>A test passes when this method and every step it scheduled through {@link TestContext}
 * have returned without throwing. Any thrown {@link Throwable} fails it, including
 * {@link LinkageError}s such as {@code NoSuchMethodError}, which are the usual symptom of a
 * plugin broken by a server update.
 */
@FunctionalInterface
public interface PreflightTest {
    void run(TestContext context) throws Exception;
}
