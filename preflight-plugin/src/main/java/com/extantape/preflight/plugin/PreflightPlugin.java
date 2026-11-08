package com.extantape.preflight.plugin;

import com.extantape.preflight.api.PreflightRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
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
        BuiltInTests.register(registry, this);
        getServer().getPluginManager().registerEvents(this, this);
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
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, current == Phase.TESTING
                    ? "Server is still running startup checks. Try again shortly."
                    : "Server failed its startup checks and is locked for maintenance.");
        }
    }

    private void runTests() {
        Set<String> skipped = new HashSet<>(getConfig().getStringList("skip-tests"));
        List<RegisteredTest> tests = registry.close().stream()
                .filter(test -> {
                    boolean skip = skipped.contains(test.id());
                    if (skip) {
                        getLogger().info("Skipping " + test.id() + " (skip-tests)");
                    }
                    return !skip;
                })
                .toList();

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
                .start(tests, results -> onFinished(world, results));
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
        return getConfig().getBoolean("shutdown-after-run", false);
    }

    /** A report left by an earlier boot must not be mistaken for this run's result. */
    private void deleteStaleReport() {
        try {
            Files.deleteIfExists(getDataFolder().toPath().resolve("report.xml"));
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
        long failed = results.stream().filter(TestResult::failed).count();
        getLogger().info((results.size() - failed) + "/" + results.size() + " startup tests passed.");
    }

    private void writeReport(List<TestResult> results) {
        Path report = getDataFolder().toPath().resolve("report.xml");
        try {
            Files.writeString(report, JUnitReport.render(results), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not write " + report, e);
        }
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
        String configured = getConfig().getString("on-failure", "LOCKDOWN");
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
                .collect(Collectors.toMap(Plugin::getName, plugin -> plugin.getDescription().getDepend()));

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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sendStatus(sender);
            case "unlock" -> {
                if (phase == Phase.LOCKED) {
                    phase = Phase.OPEN;
                    getLogger().warning(sender.getName() + " lifted the Preflight lockdown.");
                    sender.sendMessage(ChatColor.GREEN + "Lockdown lifted. Players can log in.");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "The server isn't locked (" + phase + ").");
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        long failed = lastResults.stream().filter(TestResult::failed).count();
        sender.sendMessage("Preflight: " + phase + ", " + (lastResults.size() - failed) + "/" + lastResults.size()
                + " tests passed.");
        for (TestResult result : lastResults) {
            if (result.failed()) {
                ChatColor color = result.blocking() ? ChatColor.RED : ChatColor.YELLOW;
                sender.sendMessage(color + result.test().id() + ": " + result.message());
            }
        }
    }
}
