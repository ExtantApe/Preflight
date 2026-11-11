package com.extantape.preflight.livetest.upgrade;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only plugin for the preflight-upgrade live checks. Never install it on a real server. It does nothing but
 * start; each fixture jar wraps it in a descriptor that fails in its own way (see test/live/upgrade.mjs).
 */
public final class QuietPlugin extends JavaPlugin {
    @Override public void onEnable() { getLogger().info(getName() + " enabled"); }
}
