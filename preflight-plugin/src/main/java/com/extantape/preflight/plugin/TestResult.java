package com.extantape.preflight.plugin;

import com.extantape.preflight.api.Severity;

record TestResult(RegisteredTest test, Status status, String message, Throwable cause, long durationMillis) {

    enum Status { PASSED, FAILED, TIMED_OUT }

    boolean failed() {
        return status != Status.PASSED;
    }

    /** True when this result should trigger the configured on-failure action. */
    boolean blocking() {
        return failed() && test.severity() == Severity.CRITICAL;
    }
}
