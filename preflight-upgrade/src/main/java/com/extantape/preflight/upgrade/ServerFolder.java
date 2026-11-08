package com.extantape.preflight.upgrade;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reading a server folder: its plugin jars, its settings, and whether it's running. Nothing here writes. */
final class ServerFolder {
    private ServerFolder() {}

    /**
     * Plugins that talk to the outside world, by lowercased name, from Leaf Juice's list. A test copy
     * shouldn't post to the server's Discord, count votes, upload maps or join a proxy network.
     */
    static final Set<String> OUTWARD = Set.of(
            "discordsrv", "essentialsdiscord", "essentialsdiscordlink", "discordintegration", "nuvotifier", "votifier",
            "votingplugin", "dynmap", "bluemap", "plan", "drivebackupv2", "bungeecord", "velocity", "tcpshield",
            "geyser-spigot", "floodgate");

    private static final Pattern NAME = Pattern.compile("^name\\s*:\\s*(.+?)\\s*(#.*)?$", Pattern.MULTILINE);

    // ---- jars

    /** Top-level files ending in .jar, by file name. */
    static List<Path> jars(Path plugins) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(plugins)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(plugins)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    out.add(path);
                }
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * The plugin name a jar declares, from paper-plugin.yml or else plugin.yml, or null if it has none or
     * can't be read. Only the top-level {@code name:} line is read; that's all this tool needs.
     */
    static String pluginName(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String descriptor : List.of("paper-plugin.yml", "plugin.yml")) {
                ZipEntry entry = zip.getEntry(descriptor);
                if (entry == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Matcher m = NAME.matcher(new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8).replace("\r", ""));
                    if (!m.find()) {
                        return null;
                    }
                    String name = m.group(1);
                    if (name.length() >= 2 && (name.startsWith("'") && name.endsWith("'")
                            || name.startsWith("\"") && name.endsWith("\""))) {
                        name = name.substring(1, name.length() - 1);
                    }
                    return descriptor.equals("plugin.yml") ? name.replace(' ', '_') : name;
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** sha256 of every top-level jar, by file name. */
    static Map<String, String> jarHashes(Path plugins) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path jar : jars(plugins)) {
            out.put(jar.getFileName().toString(), sha256(jar));
        }
        return out;
    }

    // ---- the target jar

    /**
     * The Minecraft version a Paper server jar is for, or null if it isn't one. Paper ships as a paperclip
     * jar: its manifest starts {@code io.papermc.paperclip.Main}, and {@code META-INF/versions.list} names
     * the version, e.g. {@code <sha256>\t26.3\t26.3/paper-26.3.jar}.
     */
    static String paperVersion(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            ZipEntry versions = zip.getEntry("META-INF/versions.list");
            if (manifest == null || versions == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(manifest)) {
                String text = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
                if (!text.replace("\r", "").lines().anyMatch(line -> line.strip().equals("Main-Class: io.papermc.paperclip.Main"))) {
                    return null;
                }
            }
            try (InputStream in = zip.getInputStream(versions)) {
                for (String line : new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8).split("\n")) {
                    String[] parts = line.strip().split("\t");
                    if (parts.length == 3 && parts[2].contains("/paper-") && parts[1].matches("[0-9][0-9A-Za-z.\\-]*")) {
                        return parts[1];
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** True for 26.1 and later, which is what Preflight supports. Unknown shapes count as not supported. */
    static boolean supportedVersion(String version) {
        String[] parts = version.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 26 || (major == 26 && minor >= 1);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---- outside databases

    private static final Pattern SHARED_DATABASE = Pattern.compile(
            "^\\s*(storage-method|type|driver|database-type|storage|use-mysql|mysql)\\s*:\\s*['\"]?"
                    + "(mysql|mariadb|postgres\\w*|postgresql|mongodb|true)['\"]?\\s*(#.*)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * True when a plugin config points at an outside database, which a test copy would share with the real
     * server. Ported from Leaf Juice. Observed: AuraSkills has {@code sql:}, {@code enabled: false} and
     * {@code type: mysql}, naming a database it doesn't use, so a match doesn't count when its own section
     * says {@code enabled: false}.
     */
    static boolean usesOutsideDatabase(String text) {
        String[] lines = text.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!SHARED_DATABASE.matcher(lines[i]).matches()) {
                continue;
            }
            int own = indent(lines[i]);
            boolean switchedOff = false;
            for (int direction : new int[] {-1, 1}) {
                for (int j = i + direction; j >= 0 && j < lines.length; j += direction) {
                    String trimmed = lines[j].strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    if (indent(lines[j]) < own) {
                        break;
                    }
                    if (indent(lines[j]) == own
                            && trimmed.toLowerCase(Locale.ROOT).replace(" ", "").startsWith("enabled:false")) {
                        switchedOff = true;
                    }
                }
            }
            if (own > 0 && switchedOff) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** Plugin folders whose main config points at an outside database, by folder name. */
    static List<String> databasePlugins(Path plugins) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(plugins)) {
            return out;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(plugins, Files::isDirectory)) {
            for (Path folder : folders) {
                for (String name : List.of("config.yml", "config.yaml", "settings.yml")) {
                    Path config = folder.resolve(name);
                    if (!Files.isRegularFile(config)) {
                        continue;
                    }
                    String text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
                    if (usesOutsideDatabase(text)) {
                        out.add(folder.getFileName().toString());
                        break;
                    }
                }
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ---- settings

    static Properties serverProperties(Path server) throws IOException {
        Properties properties = new Properties();
        Path file = server.resolve("server.properties");
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    static boolean eulaAccepted(Path server) throws IOException {
        Path file = server.resolve("eula.txt");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Properties eula = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            eula.load(reader);
        }
        return "true".equalsIgnoreCase(eula.getProperty("eula", "").strip());
    }

    /** World folders: top-level folders with a level.dat. */
    static List<Path> worlds(Path server) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(server, Files::isDirectory)) {
            for (Path folder : stream) {
                if (Files.isRegularFile(folder.resolve("level.dat"))) {
                    out.add(folder);
                }
            }
        }
        out.sort(null);
        return out;
    }

    // ---- running or not

    /** {@code running} is false only when every check ran and found the server stopped. */
    record Liveness(boolean running, String why) {}

    /**
     * Whether the server is running. Two checks, and both must say stopped: nothing answers on its port,
     * and every world's session.lock can be locked, which the server holds while it runs. A check that
     * can't be made counts as running, because the answer then has to be no.
     */
    static Liveness liveness(Path server) {
        int port;
        String host;
        try {
            Properties properties = serverProperties(server);
            port = Integer.parseInt(properties.getProperty("server-port", "25565").strip());
            String ip = properties.getProperty("server-ip", "").strip();
            host = ip.isEmpty() || ip.equals("0.0.0.0") ? "127.0.0.1" : ip;
        } catch (IOException | NumberFormatException e) {
            return new Liveness(true, "server.properties couldn't be read (" + e.getMessage()
                    + "), so it can't be told whether the server is stopped.");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 700);
            return new Liveness(true, "Something is answering on " + host + ":" + port
                    + ", the server's port. Stop the server first.");
        } catch (IOException e) {
            // Nothing listening: that's the stopped case.
        }
        List<Path> worlds;
        try {
            worlds = worlds(server);
        } catch (IOException e) {
            return new Liveness(true, "The server folder couldn't be listed (" + e.getMessage() + ").");
        }
        Map<String, String> held = new TreeMap<>();
        for (Path world : worlds) {
            Path lock = world.resolve("session.lock");
            // Never create one: opening without CREATE leaves a missing lock missing.
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                FileLock acquired = channel.tryLock();
                if (acquired == null) {
                    held.put(world.getFileName().toString(), "locked");
                } else {
                    acquired.release();
                }
            } catch (NoSuchFileException e) {
                // No lock file means no server has the world open.
            } catch (OverlappingFileLockException e) {
                held.put(world.getFileName().toString(), "locked");
            } catch (IOException e) {
                return new Liveness(true, world.getFileName() + "/session.lock couldn't be checked (" + e.getMessage()
                        + "), so it can't be told whether the server is stopped.");
            }
        }
        if (!held.isEmpty()) {
            return new Liveness(true, "The server has " + String.join(", ", held.keySet())
                    + " open. Stop the server first.");
        }
        return new Liveness(false, "Stopped: nothing on port " + port + " and no world is open.");
    }
}
