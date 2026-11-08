package com.extantape.preflight.plugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * What a plugin jar says about itself, read straight from its {@code paper-plugin.yml} or
 * {@code plugin.yml}. This covers jars the server refused to load, which the plugin manager never sees.
 */
record PluginDescriptor(
        String name, String version, String file, String descriptor, List<String> depends, List<String> softDepends) {

    static final String PAPER = "paper-plugin.yml";
    static final String BUKKIT = "plugin.yml";

    /**
     * The jars directly inside a folder: plugins, jars whose descriptor couldn't be read (corrupt, bad YAML,
     * no name), and jars with no descriptor at all, such as libraries. File names are relative to the folder.
     */
    record Scan(List<PluginDescriptor> plugins, List<String> unreadable, List<String> other) {
        static final Scan EMPTY = new Scan(List.of(), List.of(), List.of());
    }

    static Scan scan(Path folder, java.util.function.BiConsumer<Path, Exception> onError) {
        List<PluginDescriptor> found = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> other = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return Scan.EMPTY;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(folder,
                path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))) {
            for (Path jar : jars) {
                String file = jar.getFileName().toString();
                try {
                    PluginDescriptor descriptor = read(jar);
                    if (descriptor != null) {
                        found.add(descriptor);
                    } else {
                        other.add(file);
                    }
                } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                    unreadable.add(file);
                    onError.accept(jar, e);
                }
            }
        } catch (IOException e) {
            onError.accept(folder, e);
        }
        found.sort(java.util.Comparator.comparing(PluginDescriptor::file));
        unreadable.sort(null);
        other.sort(null);
        return new Scan(List.copyOf(found), List.copyOf(unreadable), List.copyOf(other));
    }

    /**
     * Reads one jar, or returns null if it has no plugin descriptor.
     *
     * @throws InvalidConfigurationException if the descriptor isn't valid YAML or names no plugin
     */
    static PluginDescriptor read(Path jar) throws IOException, InvalidConfigurationException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // Paper uses paper-plugin.yml when a jar has both.
            for (String kind : List.of(PAPER, BUKKIT)) {
                ZipEntry entry = zip.getEntry(kind);
                if (entry == null) {
                    continue;
                }
                YamlConfiguration yaml = new YamlConfiguration();
                try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    yaml.load(reader);
                }
                PluginDescriptor descriptor = fromYaml(yaml, kind, jar.getFileName().toString());
                if (descriptor == null) {
                    throw new InvalidConfigurationException(kind + " has no name");
                }
                return descriptor;
            }
            return null;
        }
    }

    static PluginDescriptor fromYaml(YamlConfiguration yaml, String kind, String file) {
        String name = yaml.getString("name");
        if (name == null || name.isBlank()) {
            return null;
        }
        if (kind.equals(BUKKIT)) {
            // Bukkit swaps spaces for underscores when it loads a plugin.yml name.
            name = name.replace(' ', '_');
        }
        List<String> depends = new ArrayList<>();
        List<String> softDepends = new ArrayList<>();
        if (kind.equals(PAPER)) {
            ConfigurationSection server = yaml.getConfigurationSection("dependencies.server");
            if (server != null) {
                for (String dependency : server.getKeys(false)) {
                    boolean required = server.getBoolean(dependency + ".required", true);
                    (required ? depends : softDepends).add(dependency);
                }
            }
        } else {
            depends.addAll(list(yaml, "depend"));
            softDepends.addAll(list(yaml, "softdepend"));
        }
        return new PluginDescriptor(name, yaml.getString("version", ""), file, kind,
                List.copyOf(depends), List.copyOf(softDepends));
    }

    private static List<String> list(YamlConfiguration yaml, String key) {
        if (yaml.isString(key)) {
            return List.of(yaml.getString(key));
        }
        return yaml.getStringList(key);
    }
}
