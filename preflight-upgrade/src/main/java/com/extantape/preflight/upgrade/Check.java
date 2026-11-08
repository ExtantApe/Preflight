package com.extantape.preflight.upgrade;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Copies a server to a throwaway folder, boots the copy on the target Paper jar with Preflight warning
 * instead of stopping, reads its report and works out a plan. The real server isn't changed, and it may
 * be running. The copy is deleted afterwards, unless the caller made it.
 */
final class Check {
    /** Paths left out of the copy: logs, caches for the old version, and this tool's own state. */
    static final List<String> SKIPPED = List.of(
            "logs", "crash-reports", "cache", "versions", ".leafjuice", "plugins/.paper-remapped",
            "plugins/Preflight/upgrade", "plugins/Preflight/report.xml", "plugins/Preflight/report.xml.partial");
    static final String MARKER = ".preflight-upgrade-copy";
    private static final long SPARE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    record Options(Path server, Path paperJar, Path rulesFile, Path workDir, Path givenCopy, String memory,
            long timeoutSeconds, boolean allowSharedDatabase, List<String> excludes, String java) {}

    private final Options options;
    private final PrintStream progress;
    private final String tool;

    Check(Options options, PrintStream progress, String tool) {
        this.options = options;
        this.progress = progress;
        this.tool = tool;
    }

    Plan run() {
        Path server = options.server();
        String checkedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        List<String> warnings = new ArrayList<>();
        Plan.Context early = new Plan.Context(tool, server.toString(), checkedAt,
                options.paperJar().getFileName().toString(), Map.of(), Map.of(), warnings);
        Plan.Target fileOnly = new Plan.Target(options.paperJar().getFileName().toString(), null, null, null, null);

        String paperVersion = ServerFolder.paperVersion(options.paperJar());
        if (paperVersion == null) {
            return Plan.stopped(early, fileOnly, options.paperJar().getFileName()
                    + " isn't a Paper server jar, so nothing was copied or started. Download one from papermc.io.");
        }
        fileOnly = new Plan.Target(fileOnly.file(), "Paper", paperVersion, null, null);
        if (!ServerFolder.supportedVersion(paperVersion)) {
            return Plan.stopped(early, fileOnly, "That jar is Paper " + paperVersion
                    + ". Preflight needs Minecraft 26.1 or newer, so it can't check plugins on it.");
        }

        Rules rules;
        try {
            rules = Rules.load(options.rulesFile());
            if (!ServerFolder.eulaAccepted(server)) {
                return Plan.stopped(early, fileOnly, "eula.txt in the server folder doesn't say eula=true. The test copy "
                        + "needs the same agreement as the server, and this tool won't accept it for you.");
            }
            List<String> databases = ServerFolder.databasePlugins(server.resolve("plugins"));
            if (!databases.isEmpty()) {
                String names = String.join(", ", databases);
                if (!options.allowSharedDatabase()) {
                    return Plan.stopped(early, fileOnly, names + (databases.size() == 1 ? " uses" : " use")
                            + " an outside database, which the test copy would share with the real server and could "
                            + "write to. Run the check again with --allow-shared-database if that's fine.");
                }
                warnings.add(names + (databases.size() == 1 ? " uses" : " use")
                        + " an outside database, and the test copy shared it (--allow-shared-database).");
            }
        } catch (IOException | UncheckedIOException e) {
            return Plan.stopped(early, fileOnly, e.getMessage());
        }

        boolean ownCopy = options.givenCopy() == null;
        Path copy = null;
        try {
            Map<String, String> hashes = ServerFolder.jarHashes(server.resolve("plugins"));
            if (ownCopy) {
                List<Path> stale = leftovers(server);
                if (!stale.isEmpty()) {
                    String list = String.join(", ", stale.stream().map(Path::toString).toList());
                    progress.println("Left over from an earlier check that didn't finish: " + list);
                    warnings.add("Copies left over from an earlier check that didn't finish: " + list
                            + ". If no other check is running, they're safe to delete.");
                }
                if (ServerFolder.liveness(server).running()) {
                    warnings.add("The server was running while it was copied, so a world may have been caught "
                            + "mid-save. That's fine for checking plugins; the copy is thrown away.");
                }
                copy = makeCopy(server, checkedAt);
            } else {
                copy = options.givenCopy();
                progress.println("Using the copy at " + copy + ". It won't be deleted afterwards.");
            }
            Map<String, String> offInCopy = prepare(copy);
            if (!offInCopy.isEmpty()) {
                progress.println("Off in the copy, so they can't reach anything outside: "
                        + String.join(", ", offInCopy.keySet()));
            }
            Plan.Context context = new Plan.Context(tool, server.toString(), checkedAt,
                    options.paperJar().getFileName().toString(), hashes, offInCopy, warnings);

            Boot boot = boot(copy);
            Path reportFile = copy.resolve(Report.FILE);
            Report report = Files.isRegularFile(reportFile) ? Report.parse(Files.readAllBytes(reportFile)) : null;
            if (report == null) {
                String why = boot.timedOut()
                        ? "The test copy didn't finish within " + options.timeoutSeconds() + " seconds, and Preflight wrote no report."
                        : "The test copy stopped without a Preflight report (exit code " + boot.exitCode() + ").";
                if (!boot.lastLines().isEmpty()) {
                    why += " Its last lines: " + String.join(" | ", boot.lastLines());
                }
                return Plan.stopped(context, fileOnly, why);
            }
            if (boot.timedOut()) {
                warnings.add("The test copy didn't stop by itself within " + options.timeoutSeconds()
                        + " seconds after writing its report, so it was stopped.");
            }
            return Plan.make(report, rules, context);
        } catch (IOException | UncheckedIOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Plan.stopped(early, fileOnly, "The check couldn't finish: " + e.getMessage());
        } finally {
            if (ownCopy && copy != null) {
                deleteCopy(copy);
            }
        }
    }

