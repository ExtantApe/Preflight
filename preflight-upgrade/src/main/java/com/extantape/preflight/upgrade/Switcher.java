package com.extantape.preflight.upgrade;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Switches plugins off on the real server, and back on. Only while it's stopped, never deleting anything:
 * a jar goes off by becoming {@code <name>.jar.disabled}, which Paper doesn't load. Before anything moves,
 * a restore point of every plugin jar is written and read back to prove it's whole.
 */
final class Switcher {
    static final String STATE_DIR = "plugins/Preflight/upgrade";
    static final String RECORD = "switched-off.json";
    static final String RESTORE_POINTS = "restore-points";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** A refusal with a reason a person can act on. Nothing was changed when this is thrown. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    record Entry(String name, List<String> files, List<String> why, String date, String target, String restorePoint) {}

    private final Path server;
    private final Path plugins;
    private final Path state;

    Switcher(Path server) {
        this.server = server;
        this.plugins = server.resolve("plugins");
        this.state = server.resolve(STATE_DIR);
    }

    // ---- the record of what's off

    List<Entry> switchedOff() throws IOException {
        Path file = state.resolve(RECORD);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            Map<String, Object> root = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)), "record");
            if (!(root.get("format") instanceof Long format) || format != 1) {
                throw new IOException(file + " is format " + root.get("format") + ", which this version doesn't know.");
            }
            List<Entry> entries = new ArrayList<>();
            for (Object item : Json.list(root, "entries", "record")) {
                Map<String, Object> e = Json.object(item, "record entry");
                entries.add(new Entry(Json.string(e, "name", "entry"), Json.strings(e, "files", "entry"),
                        Json.strings(e, "why", "entry"), Json.optionalString(e, "date", "entry"),
                        Json.optionalString(e, "target", "entry"), Json.optionalString(e, "restorePoint", "entry")));
            }
            return entries;
        } catch (Json.ParseException e) {
            throw new IOException(file + " couldn't be read (" + e.getMessage() + "). It's plain JSON; fix it by hand.");
        }
    }

    private void saveRecord(List<Entry> entries) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", 1);
        root.put("entries", entries.stream().map(entry -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", entry.name());
            e.put("files", entry.files());
            e.put("why", entry.why());
            e.put("date", entry.date());
            e.put("target", entry.target());
            e.put("restorePoint", entry.restorePoint());
            return (Object) e;
        }).toList());
        Files.createDirectories(state);
        Path partial = state.resolve(RECORD + ".partial");
        Files.writeString(partial, Json.write(root), StandardCharsets.UTF_8);
        Files.move(partial, state.resolve(RECORD), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    List<Path> restorePoints() throws IOException {
        Path folder = state.resolve(RESTORE_POINTS);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var stream = Files.list(folder)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".zip")).sorted().toList();
        }
    }

    // ---- apply

    /** Everything apply will do, checked before anything changes. */
    void checkPlan(Plan plan) throws Refused, IOException {
        Path real = server.toRealPath();
        Path planned;
        try {
            planned = Path.of(plan.server()).toRealPath();
        } catch (IOException e) {
            throw new Refused("This plan is for " + plan.server() + ", which isn't there.");
        }
        if (!real.equals(planned)) {
            throw new Refused("This plan is for " + plan.server() + ", not " + real + ".");
        }
        switch (plan.status()) {
            case STOP -> throw new Refused("The check couldn't judge the plugins, so there's nothing to apply: " + plan.stop());
            case WAIT -> throw new Refused("A plugin that must stay on would have to go ("
                    + String.join(", ", plan.blocked().stream().map(Plan.Blocked::name).toList())
                    + "), so the upgrade should wait.");
            case ALL_CLEAR -> throw new Refused("There's nothing to switch off.");
            case SWITCH_OFF -> { }
        }
        for (Plan.Off item : plan.off()) {
            for (String file : item.files()) {
                if (!Plan.safeJarName(file)) {
                    throw new Refused(file + " isn't a plain jar name in plugins/, so nothing was changed.");
                }
                Path jar = plugins.resolve(file);
                if (!Files.isRegularFile(jar)) {
                    throw new Refused(file + " isn't in the plugins folder as it was when checked, so nothing was "
                            + "changed. Check again.");
                }
                String expected = item.sha256().get(file);
                if (expected == null || !expected.equalsIgnoreCase(ServerFolder.sha256(jar))) {
                    throw new Refused(file + " has changed since the check, so nothing was changed. Check again.");
                }
                if (Files.exists(plugins.resolve(file + ".disabled"))) {
                    throw new Refused(file + ".disabled is already there, and this tool never overwrites. Nothing was "
                            + "changed. Move it away by hand first.");
                }
            }
        }
    }

    /** @return the restore point written before anything moved */
    Path apply(Plan plan) throws Refused, IOException {
        try (Held ignored = lock()) {
            checkPlan(plan);
            refuseIfRunning();
            Path point = restorePoint();
            List<Path> moved = new ArrayList<>();
            try {
                for (Plan.Off item : plan.off()) {
                    for (String file : item.files()) {
                        Path jar = plugins.resolve(file);
                        Files.move(jar, plugins.resolve(file + ".disabled"));
                        moved.add(jar);
                    }
                }
            } catch (IOException e) {
                List<String> stuck = new ArrayList<>();
                for (Path jar : moved.reversed()) {
                    try {
                        Files.move(jar.resolveSibling(jar.getFileName() + ".disabled"), jar);
                    } catch (IOException again) {
                        stuck.add(jar.getFileName().toString());
                    }
                }
                throw new IOException("Switching off stopped part-way (" + e.getMessage() + "). "
                        + (stuck.isEmpty() ? "The ones already switched off were put back."
                        : "These couldn't be put back: " + String.join(", ", stuck) + ". The restore point "
                        + point + " has every jar as it was."));
            }
            String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            String relativePoint = state.relativize(point).toString().replace('\\', '/');
            List<Entry> entries = new ArrayList<>(switchedOff());
            for (Plan.Off item : plan.off()) {
                entries.removeIf(entry -> entry.name().equalsIgnoreCase(item.name()));
                entries.add(new Entry(item.name(), item.files(), item.why(), now, plan.target().label(), relativePoint));
            }
            saveRecord(entries);
            return point;
        }
    }

    // ---- restore

    record Restored(String name, boolean done, String note) {}

    /**
     * Puts chosen plugins back on, one at a time. A plugin whose jar name is already taken by a newer jar is
     * left off, and says so; nothing is overwritten.
     */
    List<Restored> restore(List<String> names) throws Refused, IOException {
        try (Held ignored = lock()) {
            refuseIfRunning();
            List<Entry> entries = new ArrayList<>(switchedOff());
            List<Entry> chosen = new ArrayList<>();
            for (String name : names) {
                Entry entry = entries.stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst().orElse(null);
                if (entry == null) {
                    throw new Refused(name + " isn't one this tool switched off. Switched off: "
                            + (entries.isEmpty() ? "none" : String.join(", ", entries.stream().map(Entry::name).toList())) + ".");
                }
                if (!chosen.contains(entry)) {
                    chosen.add(entry);
                }
            }
            List<Restored> results = new ArrayList<>();
            for (Entry entry : chosen) {
                List<String> taken = entry.files().stream()
                        .filter(file -> Files.exists(plugins.resolve(file)) && Files.exists(plugins.resolve(file + ".disabled")))
                        .toList();
                if (!taken.isEmpty()) {
                    results.add(new Restored(entry.name(), false, String.join(", ", taken)
                            + " is already in the plugins folder, switched on, so " + entry.name() + " was left as it is."));
                    continue;
                }
                List<String> missing = new ArrayList<>();
                for (String file : entry.files()) {
                    Path off = plugins.resolve(file + ".disabled");
                    if (Files.exists(off)) {
                        Files.move(off, plugins.resolve(file));
                    } else if (!Files.exists(plugins.resolve(file))) {
                        missing.add(file);
                    }
                }
                entries.remove(entry);
                results.add(new Restored(entry.name(), true, missing.isEmpty() ? "Back on."
                        : "Back on, but " + String.join(", ", missing) + ".disabled had gone, so check the plugins folder."));
            }
            saveRecord(entries);
            return results;
        }
    }

    // ---- shared pieces

    private void refuseIfRunning() throws Refused {
        ServerFolder.Liveness liveness = ServerFolder.liveness(server);
        if (liveness.running()) {
            throw new Refused(liveness.why() + " Plugins are only switched while the server is stopped.");
        }
    }

    /** Held while changing the server, so only one apply or restore runs at a time. */
    private record Held(FileChannel channel) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private Held lock() throws IOException, Refused {
        Files.createDirectories(state);
        FileChannel channel = FileChannel.open(state.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            throw new Refused("Another preflight-upgrade is changing this server right now.");
        }
        return new Held(channel);
    }

    /**
     * Zips every top-level plugin jar, on or off, then reads the zip back and compares each entry's sha256
     * with the file it came from. Only a restore point that reads back whole is used.
     */
    Path restorePoint() throws IOException {
        Path folder = state.resolve(RESTORE_POINTS);
        Files.createDirectories(folder);
        Path zip = folder.resolve(STAMP.format(Instant.now()) + ".zip");
        for (int n = 2; Files.exists(zip); n++) {
            zip = folder.resolve(STAMP.format(Instant.now()) + "-" + n + ".zip");
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(plugins)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jar") || name.endsWith(".jar.disabled");
                    })
                    .sorted()
                    .forEach(files::add);
        }
        Map<String, String> expected = new LinkedHashMap<>();
        StringBuilder manifest = new StringBuilder("Plugin jars in plugins/ before preflight-upgrade switched any off.\n"
                + "To put one back, copy it from here into plugins/. sha256 of each:\n");
        try (OutputStream out = Files.newOutputStream(zip, StandardOpenOption.CREATE_NEW);
                ZipOutputStream zipOut = new ZipOutputStream(out)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                zipOut.putNextEntry(new ZipEntry("plugins/" + name));
                MessageDigest digest = sha256();
                try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                    in.transferTo(zipOut);
                }
                zipOut.closeEntry();
                String hash = HexFormat.of().formatHex(digest.digest());
                expected.put("plugins/" + name, hash);
                manifest.append(hash).append("  plugins/").append(name).append('\n');
            }
            zipOut.putNextEntry(new ZipEntry("README.txt"));
            zipOut.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        try (ZipFile read = new ZipFile(zip.toFile())) {
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                ZipEntry zipEntry = read.getEntry(entry.getKey());
                if (zipEntry == null) {
                    throw new IOException("The restore point " + zip + " is missing " + entry.getKey() + ", so nothing was changed.");
                }
                MessageDigest digest = sha256();
                try (InputStream in = new DigestInputStream(read.getInputStream(zipEntry), digest)) {
                    in.transferTo(OutputStream.nullOutputStream());
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(entry.getValue())) {
                    throw new IOException("The restore point " + zip + " didn't read back the same for " + entry.getKey()
                            + ", so nothing was changed.");
                }
            }
        }
        return zip;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
