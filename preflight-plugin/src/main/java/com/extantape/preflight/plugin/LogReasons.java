package com.extantape.preflight.plugin;

import java.util.List;
import java.util.Optional;

/**
 * Finds the server's own explanation for a plugin that didn't load or enable, in the boot log. Only
 * Paper's wording is matched; anything else is left unknown rather than guessed.
 */
final class LogReasons {
    static final int MAX_LENGTH = 300;

    private LogReasons() {}

    static Optional<String> find(List<String> lines, PluginInventory.Status status) {
        for (int i = 0; i < lines.size(); i++) {
            if (matches(lines.get(i), status)) {
                String reason = message(lines.get(i));
                if (i + 1 < lines.size() && isExceptionLine(lines.get(i + 1))) {
                    reason += ": " + lines.get(i + 1).trim();
                }
                return Optional.of(oneLine(reason));
            }
        }
        return Optional.empty();
    }

    private static boolean matches(String line, PluginInventory.Status status) {
        return switch (status.state()) {
            // Paper 26.x: "Could not load plugin 'X.jar' in folder 'plugins'" or
            // "Could not load 'plugins/X.jar' in 'plugins'".
            case NOT_LOADED -> line.contains("Could not load") && status.files().stream()
                    .anyMatch(file -> line.contains("'" + file + "'") || line.contains("/" + file + "'"));
            case DISABLED -> line.contains("Error occurred while enabling " + status.name() + " ");
            case ENABLED -> false;
        };
    }

    /** Drops the "[time] [thread/LEVEL]: [Logger] " prefix. */
    private static String message(String line) {
        int start = line.indexOf("]: ");
        String message = start < 0 ? line : line.substring(start + 3);
        if (message.startsWith("[")) {
            int close = message.indexOf("] ");
            if (close > 0) {
                message = message.substring(close + 2);
            }
        }
        return message.trim();
    }

    private static boolean isExceptionLine(String line) {
        String trimmed = line.trim();
        return !trimmed.isEmpty() && !line.startsWith("[") && !trimmed.startsWith("at ");
    }

    /** First line only, capped, so a person can read it in a list. */
    static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() <= MAX_LENGTH ? line : line.substring(0, MAX_LENGTH - 1) + "…";
    }
}
