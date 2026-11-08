package com.extantape.preflight.upgrade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What to switch off before an upgrade, worked out from a Preflight report. Making a plan touches nothing.
 * Ported from Leaf Juice's upgrade helper, with the same rules: what didn't load, didn't start, is missing
 * a hard dependency or failed a critical check goes off, plus everything that hard-depends on those.
 * Warnings and soft dependents are only listed.
 */
record Plan(
        String tool,
        String server,
        String checkedAt,
        Target target,
        String stop,
        List<Off> off,
        List<Blocked> blocked,
        List<NotChecked> notChecked,
        List<String> warnings,
        List<Rules.Recheck> recheck,
        PreflightInfo preflight) {

    static final int FORMAT = 1;
    /** The report.xml format this version understands. */
    static final int REPORT_FORMAT = 1;

    enum Status {
        SWITCH_OFF("switch-off", 10), ALL_CLEAR("all-clear", 0), WAIT("wait", 11), STOP("stop", 12);

        final String label;
        final int exitCode;

        Status(String label, int exitCode) {
            this.label = label;
            this.exitCode = exitCode;
        }
    }

    record Target(String file, String brand, String minecraft, Long build, String version) {
        /** "Paper 26.3 build 244", or the jar's file name when the report didn't say. */
        String label() {
            if (minecraft == null) {
                return file;
            }
            return (brand == null ? "Paper" : brand) + " " + minecraft + (build == null ? "" : " build " + build);
        }
    }

    record Off(String name, List<String> files, Map<String, String> sha256, List<String> why) {}

    record Blocked(String name, String rule, List<String> why) {}

    record NotChecked(String name, String why) {}

    record PreflightInfo(String version, String onFailure, String serverChecks) {}

    /** What the check knew besides the report: the real server's jars, and what the copy left out. */
    record Context(String tool, String server, String checkedAt, String targetFile,
            Map<String, String> jarHashes, Map<String, String> offInCopy, List<String> extraWarnings) {}

    Status status() {
        if (stop != null) {
            return Status.STOP;
        }
        if (!blocked.isEmpty()) {
            return Status.WAIT;
        }
        return off.isEmpty() ? Status.ALL_CLEAR : Status.SWITCH_OFF;
    }

    static Plan stopped(Context context, Target target, String why) {
        return new Plan(context.tool(), context.server(), context.checkedAt(), target, why, List.of(), List.of(),
                List.of(), List.copyOf(context.extraWarnings()), List.of(), null);
    }

    static Plan make(Report report, Rules rules, Context context) {
        Target fileOnly = new Target(context.targetFile(), null, null, null, null);
        if (report == null) {
            return stopped(context, fileOnly, "Preflight didn't write a report on the test copy, so nothing can be judged.");
        }
        Target target = new Target(context.targetFile(), report.brand(), report.minecraft(), report.build(),
                report.serverVersion());
        if (report.format() == null) {
            return stopped(context, target, "Preflight's report has no format number, so it's from a version too old "
                    + "for this. Preflight 0.2.0 or later is needed.");
        }
        if (report.format() != REPORT_FORMAT) {
            return stopped(context, target, "Preflight's report is format " + report.format()
                    + ", which this version of preflight-upgrade doesn't know. Look for a newer one.");
        }
        if (report.finishedAt() == null) {
            return stopped(context, target, "Preflight's report isn't finished, so nothing can be judged. Try the check again.");
        }
        if ("failed".equals(report.serverChecks())) {
            return stopped(context, target, "Preflight's own server checks failed, so " + target.label()
                    + " itself doesn't run properly on this server. Switching plugins off won't fix that.");
        }
        Rules active = rules == null ? Rules.NONE : rules;
        Map<String, Report.Plugin> plugins = report.plugins();

        // Why each plugin can't stay on by itself.
        Map<String, List<String>> reasons = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Report.Plugin plugin : plugins.values()) {
            List<String> why = new ArrayList<>();
            switch (plugin.state()) {
                case NOT_LOADED -> why.add("It didn't load." + (plugin.reason() == null ? "" : " " + plugin.reason()));
                case DISABLED -> why.add("It didn't start." + (plugin.reason() == null ? "" : " " + plugin.reason()));
                default -> { }
            }
            if (!plugin.missingDepends().isEmpty()) {
                why.add("It needs " + String.join(", ", plugin.missingDepends()) + ", which didn't load.");
            }
            for (String id : plugin.failedTests()) {
                Report.Test test = report.tests().get(id);
                if (test != null && test.failed() && !test.severity().equals("warning")) {
                    why.add("It failed " + id + (test.message() == null ? "." : ": " + test.message()));
                }
            }
            if (!why.isEmpty()) {
                reasons.put(plugin.name(), why);
            }
        }

        // Plugins switched off in the copy weren't checked, and nor was anything that needs them.
        Map<String, String> notChecked = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        context.offInCopy().forEach(notChecked::put);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Report.Plugin plugin : plugins.values()) {
                if (notChecked.containsKey(plugin.name())) {
                    continue;
                }
                for (String dependency : plugin.depends()) {
                    if (notChecked.containsKey(dependency)) {
                        notChecked.put(plugin.name(), "It needs " + dependency
                                + ", which is off in the test copy, so it couldn't be checked.");
                        grew = true;
                        break;
                    }
                }
            }
        }
        notChecked.keySet().forEach(reasons::remove);

        // Whatever needs a plugin that goes off has to go too, however far down the chain.
        Map<String, List<String>> off = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        reasons.forEach((name, why) -> off.put(name, new ArrayList<>(why)));
        Deque<String> queue = new ArrayDeque<>(reasons.keySet());
        while (!queue.isEmpty()) {
            String name = queue.pop();
            Set<String> needers = new LinkedHashSet<>();
            for (Report.Plugin plugin : plugins.values()) {
                if (plugin.depends().stream().anyMatch(dependency -> dependency.equalsIgnoreCase(name))) {
                    needers.add(plugin.name());
                }
            }
            Report.Plugin self = lookup(plugins, name);
            if (self != null) {
                needers.addAll(self.requiredBy());
            }
            for (String needer : needers.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
                if (needer.equalsIgnoreCase(name)) {
                    continue;
                }
                if (!off.containsKey(needer)) {
                    queue.add(needer);
                }
                String because = "Off because " + name + " is off.";
                List<String> why = off.computeIfAbsent(needer, k -> new ArrayList<>());
                if (!why.contains(because)) {
                    why.add(because);
                }
            }
        }
        off.keySet().forEach(notChecked::remove);

        List<Off> offList = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : off.entrySet()) {
            String name = entry.getKey();
            List<String> why = List.copyOf(entry.getValue());
            Rules.Keep keep = active.keep(name);
            Report.Plugin plugin = lookup(plugins, name);
            List<String> files = plugin == null ? List.of() : plugin.files();
            if (keep != null) {
                blocked.add(new Blocked(name, keep.why(), why));
            } else if (files.isEmpty() || !files.stream().allMatch(Plan::safeJarName)) {
                blocked.add(new Blocked(name, "Preflight didn't say which file it is, so it can't be switched off safely.", why));
            } else if (!files.stream().allMatch(context.jarHashes()::containsKey)) {
                blocked.add(new Blocked(name, "Its jar wasn't in the server's plugins folder when the check started, "
                        + "so it can't be switched off safely.", why));
            } else {
                Map<String, String> hashes = new LinkedHashMap<>();
                files.forEach(file -> hashes.put(file, context.jarHashes().get(file)));
                offList.add(new Off(name, List.copyOf(files), hashes, why));
            }
        }

        List<String> warnings = new ArrayList<>(context.extraWarnings());
        if (report.onFailure() != null && !report.onFailure().equals("WARN")) {
            warnings.add("Preflight ran with on-failure " + report.onFailure()
                    + " instead of WARN, so later checks may not have run. Treat this plan with care.");
        }
        for (String jar : report.unreadableJars()) {
            warnings.add(jar + " isn't a readable plugin jar, on any version. Remove it or replace it by hand.");
        }
        report.tests().values().stream()
                .filter(test -> test.failed() && test.severity().equals("warning"))
                .sorted(Comparator.comparing(Report.Test::id))
                .forEach(test -> warnings.add(test.id() + " warned" + (test.message() == null ? "." : ": " + test.message())));
        plugins.values().stream()
                .filter(plugin -> !off.containsKey(plugin.name()))
                .sorted(Comparator.comparing(Report.Plugin::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(plugin -> {
                    List<String> gone = plugin.softDepends().stream().filter(off::containsKey).toList();
                    if (!gone.isEmpty()) {
                        warnings.add(plugin.name() + " stays on, but works with " + String.join(", ", gone)
                                + ", which would be off. Check it by hand.");
                    }
                });

        Set<String> installed = new HashSet<>();
        plugins.keySet().forEach(name -> installed.add(Rules.lower(name)));
        notChecked.keySet().forEach(name -> installed.add(Rules.lower(name)));
        List<Rules.Recheck> recheck = active.recheck().stream()
                .filter(item -> installed.contains(Rules.lower(item.plugin())))
                .toList();

        List<NotChecked> notCheckedList = notChecked.entrySet().stream()
                .map(entry -> new NotChecked(entry.getKey(), entry.getValue()))
                .toList();
        return new Plan(context.tool(), context.server(), context.checkedAt(), target, null, List.copyOf(offList),
                List.copyOf(blocked), notCheckedList, List.copyOf(warnings), recheck,
                new PreflightInfo(report.preflightVersion(), report.onFailure(), report.serverChecks()));
    }

    /** A plain jar file name directly in plugins/: no folders, no hidden files, nothing that climbs out. */
    static boolean safeJarName(String file) {
        return !file.isEmpty() && !file.contains("/") && !file.contains("\\") && !file.startsWith(".")
                && !file.contains("..") && file.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                && file.chars().noneMatch(c -> c < 0x20);
    }

    private static Report.Plugin lookup(Map<String, Report.Plugin> plugins, String name) {
        Report.Plugin exact = plugins.get(name);
        if (exact != null) {
            return exact;
        }
        return plugins.values().stream().filter(plugin -> plugin.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    // ---- JSON, for --json output and for apply to read back

    Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("tool", tool);
        out.put("server", server);
        out.put("checkedAt", checkedAt);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("file", target.file());
        t.put("brand", target.brand());
        t.put("minecraft", target.minecraft());
        t.put("build", target.build());
        t.put("version", target.version());
        out.put("target", t);
        out.put("status", status().label);
        if (stop != null) {
            out.put("stop", stop);
        }
        out.put("off", off.stream().map(item -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", item.name());
            entry.put("files", item.files());
            entry.put("sha256", new LinkedHashMap<>(item.sha256()));
            entry.put("why", item.why());
            return (Object) entry;
        }).toList());
        out.put("blocked", blocked.stream().map(item -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", item.name());
            entry.put("rule", item.rule());
            entry.put("why", item.why());
            return (Object) entry;
        }).toList());
        out.put("notChecked", notChecked.stream().map(item -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", item.name());
            entry.put("why", item.why());
            return (Object) entry;
        }).toList());
        out.put("warnings", warnings);
        out.put("recheck", recheck.stream().map(item -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("plugin", item.plugin());
            entry.put("note", item.note());
            return (Object) entry;
        }).toList());
        if (preflight != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("version", preflight.version());
            p.put("onFailure", preflight.onFailure());
            p.put("serverChecks", preflight.serverChecks());
            out.put("preflight", p);
        }
        return out;
    }

    /** Reads a plan back for apply. Only what apply acts on is checked strictly. */
    static Plan fromJson(String text) throws Json.ParseException {
        Map<String, Object> root = Json.object(Json.parse(text), "plan");
        if (!(root.get("format") instanceof Long format) || format != FORMAT) {
            throw new Json.ParseException("this plan is format " + root.get("format")
                    + ", which this version of preflight-upgrade doesn't know");
        }
        Map<String, Object> t = Json.object(root.getOrDefault("target", Map.of()), "plan.target");
        Target target = new Target(Json.optionalString(t, "file", "plan.target"),
                Json.optionalString(t, "brand", "plan.target"), Json.optionalString(t, "minecraft", "plan.target"),
                t.get("build") instanceof Long l ? l : null, Json.optionalString(t, "version", "plan.target"));
        List<Off> off = new ArrayList<>();
        for (Object item : Json.list(root, "off", "plan")) {
            Map<String, Object> entry = Json.object(item, "plan.off entry");
            Map<String, Object> hashes = Json.object(entry.getOrDefault("sha256", Map.of()), "plan.off.sha256");
            Map<String, String> sha = new LinkedHashMap<>();
            for (Map.Entry<String, Object> hash : hashes.entrySet()) {
                if (!(hash.getValue() instanceof String value)) {
                    throw new Json.ParseException("plan.off.sha256 should map files to text");
                }
                sha.put(hash.getKey(), value);
            }
            off.add(new Off(Json.string(entry, "name", "plan.off"), Json.strings(entry, "files", "plan.off"), sha,
                    Json.strings(entry, "why", "plan.off")));
        }
        List<Blocked> blocked = new ArrayList<>();
        for (Object item : Json.list(root, "blocked", "plan")) {
            Map<String, Object> entry = Json.object(item, "plan.blocked entry");
            blocked.add(new Blocked(Json.string(entry, "name", "plan.blocked"),
                    Json.string(entry, "rule", "plan.blocked"), Json.strings(entry, "why", "plan.blocked")));
        }
        List<NotChecked> notChecked = new ArrayList<>();
        for (Object item : Json.list(root, "notChecked", "plan")) {
            Map<String, Object> entry = Json.object(item, "plan.notChecked entry");
            notChecked.add(new NotChecked(Json.string(entry, "name", "plan.notChecked"),
                    Json.string(entry, "why", "plan.notChecked")));
        }
        List<Rules.Recheck> recheck = new ArrayList<>();
        for (Object item : Json.list(root, "recheck", "plan")) {
            Map<String, Object> entry = Json.object(item, "plan.recheck entry");
            recheck.add(new Rules.Recheck(Json.string(entry, "plugin", "plan.recheck"),
                    Json.string(entry, "note", "plan.recheck")));
        }
        Plan plan = new Plan(Json.optionalString(root, "tool", "plan"), Json.string(root, "server", "plan"),
                Json.optionalString(root, "checkedAt", "plan"), target, Json.optionalString(root, "stop", "plan"),
                List.copyOf(off), List.copyOf(blocked), List.copyOf(notChecked),
                Json.strings(root, "warnings", "plan"), List.copyOf(recheck), null);
        String status = Json.optionalString(root, "status", "plan");
        if (status != null && !status.equals(plan.status().label)) {
            throw new Json.ParseException("the plan says \"" + status + "\" but its contents say \""
                    + plan.status().label + "\", so it may have been edited");
        }
        return plan;
    }
}