    // ---- copying

    Path makeCopy(Path server, String checkedAt) throws IOException {
        Path work = options.workDir() != null ? options.workDir() : server.getParent();
        Files.createDirectories(work);
        Path copy = work.resolve(server.getFileName() + ".preflight-check-" + STAMP.format(Instant.parse(checkedAt)));
        if (Files.exists(copy)) {
            throw new IOException(copy + " already exists, so no copy was made.");
        }
        if (copy.toAbsolutePath().normalize().startsWith(server.toAbsolutePath().normalize())) {
            throw new IOException("The copy can't go inside the server folder. Choose another --work-dir.");
        }
        long size = sizeToCopy(server);
        FileStore store = Files.getFileStore(work);
        if (store.getUsableSpace() < size + SPARE_BYTES) {
            throw new IOException("The copy needs about " + gigabytes(size + SPARE_BYTES) + " free in " + work
                    + ", and there's " + gigabytes(store.getUsableSpace()) + ". Choose another --work-dir.");
        }
        progress.println("Copying the server (" + gigabytes(size) + ") to " + copy + " …");
        Files.createDirectory(copy);
        // Written first, so the copy can always be told apart from a real server and deleted safely.
        Files.writeString(copy.resolve(MARKER), "A throwaway copy made by preflight-upgrade. Safe to delete.\n");
        copyTree(server, copy);
        return copy;
    }

    private boolean skipped(Path server, Path path) {
        String relative = server.relativize(path).toString().replace('\\', '/');
        if (relative.isEmpty()) {
            return false;
        }
        if (path.getFileName().toString().equals("session.lock")) {
            return true;
        }
        for (String skip : Stream.concat(SKIPPED.stream(), options.excludes().stream()).toList()) {
            String clean = skip.replace('\\', '/').replaceAll("^/+|/+$", "");
            if (relative.equals(clean) || relative.startsWith(clean + "/")) {
                return true;
            }
        }
        return false;
    }

