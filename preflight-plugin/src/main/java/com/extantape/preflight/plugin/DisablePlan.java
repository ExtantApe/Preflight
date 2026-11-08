package com.extantape.preflight.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Works out which plugins to disable, and in what order, when a plugin fails its tests. */
final class DisablePlan {
    private DisablePlan() {}

    /**
     * @param targets plugins that failed
     * @param hardDepends each plugin's name mapped to the names of plugins it hard-depends on
     * @return targets plus everything depending on them, directly or not, dependents first
     */
    static List<String> order(Collection<String> targets, Map<String, List<String>> hardDepends) {
        Set<String> visited = new LinkedHashSet<>();
        for (String target : targets) {
            visit(target, hardDepends, visited, new java.util.HashSet<>());
        }
        List<String> ordered = new ArrayList<>(visited);
        // visit() adds a plugin before its dependents, so reversing puts dependents first.
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    private static void visit(String plugin, Map<String, List<String>> hardDepends, Set<String> visited,
            Set<String> path) {
        // Re-insert so a plugin always lands after whatever pulled it in, even if seen earlier.
        visited.remove(plugin);
        visited.add(plugin);
        // Jars that never loaded can declare a dependency cycle; stop rather than recurse forever.
        path.add(plugin);
        hardDepends.forEach((candidate, depends) -> {
            if (depends.contains(plugin) && !path.contains(candidate)) {
                visit(candidate, hardDepends, visited, path);
            }
        });
        path.remove(plugin);
    }
}
