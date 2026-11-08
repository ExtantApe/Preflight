package com.extantape.preflight.plugin;

import com.extantape.preflight.api.PreflightRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PreflightPlugin extends JavaPlugin implements Listener {
    private static final String TEST_WORLD = "preflight_test";
    private static final String WORLD_MARKER = ".preflight-disposable";
    private static final int ARENA_SPACING = 64;
    private static final int ARENA_Y = 80;
    private static final String FLAT_SETTINGS = """
            {"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},\
            {"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}""";

    /** JVM flags that override config.yml, so a tool can set them on a throwaway copy without editing it. */
    static final String ON_FAILURE_PROPERTY = "preflight.on-failure";
    static final String SHUTDOWN_PROPERTY = "preflight.shutdown-after-run";

    private enum Phase { TESTING, OPEN, LOCKED }

    private enum FailureAction { LOCKDOWN, WARN, SHUTDOWN, DISABLE_PLUGIN }

    private final DefaultRegistry registry = new DefaultRegistry();
    private volatile Phase phase = Phase.TESTING;
    private List<TestResult> lastResults = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        deleteStaleReport();
        // Registered here so anything that soft-depends on Preflight can find it in its own onEnable.
        getServer().getServicesManager().register(PreflightRegistry.class, registry, this, ServicePriority.Normal);
        BuiltInTests.register(registry, this, this::inventory);
        getServer().getPluginManager().registerEvents(this, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(command(), "Shows startup test results or lifts a lockdown."));
    }

    /** Fires once every plugin has enabled and the default worlds exist. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP || !getConfig().getBoolean("enabled", true)) {
            registry.close();
            phase = Phase.OPEN;
            return;
        }
        getServer().getScheduler().runTask(this, this::runTests);
    }

    /** Listener-level gate: nobody gets in while tests run, or during a lockdown. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        Phase current = phase;
        if (current != Phase.OPEN) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(current == Phase.TESTING
                    ? "Server is still running startup checks. Try again shortly."
                    : "Server failed its startup checks and is locked for maintenance."));
        }
    }

    private void runTests() {
        Set<String> skipIds = new HashSet<>(getConfig().getStringList("skip-tests"));
        List<TestResult> skipped = new ArrayList<>();
        List<RegisteredTest> tests = registry.close().stream()
                .filter(test -> {
                    boolean skip = skipIds.contains(test.id());
                    if (skip) {
                        getLogger().info("Skipping " + test.id() + " (skip-tests)");
                        skipped.add(TestResult.skipped(test));
                    }
                    return !skip;
                })
                .toList();
        logOverrides();

        World world;
        try {
            world = createTestWorld();
        } catch (RuntimeException | IOException e) {
            getLogger().log(Level.SEVERE, "Could not set up the test world; no tests ran. Locking the server.", e);
            phase = Phase.LOCKED;
            if (shutdownAfterRun() || failureAction() == FailureAction.SHUTDOWN) {
                Bukkit.shutdown();
            }
            return;
        }

        getLogger().info("Running " + tests.size() + " startup tests. Logins are held until they finish.");
        TickScheduler scheduler = (ticks, action) -> getServer().getScheduler().runTaskLater(this, action, ticks)::cancel;
        new TestRunner(scheduler, world, index -> arena(world, index), getLogger())
                .start(tests, results -> {
                    List<TestResult> all = new ArrayList<>(results);
                    all.addAll(skipped);
                    onFinished(world, all);
                });
    }

    private World createTestWorld() throws IOException {
        World world = new WorldCreator(TEST_WORLD)
                .type(WorldType.FLAT)
                // Empty flat settings log "No key layers" on Paper 26.1.2, so spell the layers out.
                .generatorSettings(FLAT_SETTINGS)
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            throw new IllegalStateException("Server returned no world for " + TEST_WORLD);
        }
        // deleteTestWorld() refuses to touch any folder lacking this marker.
        Files.createDirectories(world.getWorldFolder().toPath());
        Files.writeString(world.getWorldFolder().toPath().resolve(WORLD_MARKER), "Safe to delete.\n");
        return world;
    }

    private Location arena(World world, int index) {
        int x = index * ARENA_SPACING;
        world.getChunkAt(x >> 4, 0).setForceLoaded(true);
        return new Location(world, x, ARENA_Y, 0);
    }

    private void onFinished(World world, List<TestResult> results) {
        lastResults = results;
        logSummary(results);
        writeReport(results);
        deleteTestWorld(world);
        applyFailureAction(results);
        if (shutdownAfterRun()) {
            phase = Phase.LOCKED;
            Bukkit.shutdown();
        }
    }

    private boolean shutdownAfterRun() {
        String override = System.getProperty(SHUTDOWN_PROPERTY);
        return override != null ? Boolean.parseBoolean(override) : getConfig().getBoolean("shutdown-after-run", false);
    }

    /** Says when a -D flag is overriding config.yml, so nobody wonders why their setting is ignored. */
    private void logOverrides() {
        for (String property : List.of(ON_FAILURE_PROPERTY, SHUTDOWN_PROPERTY)) {
            String value = System.getProperty(property);
            if (value != null) {
                getLogger().info("Using -D" + property + "=" + value + " instead of config.yml.");
            }
        }
    }

    /** A report left by an earlier boot must not be mistaken for this run's result. */
    private void deleteStaleReport() {
        try {
            Files.deleteIfExists(getDataFolder().toPath().resolve("report.xml"));
            Files.deleteIfExists(getDataFolder().toPath().resolve("report.xml.partial"));
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not delete the previous report.xml", e);
        }
    }

    private void logSummary(List<TestResult> results) {
        for (TestResult result : results) {
            if (!result.failed()) {
                continue;
            }
            Level level = result.blocking() ? Level.SEVERE : Level.WARNING;
            getLogger().log(level, result.test().severity() + " " + result.test().id() + " " + result.status()
                    + " - " + result.message(), result.cause());
        }
        long passed = results.stream().filter(result -> result.status() == TestResult.Status.PASSED).count();
        long ran = results.stream().filter(result -> result.status() != TestResult.Status.SKIPPED).count();
        getLogger().info(passed + "/" + ran + " startup tests passed.");
    }

    private void writeReport(List<TestResult> results) {
        Path report = getDataFolder().toPath().resolve("report.xml");
        try {
            Instant finishedAt = Instant.now();
            Map<String, String> properties;
            try {
                PluginInventory.Snapshot snapshot = inventory();
                properties = ReportProperties.build(results, snapshot, reasons(snapshot.plugins()), serverFacts(),
                        failureAction().name(), finishedAt);
            } catch (RuntimeException e) {
                // The test results still matter more than the extras, so write those regardless.
                getLogger().log(Level.WARNING, "Could not collect plugin details for report.xml", e);
                properties = Map.of();
            }
            // Write then move, so a reader never sees half a report.
            Path partial = report.resolveSibling("report.xml.partial");
            Files.writeString(partial, JUnitReport.render(results, properties, finishedAt), StandardCharsets.UTF_8);
            try {
                Files.move(partial, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, report, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not write " + report, e);
        }
    }

    /** Loaded plugins plus jars in plugins/ that never loaded. */
    PluginInventory.Snapshot inventory() {
        PluginDescriptor.Scan jars = PluginDescriptor.scan(getDataFolder().getParentFile().toPath(),
                (path, e) -> getLogger().log(Level.WARNING, "Could not read the plugin descriptor in " + path, e));
        List<PluginInventory.Loaded> loaded = Arrays.stream(getServer().getPluginManager().getPlugins())
                .map(plugin -> new PluginInventory.Loaded(plugin.getName(), plugin.getPluginMeta().getVersion(),
                        plugin.isEnabled(), plugin.getPluginMeta().getPluginDependencies(),
                        plugin.getPluginMeta().getPluginSoftDependencies(),
                        plugin.getPluginMeta() instanceof PluginDescriptionFile
                                ? PluginDescriptor.BUKKIT : PluginDescriptor.PAPER))
                .toList();
        return PluginInventory.snapshot(jars, loaded);
    }

    /** Paper's own error lines for plugins that didn't load or enable, read from this boot's log. */
    private Map<String, String> reasons(List<PluginInventory.Status> plugins) {
        Map<String, String> reasons = new LinkedHashMap<>();
        if (plugins.stream().noneMatch(plugin -> plugin.state() != PluginInventory.State.ENABLED)) {
            return reasons;
        }
        Path log = Path.of("logs", "latest.log");
        List<String> lines;
        try {
            // Read as bytes so one bad character in the log can't stop the report.
            lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            getLogger().log(Level.FINE, "No " + log + " to read reasons from", e);
            return reasons;
        }
        for (PluginInventory.Status plugin : plugins) {
            LogReasons.find(lines, plugin).ifPresent(reason -> reasons.put(plugin.name(), reason));
        }
        return reasons;
    }

    private ReportProperties.Server serverFacts() {
        ServerBuildInfo info = ServerBuildInfo.buildInfo();
        OptionalInt build = info.buildNumber();
        return new ReportProperties.Server(info.brandName(), getServer().getVersion(), info.minecraftVersionId(),
                build.isPresent() ? build.getAsInt() : null, getPluginMeta().getVersion());
    }

    private void deleteTestWorld(World world) {
        Path folder = world.getWorldFolder().toPath();
        if (!Bukkit.unloadWorld(world, false)) {
            getLogger().warning("Could not unload " + TEST_WORLD + "; leaving " + folder + " in place.");
            return;
        }
        if (!Files.exists(folder.resolve(WORLD_MARKER))) {
            getLogger().warning("Not deleting " + folder + ": it has no Preflight marker file.");
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not fully delete " + folder + "; it will be reused next boot.", e);
        }
    }

    private void applyFailureAction(List<TestResult> results) {
        List<TestResult> blocking = results.stream().filter(TestResult::blocking).toList();
        if (blocking.isEmpty()) {
            phase = Phase.OPEN;
            return;
        }
        FailureAction action = failureAction();
        getLogger().severe(blocking.size() + " critical startup test(s) failed. Action: " + action);
        switch (action) {
            case WARN -> phase = Phase.OPEN;
            case LOCKDOWN -> {
                phase = Phase.LOCKED;
                getLogger().severe("Logins are refused until an admin runs /preflight unlock.");
            }
            case SHUTDOWN -> {
                phase = Phase.LOCKED;
                Bukkit.shutdown();
            }
            case DISABLE_PLUGIN -> disableFailedPlugins(blocking);
        }
    }

    private FailureAction failureAction() {
        String override = System.getProperty(ON_FAILURE_PROPERTY);
        String configured = override != null ? override : getConfig().getString("on-failure", "LOCKDOWN");
        try {
            return FailureAction.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            getLogger().warning("Unknown on-failure value '" + configured + "'; using LOCKDOWN.");
            return FailureAction.LOCKDOWN;
        }
    }

    private void disableFailedPlugins(List<TestResult> blocking) {
        Set<String> targets = new LinkedHashSet<>();
        boolean serverLevelFailure = false;
        for (TestResult result : blocking) {
            if (result.test().owner() == this) {
                serverLevelFailure = true;
            } else {
                targets.add(result.test().owner().getName());
            }
        }

        Map<String, Plugin> installed = Arrays.stream(getServer().getPluginManager().getPlugins())
                .collect(Collectors.toMap(Plugin::getName, Function.identity()));
        Map<String, List<String>> depends = installed.values().stream()
                .collect(Collectors.toMap(Plugin::getName, plugin -> plugin.getPluginMeta().getPluginDependencies()));

        for (String name : DisablePlan.order(targets, depends)) {
            Plugin plugin = installed.get(name);
            if (plugin != null && plugin != this && plugin.isEnabled()) {
                getLogger().severe("Disabling " + name + " after failed startup tests.");
                getServer().getPluginManager().disablePlugin(plugin);
            }
        }

        // Preflight's own tests can't name a culprit, so disabling something wouldn't be a fix.
        phase = serverLevelFailure ? Phase.LOCKED : Phase.OPEN;
        if (serverLevelFailure) {
            getLogger().severe("A server-level test failed, so logins stay refused. Use /preflight unlock to override.");
        }
    }

    /** /preflight [status|unlock], admin-only. Players without the permission don't see it. */
    private LiteralCommandNode<CommandSourceStack> command() {
        return Commands.literal("preflight")
                .requires(source -> source.getSender().hasPermission("preflight.admin"))
                .executes(context -> sendStatus(context.getSource().getSender()))
                .then(Commands.literal("status")
                        .executes(context -> sendStatus(context.getSource().getSender())))
                .then(Commands.literal("unlock")
                        .executes(context -> unlock(context.getSource().getSender())))
                .build();
    }

    private int unlock(CommandSender sender) {
        if (phase == Phase.LOCKED) {
            phase = Phase.OPEN;
            getLogger().warning(sender.getName() + " lifted the Preflight lockdown.");
            sender.sendMessage(Component.text("Lockdown lifted. Players can log in.", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("The server isn't locked (" + phase + ").", NamedTextColor.YELLOW));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int sendStatus(CommandSender sender) {
        long passed = lastResults.stream().filter(result -> result.status() == TestResult.Status.PASSED).count();
        long ran = lastResults.stream().filter(result -> result.status() != TestResult.Status.SKIPPED).count();
        sender.sendMessage(Component.text("Preflight: " + phase + ", " + passed + "/" + ran + " tests passed."));
        for (TestResult result : lastResults) {
            if (result.failed()) {
                NamedTextColor color = result.blocking() ? NamedTextColor.RED : NamedTextColor.YELLOW;
                sender.sendMessage(Component.text(result.test().id() + ": " + result.message(), color));
            }
        }
        return Command.SINGLE_SUCCESS;
    }
}
