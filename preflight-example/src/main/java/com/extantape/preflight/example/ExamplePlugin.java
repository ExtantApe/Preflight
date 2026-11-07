package com.extantape.preflight.example;

import org.bukkit.plugin.java.JavaPlugin;

public final class ExamplePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // Keep all Preflight references in another class. This one must still load when Preflight
        // isn't installed, and the check below means ExampleTests is then never touched.
        if (getServer().getPluginManager().getPlugin("Preflight") != null) {
            ExampleTests.register(this);
        }
    }
}
