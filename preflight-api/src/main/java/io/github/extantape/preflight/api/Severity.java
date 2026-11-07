package io.github.extantape.preflight.api;

/** How much a failing test matters. */
public enum Severity {
    /** Failure triggers the server's configured {@code on-failure} action. */
    CRITICAL,
    /** Failure is reported, but the server carries on. */
    WARNING
}
