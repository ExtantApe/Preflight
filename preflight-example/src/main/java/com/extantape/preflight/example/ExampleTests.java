package com.extantape.preflight.example;

import com.extantape.preflight.api.Check;
import com.extantape.preflight.api.PreflightRegistry;
import com.extantape.preflight.api.Severity;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

final class ExampleTests {
    private ExampleTests() {}

    static void register(Plugin plugin) {
        PreflightRegistry registry = Bukkit.getServicesManager().load(PreflightRegistry.class);
        if (registry == null) {
            return;
        }

        // Runs and finishes immediately.
        registry.register(plugin, "data-folder", context ->
                Check.that(plugin.getDataFolder().exists() || plugin.getDataFolder().mkdirs(),
                        "Can't create the plugin's data folder"));

        // Spans several ticks. It stays open until the later() step has run.
        registry.register(plugin, "block-survives-a-second", context -> {
            Block block = context.origin().getBlock();
            block.setType(Material.GOLD_BLOCK, false);
            context.later(20, () -> Check.equal(Material.GOLD_BLOCK, block.getType(), "block after 20 ticks"));
        });

        // Waits on off-thread work, the shape a database check would take. A WARNING reports the
        // problem without triggering the on-failure action. This one allows 10 seconds.
        registry.register(plugin, "async-backend", Severity.WARNING, 200, context -> {
            CompletableFuture<Boolean> ping = CompletableFuture.supplyAsync(() -> true);
            context.await(ping, reachable -> Check.that(reachable, "Backend unreachable"));
        });
    }
}
