package com.extantape.preflight.upgrade;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * {@code java -jar preflight-upgrade.jar <command>}. See docs/upgrade.md.
 *
 * <p>Exit codes: 0 done or all clear, 10 the check found plugins to switch off, 11 the upgrade should wait
 * (a never-off plugin would have to go), 12 the check couldn't judge, 13 refused or partly done, 2 bad
 * arguments, 1 unexpected error.
 */
public final class Main {
    static final int OK = 0;
    static final int ERROR = 1;
    static final int USAGE = 2;
    static final int REFUSED = 13;

    private static final String USAGE_TEXT = """
            preflight-upgrade %s: check a Paper server's plugins against a new Paper version, on a throwaway copy.

              check   --server <dir> --paper <paper.jar> [--json] [--rules <file>] [--work-dir <dir>]
                      [--copy <dir>] [--memory 4G] [--timeout <seconds>] [--allow-shared-database]
                      [--exclude <path in the server>]... [--java <java>]
                  Copies the server, starts the copy on the new Paper jar with Preflight, and says which
                  plugins won't start and what needs them. The real server isn't changed, and may be running.

              apply   --server <dir> --plan <plan.json> [--yes]
                  Switches off what a saved plan lists (check --json > plan.json). Only while the server is
                  stopped, after a restore point. Jars are renamed to .jar.disabled, never deleted.

              restore --server <dir> (--plugin <name>... | --all) [--yes]
                  Switches plugins this tool switched off back on, one at a time. Never overwrites a jar.

              status  --server <dir> [--json]
                  Lists what this tool has switched off, and the restore points.

            Rules (plugins that must never go off): <server>/plugins/Preflight/upgrade/rules.json, or --rules.
            """;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.in));
    }

    static int run(String[] args, PrintStream out, PrintStream err, InputStream in) {
        if (args.length == 0 || List.of("help", "--help", "-h").contains(args[0])) {
            out.print(USAGE_TEXT.formatted(version()));
            return args.length == 0 ? USAGE : OK;
        }
        if (List.of("--version", "version").contains(args[0])) {
            out.println(version());
            return OK;
        }
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println("Run with --help for the commands.");
            return USAGE;
        }
        try {
            return switch (parsed.command) {
                case "check" -> check(parsed, out, err);
                case "apply" -> apply(parsed, out, err, in);
                case "restore" -> restore(parsed, out, err, in);
                case "status" -> status(parsed, out);
                default -> {
                    err.println("Unknown command " + parsed.command + ". Run with --help for the commands.");
                    yield USAGE;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return USAGE;
        } catch (IOException e) {
            err.println(e.getMessage());
            return ERROR;
        }
    }

    // ---- commands

    private static int check(Args args, PrintStream out, PrintStream err) throws IOException {
        Path server = args.server();
        Path paper = args.path("paper", true);
        if (!Files.isRegularFile(paper) || !paper.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("--paper should be a Paper server jar: " + paper);
        }
        Path copy = args.path("copy", false);
        if (copy != null) {
            if (!Files.isDirectory(copy)) {
                throw new IllegalArgumentException("--copy should be a folder that's already a copy of the server: " + copy);
            }
            Path realCopy = copy.toRealPath();
            Path realServer = server.toRealPath();
            if (realCopy.startsWith(realServer) || realServer.startsWith(realCopy)) {
                throw new IllegalArgumentException("--copy can't be the server folder, or inside or around it.");
            }
        }
        Path rules = args.path("rules", false);
        long timeout = args.number("timeout", 600);
        String memory = args.value("memory", "4G");
        if (!memory.matches("[0-9]+[MmGg]")) {
            throw new IllegalArgumentException("--memory should look like 4G or 2048M.");
        }
        String java = args.value("java", ProcessHandle.current().info().command().orElse("java"));
        Check.Options options = new Check.Options(server.toRealPath(), paper.toRealPath(),
                rules != null ? rules : server.resolve(Switcher.STATE_DIR).resolve("rules.json"),
                args.path("work-dir", false), copy == null ? null : copy.toRealPath(), memory, timeout,
                args.flag("allow-shared-database"), args.all("exclude"), java);
        boolean json = args.flag("json");
        args.done();
        Plan plan = new Check(options, err, version()).run();
        if (json) {
            out.print(Json.write(plan.toJson()));
        } else {
            printPlan(plan, out);
        }
        return plan.status().exitCode;
    }

    private static int apply(Args args, PrintStream out, PrintStream err, InputStream in) throws IOException {
        Path server = args.server();
        Path planFile = args.path("plan", true);
        boolean yes = args.flag("yes");
        args.done();
        Plan plan;
        try {
            plan = Plan.fromJson(Files.readString(planFile, StandardCharsets.UTF_8));
        } catch (Json.ParseException e) {
            err.println(planFile + " isn't a plan this tool can use: " + e.getMessage() + ".");
            return REFUSED;
        }
        Switcher switcher = new Switcher(server.toRealPath());
        try {
            switcher.checkPlan(plan);
        } catch (Switcher.Refused e) {
            err.println(e.getMessage());
            return REFUSED;
        }
        out.println("For " + plan.target().label() + ", this switches off " + plan.off().size()
                + (plan.off().size() == 1 ? " plugin" : " plugins") + " in " + server + ":");
        for (Plan.Off item : plan.off()) {
            out.println("  " + item.name() + " (" + String.join(", ", item.files()) + "): " + String.join(" ", item.why()));
        }
        out.println("Each jar is renamed to .jar.disabled, after a restore point of every plugin jar. Nothing is deleted.");
        out.println("Worlds aren't part of that restore point: take a full backup before the upgrade itself.");
        if (!confirm(yes, in, out, err)) {
            return REFUSED;
        }
        try {
            Path point = switcher.apply(plan);
            out.println("Switched off: " + String.join(", ", plan.off().stream().map(Plan.Off::name).toList()) + ".");
            out.println("Restore point: " + point);
            out.println("To switch one back on: restore --server " + server + " --plugin <name>");
            return OK;
        } catch (Switcher.Refused e) {
            err.println(e.getMessage());
            return REFUSED;
        }
    }

    private static int restore(Args args, PrintStream out, PrintStream err, InputStream in) throws IOException {
        Path server = args.server();
        List<String> names = args.all("plugin");
        boolean all = args.flag("all");
        boolean yes = args.flag("yes");
        args.done();
        Switcher switcher = new Switcher(server.toRealPath());
        if (all == !names.isEmpty()) {
            throw new IllegalArgumentException("restore needs either --plugin <name> (once or more) or --all.");
        }
        if (all) {
            names = switcher.switchedOff().stream().map(Switcher.Entry::name).toList();
            if (names.isEmpty()) {
                out.println("Nothing is switched off by this tool.");
                return OK;
            }
        }
        out.println("This switches back on: " + String.join(", ", names) + ".");
        if (!confirm(yes, in, out, err)) {
            return REFUSED;
        }
        try {
            List<Switcher.Restored> results = switcher.restore(names);
            for (Switcher.Restored result : results) {
                out.println("  " + result.name() + ": " + result.note());
            }
            return results.stream().allMatch(Switcher.Restored::done) ? OK : REFUSED;
        } catch (Switcher.Refused e) {
            err.println(e.getMessage());
            return REFUSED;
        }
    }

    private static int status(Args args, PrintStream out) throws IOException {
        Path server = args.server();
        boolean json = args.flag("json");
        args.done();
        Switcher switcher = new Switcher(server.toRealPath());
        List<Switcher.Entry> entries = switcher.switchedOff();
        List<Path> points = switcher.restorePoints();
        ServerFolder.Liveness liveness = ServerFolder.liveness(server);
        if (json) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", 1);
            root.put("server", server.toRealPath().toString());
            root.put("running", liveness.running());
            root.put("runningWhy", liveness.why());
            root.put("switchedOff", entries.stream().map(entry -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", entry.name());
                e.put("files", entry.files());
                e.put("why", entry.why());
                e.put("date", entry.date());
                e.put("target", entry.target());
                e.put("restorePoint", entry.restorePoint());
                return (Object) e;
            }).toList());
            root.put("restorePoints", points.stream().map(path -> (Object) path.toString()).toList());
            out.print(Json.write(root));
            return OK;
        }
        out.println(liveness.why());
        if (entries.isEmpty()) {
            out.println("Nothing is switched off by this tool.");
        } else {
            out.println("Switched off by this tool:");
            for (Switcher.Entry entry : entries) {
                out.println("  " + entry.name() + " (" + String.join(", ", entry.files()) + "), " + entry.date()
                        + " for " + entry.target() + ": " + String.join(" ", entry.why()));
            }
        }
        out.println(points.isEmpty() ? "No restore points." : "Restore points:");
        points.forEach(point -> out.println("  " + point));
        return OK;
    }

    // ---- output

    static void printPlan(Plan plan, PrintStream out) {
        out.println("Checked " + plan.server() + " on " + plan.target().label() + ".");
        switch (plan.status()) {
            case STOP -> out.println("Nothing can be judged: " + plan.stop());
            case ALL_CLEAR -> out.println("Every plugin started. Nothing needs switching off.");
            case WAIT -> out.println("Wait: a plugin that must stay on wouldn't start, so this upgrade should wait.");
            case SWITCH_OFF -> out.println("These plugins would have to be switched off for this version:");
        }
        if (!plan.blocked().isEmpty()) {
            out.println();
            out.println("Must stay on, but wouldn't work:");
            for (Plan.Blocked item : plan.blocked()) {
                out.println("  " + item.name() + ". " + String.join(" ", item.why()) + " Kept on because: " + item.rule());
            }
        }
        if (!plan.off().isEmpty()) {
            out.println();
            out.println(plan.blocked().isEmpty() ? "Would be switched off:" : "Would also be switched off:");
            for (Plan.Off item : plan.off()) {
                out.println("  " + item.name() + " (" + String.join(", ", item.files()) + "). " + String.join(" ", item.why()));
            }
        }
        if (!plan.notChecked().isEmpty()) {
            out.println();
            out.println("Not checked:");
            plan.notChecked().forEach(item -> out.println("  " + item.name() + ". " + item.why()));
        }
        if (!plan.warnings().isEmpty()) {
            out.println();
            out.println("For you to decide (nothing is switched off for these):");
            plan.warnings().forEach(warning -> out.println("  " + warning));
        }
        if (!plan.recheck().isEmpty()) {
            out.println();
            out.println("Try by hand after the upgrade:");
            plan.recheck().forEach(item -> out.println("  " + item.plugin() + ": " + item.note()));
        }
        if (plan.status() == Plan.Status.SWITCH_OFF) {
            out.println();
            out.println("A Preflight pass means a plugin starts, not that it works. To switch these off, save this check");
            out.println("with --json to a file, stop the server, and run: apply --server <dir> --plan <file>");
        }
    }

    private static boolean confirm(boolean yes, InputStream in, PrintStream out, PrintStream err) throws IOException {
        if (yes) {
            return true;
        }
        if (System.console() == null && in == System.in) {
            err.println("Nothing was changed. Run this in a terminal to answer yes, or pass --yes once a person has agreed.");
            return false;
        }
        out.print("Type yes to go ahead: ");
        out.flush();
        String answer = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
        if (answer == null || !answer.strip().equalsIgnoreCase("yes")) {
            out.println("Nothing was changed.");
            return false;
        }
        return true;
    }

    static String version() {
        try (InputStream in = Main.class.getResourceAsStream("/preflight-upgrade.properties")) {
            Properties properties = new Properties();
            if (in != null) {
                properties.load(in);
            }
            return properties.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    // ---- arguments

    /** {@code command --name value --flag}. Every option must be used, so a typo is an error, not ignored. */
    static final class Args {
        private static final List<String> FLAGS = List.of("json", "yes", "all", "allow-shared-database");
        final String command;
        private final Map<String, List<String>> values = new LinkedHashMap<>();
        private final List<String> used = new ArrayList<>();

        private Args(String command) {
            this.command = command;
        }

        static Args parse(String[] args) {
            Args out = new Args(args[0]);
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--") || arg.length() == 2) {
                    throw new IllegalArgumentException("Unexpected \"" + arg + "\". Options start with --.");
                }
                String name = arg.substring(2);
                String value = null;
                int equals = name.indexOf('=');
                if (equals > 0) {
                    value = name.substring(equals + 1);
                    name = name.substring(0, equals);
                }
                if (FLAGS.contains(name)) {
                    if (value != null) {
                        throw new IllegalArgumentException("--" + name + " takes no value.");
                    }
                    value = "true";
                } else if (value == null) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--" + name + " needs a value.");
                    }
                    value = args[++i];
                }
                out.values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
            return out;
        }

        Path server() {
            Path server = path("server", true);
            if (!Files.isDirectory(server)) {
                throw new IllegalArgumentException("--server should be the server's folder: " + server);
            }
            return server;
        }

        Path path(String name, boolean required) {
            String value = value(name, null);
            if (value == null) {
                if (required) {
                    throw new IllegalArgumentException(command + " needs --" + name + ".");
                }
                return null;
            }
            return Path.of(value).toAbsolutePath().normalize();
        }

        String value(String name, String fallback) {
            used.add(name);
            List<String> found = values.get(name);
            if (found == null) {
                return fallback;
            }
            if (found.size() > 1) {
                throw new IllegalArgumentException("--" + name + " was given more than once.");
            }
            return found.getFirst();
        }

        List<String> all(String name) {
            used.add(name);
            return values.getOrDefault(name, List.of());
        }

        long number(String name, long fallback) {
            String value = value(name, null);
            if (value == null) {
                return fallback;
            }
            try {
                long n = Long.parseLong(value);
                if (n <= 0) {
                    throw new NumberFormatException();
                }
                return n;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " should be a whole number above 0.");
            }
        }

        boolean flag(String name) {
            used.add(name);
            return values.containsKey(name);
        }

        /** Fails on any option the command didn't ask about. */
        void done() {
            List<String> unknown = values.keySet().stream()
                    .filter(name -> !used.contains(name))
                    .map(name -> "--" + name).toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(command + " doesn't take " + String.join(", ", unknown) + ".");
            }
        }
    }
}
