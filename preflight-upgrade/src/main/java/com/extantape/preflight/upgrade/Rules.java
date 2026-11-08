package com.extantape.preflight.upgrade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-server rules: plugins that must never be switched off, and things to try by hand afterwards. The
 * same JSON as Leaf Juice's {@code upgrade-rules.json}, so one file serves both:
 *
 * <pre>{"format": 1, "neverOff": [{"plugin": "…", "why": "…"}], "recheck": [{"plugin": "…", "note": "…"}]}</pre>
 */
record Rules(List<Keep> neverOff, List<Recheck> recheck) {
    static final int FORMAT = 1;
    static final Rules NONE = new Rules(List.of(), List.of());

    record Keep(String plugin, String why) {}

    record Recheck(String plugin, String note) {}

    /** Names match ignoring case, so a rule for "OneBlock" still protects a plugin calling itself "Oneblock". */
    Keep keep(String name) {
        return neverOff.stream().filter(keep -> keep.plugin().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /**
     * @return null when the file doesn't exist
     * @throws IOException when it exists but can't be used. That's never treated as "no rules", because a
     *     lost never-off rule could let a protective plugin be switched off.
     */
    static Rules load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Map<String, Object> root = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)), "rules");
            Object format = root.get("format");
            if (!(format instanceof Long l) || l != FORMAT) {
                throw new IOException(file + " is rules format " + format + ", which this version doesn't know.");
            }
            List<Keep> keep = new ArrayList<>();
            for (Object item : Json.list(root, "neverOff", "rules")) {
                Map<String, Object> entry = Json.object(item, "rules.neverOff entry");
                keep.add(new Keep(Json.string(entry, "plugin", "rules.neverOff"),
                        Json.string(entry, "why", "rules.neverOff")));
            }
            List<Recheck> recheck = new ArrayList<>();
            for (Object item : Json.list(root, "recheck", "rules")) {
                Map<String, Object> entry = Json.object(item, "rules.recheck entry");
                recheck.add(new Recheck(Json.string(entry, "plugin", "rules.recheck"),
                        Json.string(entry, "note", "rules.recheck")));
            }
            return new Rules(List.copyOf(keep), List.copyOf(recheck));
        } catch (Json.ParseException | java.io.UncheckedIOException e) {
            throw new IOException(file + " couldn't be read (" + e.getMessage()
                    + "), so no plugin plan is offered until it's fixed.");
        }
    }

    static Map<String, Object> toJson(Rules rules) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("neverOff", rules.neverOff().stream().map(keep -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("plugin", keep.plugin());
            entry.put("why", keep.why());
            return entry;
        }).toList());
        out.put("recheck", rules.recheck().stream().map(item -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("plugin", item.plugin());
            entry.put("note", item.note());
            return entry;
        }).toList());
        return out;
    }

    static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
