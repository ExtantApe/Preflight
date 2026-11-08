package com.extantape.preflight.plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Combines what the plugin manager knows with what the jars in {@code plugins/} declare, so a jar the
 * server refused to load still shows up, with its dependencies.
 */
final class PluginInventory {
    private PluginInventory() {}

    enum State {
        ENABLED, DISABLED, NOT_LOADED;

        String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** A plugin the server loaded, as the plugin manager reports it. */
    record Loaded(String name, String version, boolean enabled, List<String> depends, List<String> softDepends,
            String descriptor) {}

    record Status(String name, String version, List<String> files, String descriptor, State state,
            List<String> depends, List<String> softDepends, List<String> missingDepends, List<String> requiredBy) {}

    /** Everything known about plugins/ at one moment. */
    record Snapshot(List<Status> plugins, List<String> unreadableJars, List<String> otherJars) {}

    static Snapshot snapshot(PluginDescriptor.Scan scan, List<Loaded> loaded) {
        return new Snapshot(build(scan.plugins(), loaded), scan.unreadable(), scan.other());
    }

    static List<Status> build(List<PluginDescriptor> jars, List<Loaded> loaded) {
        Map<String, Status> byName = new LinkedHashMap<>();
        for (Loaded plugin : loaded) {
            List<PluginDescriptor> own = jars.stream().filter(jar -> jar.name().equals(plugin.name())).toList();
            String descriptor = own.isEmpty() ? plugin.descriptor() : own.getFirst().descriptor();
            byName.put(plugin.name(), new Status(plugin.name(), plugin.version(), files(own), descriptor,
                    plugin.enabled() ? State.ENABLED : State.DISABLED,
                    List.copyOf(plugin.depends()), List.copyOf(plugin.softDepends()), List.of(), List.of()));
        }
        for (PluginDescriptor jar : jars) {
            if (byName.containsKey(jar.name())) {
                continue;
            }
            List<PluginDescriptor> own = jars.stream().filter(other -> other.name().equals(jar.name())).toList();
            byName.put(jar.name(), new Status(jar.name(), jar.version(), files(own), jar.descriptor(),
                    State.NOT_LOADED, jar.depends(), jar.softDepends(), List.of(), List.of()));
        }

        Map<String, List<String>> hardDepends = new LinkedHashMap<>();
        byName.values().forEach(status -> hardDepends.put(status.name(), status.depends()));

        List<Status> statuses = new ArrayList<>();
        for (Status status : byName.values()) {
            List<String> missing = status.depends().stream()
                    .filter(dependency -> {
                        Status found = byName.get(dependency);
                        return found == null || found.state() != State.ENABLED;
                    })
                    .toList();
            List<String> requiredBy = DisablePlan.order(Set.of(status.name()), hardDepends).stream()
                    .filter(name -> !name.equals(status.name()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            statuses.add(new Status(status.name(), status.version(), status.files(), status.descriptor(),
                    status.state(), status.depends(), status.softDepends(), missing, requiredBy));
        }
        statuses.sort(Comparator.comparing(Status::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(statuses);
    }

    /** True when this plugin can't be relied on: it isn't running, or something it needs isn't. */
    static boolean broken(Status status) {
        return status.state() != State.ENABLED || !status.missingDepends().isEmpty();
    }

    private static List<String> files(List<PluginDescriptor> jars) {
        return jars.stream().map(PluginDescriptor::file).sorted().toList();
    }
}