    private long sizeToCopy(Path server) throws IOException {
        long[] total = {0};
        Files.walkFileTree(server, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return skipped(server, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!skipped(server, file)) {
                    total[0] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    /** Follows links, so a linked world becomes a real copy and the test can never open the real one. */
    private void copyTree(Path server, Path copy) throws IOException {
        Files.walkFileTree(server, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (skipped(server, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(copy.resolve(server.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!skipped(server, file)) {
                    Files.copy(file, copy.resolve(server.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof FileSystemLoopException) {
                    progress.println("Skipped " + server.relativize(file) + ": a link loops back on itself.");
                    return FileVisitResult.CONTINUE;
                }
                throw e;
            }
        });
    }

    // ---- preparing

    /**
     * Makes the copy safe to boot next to the real server: its own port on 127.0.0.1, no remote console,
     * outward plugins off, and this release's Preflight in place.
     *
     * @return plugins switched off in the copy, by name, with why
     */
    Map<String, String> prepare(Path copy) throws IOException {
        // Whoever made the copy, nothing from the real server's last run may be mistaken for this one.
        deleteInside(copy, copy.resolve("logs"));
        deleteInside(copy, copy.resolve(Report.FILE));
        deleteInside(copy, copy.resolve(Report.FILE + ".partial"));
        for (Path world : ServerFolder.worlds(copy)) {
            deleteInside(copy, world.resolve("session.lock"));
        }

        Path properties = copy.resolve("server.properties");
        List<String> lines = Files.isRegularFile(properties)
                ? new ArrayList<>(Files.readAllLines(properties, StandardCharsets.ISO_8859_1)) : new ArrayList<>();
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("server-ip", "127.0.0.1");
        settings.put("server-port", Integer.toString(freePort()));
        settings.put("enable-rcon", "false");
        settings.put("enable-query", "false");
        settings.put("management-server-enabled", "false");
        settings.put("motd", "Preflight test copy");
        for (Map.Entry<String, String> setting : settings.entrySet()) {
            String prefix = setting.getKey() + "=";
            lines.removeIf(line -> line.strip().startsWith(prefix));
            lines.add(prefix + setting.getValue());
        }
        Files.write(properties, lines, StandardCharsets.ISO_8859_1);

        Path plugins = copy.resolve("plugins");
        Files.createDirectories(plugins);
        Map<String, String> off = new LinkedHashMap<>();
        for (Path jar : ServerFolder.jars(plugins)) {
            String name = ServerFolder.pluginName(jar);
            if (name == null) {
                continue;
            }
            if (name.equalsIgnoreCase("Preflight")) {
                // The copy is throwaway; this release's Preflight replaces whatever was there.
                Files.delete(jar);
            } else if (ServerFolder.OUTWARD.contains(name.toLowerCase(Locale.ROOT))) {
                Files.move(jar, jar.resolveSibling(jar.getFileName() + ".disabled"));
                off.put(name, "Off in the test copy, so it can't reach anything outside. It wasn't checked.");
            }
        }
        try (InputStream bundled = Check.class.getResourceAsStream("/bundled/Preflight.jar")) {
            if (bundled == null) {
                throw new IOException("This build of preflight-upgrade has no Preflight jar inside it.");
            }
            Files.copy(bundled, plugins.resolve("Preflight.jar"), StandardCopyOption.REPLACE_EXISTING);
        }
        return off;
    }

    /** Deletes a file or folder, but only one inside the copy. */
    private static void deleteInside(Path copy, Path target) throws IOException {
        if (!target.toAbsolutePath().normalize().startsWith(copy.toAbsolutePath().normalize()) || !Files.exists(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** Copies of this server, made by this tool, still lying in the work folder. */
    List<Path> leftovers(Path server) throws IOException {
        Path work = options.workDir() != null ? options.workDir() : server.getParent();
        if (!Files.isDirectory(work)) {
            return List.of();
        }
        String prefix = server.getFileName() + ".preflight-check-";
        try (Stream<Path> children = Files.list(work)) {
            return children.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> Files.isRegularFile(path.resolve(MARKER)))
                    .sorted()
                    .toList();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    // ---- booting

    record Boot(boolean timedOut, int exitCode, List<String> lastLines) {}

    private Boot boot(Path copy) throws IOException, InterruptedException {
        List<String> command = List.of(options.java(), "-Xmx" + options.memory(),
                "-Dpreflight.on-failure=WARN", "-Dpreflight.shutdown-after-run=true",
                "-jar", options.paperJar().toAbsolutePath().toString(), "--nogui");
        progress.println("Starting the copy on " + options.paperJar().getFileName() + " …");
        Process process = new ProcessBuilder(command).directory(copy.toFile()).redirectErrorStream(true).start();
        Thread stopOnExit = new Thread(() -> stop(process));
        Runtime.getRuntime().addShutdownHook(stopOnExit);
        Deque<String> tail = new ArrayDeque<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = out.readLine()) != null; ) {
                    if (line.contains("[Preflight]") && (line.contains("startup tests") || line.contains("FAILED"))) {
                        progress.println("  " + line.strip());
                    }
                    synchronized (tail) {
                        tail.addLast(line.strip());
                        if (tail.size() > 6) {
                            tail.removeFirst();
                        }
                    }
                }
            } catch (IOException e) {
                // The process ended; nothing more to read.
            }
        });
        boolean finished = process.waitFor(options.timeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            stop(process);
        }
        reader.join(5000);
        try {
            Runtime.getRuntime().removeShutdownHook(stopOnExit);
        } catch (IllegalStateException e) {
            // Already shutting down.
        }
        synchronized (tail) {
            return new Boot(!finished, finished ? process.exitValue() : -1, List.copyOf(tail));
        }
    }

    /** Asks the copy to stop, then insists. */
    private static void stop(Process process) {
        if (!process.isAlive()) {
            return;
        }
        try {
            process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            if (process.waitFor(60, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IOException e) {
            // Falls through to destroy.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- cleaning up

    /** Deletes a copy this tool made. Refuses any folder without the marker, so it can never touch a real server. */
    void deleteCopy(Path copy) {
        if (!Files.isRegularFile(copy.resolve(MARKER))) {
            progress.println("Not deleting " + copy + ": it has no " + MARKER + " marker.");
            return;
        }
        progress.println("Deleting the copy …");
        try (Stream<Path> paths = Files.walk(copy)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException | UncheckedIOException e) {
            progress.println("Couldn't fully delete " + copy + " (" + e.getMessage() + "). It's safe to delete by hand.");
        }
    }

    static String gigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
