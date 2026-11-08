package com.extantape.preflight.plugin;

import com.extantape.preflight.api.Severity;

record TestResult(RegisteredTest test, Status status, String message, Throwable cause, long durationMillis) {

    enum Status {
        PASSED, FAILED, TIMED_OUT, SKIPPED;

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    static TestResult skipped(RegisteredTest test) {
        return new TestResult(test, Status.SKIPPED, "Skipped by skip-tests", null, 0);
    }

    boolean failed() {
        return status == Status.FAILED || status == Status.TIMED_OUT;
    }

    /** True when this result should trigger the configured on-failure action. */
    boolean blocking() {
        return failed() && test.severity() == Severity.CRITICAL;
    }
}
