package com.extantape.preflight.livetest;

import com.extantape.preflight.api.Check;
import com.extantape.preflight.api.PreflightRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only plugin for Preflight's live scenarios. Never install it on a real server. What it does comes from
 * plugins/PfFixture/mode.txt: "pass" (a test that passes), "fail" (a critical test that fails), "slow" (a test that
 * passes after 10 seconds, to hold logins) or "throw" (onEnable throws, so the plugin never enables).
 * It uses an old-style plugin.yml with softdepend, the way older plugins add tests.
 */
public final class FixturePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        String mode = "pass";
        try {
            Path f = getDataFolder().toPath().resolve("mode.txt");
            if (Files.exists(f)) mode = Files.readString(f).trim();
        } catch (Exception e) {
            getLogger().warning("Couldn't read mode.txt: " + e);
        }
        getLogger().info("PfFixture mode " + mode);
        if (mode.equals("throw")) throw new IllegalStateException("PfFixture was told to fail on enable");
        if (Bukkit.getPluginManager().getPlugin("Preflight") == null) return;
        Tests.register(this, mode);
    }

    /** Every reference to Preflight's API stays in here, so the plugin loads without it. */
    static final class Tests {
        static void register(JavaPlugin plugin, String mode) {
            PreflightRegistry registry = Bukkit.getServicesManager().load(PreflightRegistry.class);
            switch (mode) {
                case "fail" -> registry.register(plugin, "always-fails", context -> Check.that(false, "PfFixture fails on purpose"));
                case "slow" -> registry.register(plugin, "slow", com.extantape.preflight.api.Severity.CRITICAL, 400,
                        context -> context.later(200, () -> {}));
                default -> registry.register(plugin, "passes", context -> Check.that(true, "never shown"));
            }
        }
    }
}
