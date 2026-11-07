package io.github.extantape.preflight.plugin;

import io.github.extantape.preflight.api.PreflightTest;
import io.github.extantape.preflight.api.Severity;
import org.bukkit.plugin.Plugin;

record RegisteredTest(
        String ownerName, Plugin owner, String name, Severity severity, long timeoutTicks, PreflightTest body) {

    String id() {
        return ownerName + ":" + name;
    }
}
