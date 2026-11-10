package com.extantape.preflight.livetest;

import org.bukkit.plugin.java.JavaPlugin;

/** Test-only: needs PfFixture, declared in paper-plugin.yml, so DISABLE_PLUGIN must switch it off first. */
public final class DependentPlugin extends JavaPlugin {
    @Override public void onEnable() { getLogger().info("PfDependent enabled"); }
    @Override public void onDisable() { getLogger().info("PfDependent disabled"); }
}
