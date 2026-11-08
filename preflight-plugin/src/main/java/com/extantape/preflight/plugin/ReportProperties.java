package com.extantape.preflight.plugin;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The flat name/value pairs at the top of report.xml, for tools that act on a run, such as an upgrade
 * helper deciding which plugins to switch off. The format is documented in docs/report-format.md.
 *
 * <p>Keys are {@code preflight.*}, {@code server.*}, {@code plugin.<Name>.<field>} and
 * {@code test.<Owner:name>.<field>}. Field names never contain dots, so split a plugin key on its last
 * dot. Fields with nothing to say are left out, not written empty, except lists, which are written
 * empty so a reader can tell "none" from "not reported".
 */
final class ReportProperties {
    /** Bump only for a change that breaks existing readers. Adding keys isn't one. */
    static final int FORMAT = 1;

    static final String OWNER = "Preflight";
    static final String ALL_PLUGINS_ENABLED = "all-plugins-enabled";
    static final String PLUGINS_LOADED = "plugins-loaded";
    /** Built-in tests that point at plugins rather than at the server. */
    static final Set<String> PLUGIN_TESTS = Set.of(ALL_PLUGINS_ENABLED, PLUGINS_LOADED);

    /** What the running server says about itself. {@code build} is null when Paper doesn't report one. */
    record Server(String brand, String version, String minecraftVersion, Integer build, String preflightVersion) {}

    private ReportProperties() {}

    static Map<String, String> build(List<TestResult> results, PluginInventory.Snapshot snapshot,
            Map<String, String> reasons, Server server, String onFailure, Instant finishedAt) {
        List<PluginInventory.Status> plugins = snapshot.plugins();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("preflight.report.format", Integer.toString(FORMAT));
        out.put("preflight.version", server.preflightVersion());
        out.put("preflight.finished-at", finishedAt.truncatedTo(ChronoUnit.SECONDS).toString());
        out.put("preflight.server-checks", serverChecksFailed(results) ? "failed" : "passed");
        out.put("preflight.on-failure", onFailure);
        out.put("preflight.unreadable-jars", String.join(",", snapshot.unreadableJars()));
        out.put("preflight.other-jars", String.join(",", snapshot.otherJars()));
        out.put("server.brand", server.brand());
        out.put("server.version", server.version());
        out.put("server.minecraft-version", server.minecraftVersion());
        if (server.build() != null) {
            out.put("server.build", server.build().toString());
        }

        for (PluginInventory.Status plugin : plugins) {
            String key = "plugin." + plugin.name() + ".";
            out.put(key + "state", plugin.state().label());
            if (!plugin.version().isEmpty()) {
                out.put(key + "version", plugin.version());
            }
            out.put(key + "files", String.join(",", plugin.files()));
            if (plugin.descriptor() != null) {
                out.put(key + "descriptor", plugin.descriptor());
            }
            out.put(key + "depends", String.join(",", plugin.depends()));
            out.put(key + "soft-depends", String.join(",", plugin.softDepends()));
            out.put(key + "missing-depends", String.join(",", plugin.missingDepends()));
            out.put(key + "required-by", String.join(",", plugin.requiredBy()));
            out.put(key + "failed-tests", String.join(",", failedTests(plugin, results)));
            String reason = reasons.get(plugin.name());
            if (reason != null) {
                out.put(key + "reason", reason);
            }
        }

        for (TestResult result : results) {
            String key = "test." + result.test().id() + ".";
            out.put(key + "result", result.status().label());
            out.put(key + "severity", result.test().severity().name().toLowerCase(Locale.ROOT));
            if (result.failed()) {
                out.put(key + "message", LogReasons.oneLine(result.message()));
            }
        }
        return out;
    }

    /** True when one of Preflight's own server checks failed critically, so the server itself is suspect. */
    static boolean serverChecksFailed(List<TestResult> results) {
        return results.stream().anyMatch(result -> result.blocking()
                && result.test().ownerName().equals(OWNER)
                && !PLUGIN_TESTS.contains(result.test().name()));
    }

    /** Failed tests that point at this plugin: its own, plus the built-in check that caught it. */
    static List<String> failedTests(PluginInventory.Status plugin, List<TestResult> results) {
        List<String> ids = new ArrayList<>();
        for (TestResult result : results) {
            if (!result.failed()) {
                continue;
            }
            RegisteredTest test = result.test();
            boolean own = test.ownerName().equals(plugin.name()) && !plugin.name().equals(OWNER);
            boolean caughtDisabled = test.ownerName().equals(OWNER) && test.name().equals(ALL_PLUGINS_ENABLED)
                    && plugin.state() == PluginInventory.State.DISABLED;
            boolean caughtUnloaded = test.ownerName().equals(OWNER) && test.name().equals(PLUGINS_LOADED)
                    && (plugin.state() == PluginInventory.State.NOT_LOADED || !plugin.missingDepends().isEmpty());
            if (own || caughtDisabled || caughtUnloaded) {
                ids.add(test.id());
            }
        }
        return ids;
    }
}
