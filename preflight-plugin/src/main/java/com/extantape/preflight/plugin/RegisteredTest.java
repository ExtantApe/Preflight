package com.extantape.preflight.plugin;

import com.extantape.preflight.api.PreflightTest;
import com.extantape.preflight.api.Severity;
import org.bukkit.plugin.Plugin;

record RegisteredTest(
        String ownerName, Plugin owner, String name, Severity severity, long timeoutTicks, PreflightTest body) {

    String id() {
        return ownerName + ":" + name;
    }
}
