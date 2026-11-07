package com.extantape.preflight.plugin;

import com.extantape.preflight.api.PreflightRegistry;
import com.extantape.preflight.api.PreflightTest;
import com.extantape.preflight.api.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.plugin.Plugin;

final class DefaultRegistry implements PreflightRegistry {
    private final List<RegisteredTest> tests = new ArrayList<>();
    private boolean closed;

    @Override
    public void register(Plugin owner, String name, Severity severity, long timeoutTicks, PreflightTest test) {
        Objects.requireNonNull(owner, "owner");
        add(owner.getName(), owner, name, severity, timeoutTicks, test);
    }

    /** Takes the owner's name separately so this can be exercised without a server. */
    void add(String ownerName, Plugin owner, String name, Severity severity, long timeoutTicks, PreflightTest test) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(test, "test");
        if (timeoutTicks < 1) {
            throw new IllegalArgumentException("timeoutTicks must be at least 1");
        }
        RegisteredTest entry = new RegisteredTest(ownerName, owner, name, severity, timeoutTicks, test);
        if (closed) {
            throw new IllegalStateException(
                    "Preflight has already started; register " + entry.id() + " during onEnable");
        }
        if (tests.stream().anyMatch(existing -> existing.id().equals(entry.id()))) {
            throw new IllegalArgumentException("Test already registered: " + entry.id());
        }
        tests.add(entry);
    }

    /** Stops accepting registrations and returns everything registered so far. */
    List<RegisteredTest> close() {
        closed = true;
        return List.copyOf(tests);
    }
}
