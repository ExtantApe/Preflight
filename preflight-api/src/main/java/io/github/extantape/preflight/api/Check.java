package io.github.extantape.preflight.api;

import java.util.Objects;

/** Small assertion helpers for tests. Failures throw {@link AssertionError}. */
public final class Check {
    private Check() {}

    public static void that(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static <T> T notNull(T value, String what) {
        if (value == null) {
            throw new AssertionError(what + " was null");
        }
        return value;
    }
}
