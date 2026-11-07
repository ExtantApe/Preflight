package io.github.extantape.preflight.plugin;

import io.github.extantape.preflight.api.Check;
import io.github.extantape.preflight.api.PreflightRegistry;
import io.github.extantape.preflight.api.Severity;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

/** Smoke tests for the server itself, plus the check that every installed plugin actually enabled. */
final class BuiltInTests {
    private BuiltInTests() {}

    static void register(PreflightRegistry registry, Plugin self) {
        registry.register(self, "all-plugins-enabled", context -> {
            List<String> disabled = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                    .filter(plugin -> !plugin.isEnabled())
                    .map(Plugin::getName)
                    .toList();
            Check.that(disabled.isEmpty(), "Plugins failed to enable: " + disabled);
        });

        registry.register(self, "worlds-loaded", context ->
                Check.that(!Bukkit.getWorlds().isEmpty(), "No worlds are loaded"));

        registry.register(self, "chunk-load", context -> {
            Chunk chunk = context.world().getChunkAt(context.origin().getBlockX() >> 4, 200);
            Check.that(chunk.load(true), "Chunk failed to load");
            Check.that(chunk.isLoaded(), "Chunk reports as not loaded");
        });

        registry.register(self, "block-set-get", context -> {
            Block block = context.origin().getBlock();
            block.setType(Material.STONE, false);
            Check.equal(Material.STONE, block.getType(), "block type right after set");
            context.later(2, () -> Check.equal(Material.STONE, block.getType(), "block type after 2 ticks"));
        });

        registry.register(self, "block-gravity", Severity.WARNING, PreflightRegistry.DEFAULT_TIMEOUT_TICKS, context -> {
            Block block = context.origin().getBlock();
            block.setType(Material.SAND, true);
            context.later(40, () -> Check.that(block.getType() != Material.SAND, "Unsupported sand did not fall"));
        });

        registry.register(self, "entity-spawn", Severity.WARNING, PreflightRegistry.DEFAULT_TIMEOUT_TICKS, context -> {
            Entity entity = context.world().spawnEntity(context.origin(), EntityType.PIG);
            context.onCleanup(entity::remove);
            context.later(2, () -> Check.that(entity.isValid(), "Spawned entity was removed or never added"));
        });

        registry.register(self, "scheduler", context -> {
            CompletableFuture<Boolean> ranOffMainThread = new CompletableFuture<>();
            Bukkit.getScheduler().runTaskAsynchronously(self, () ->
                    ranOffMainThread.complete(!Bukkit.isPrimaryThread()));
            context.await(ranOffMainThread, offMain -> {
                Check.that(offMain, "Async task ran on the main thread");
                Check.that(Bukkit.isPrimaryThread(), "Test step resumed off the main thread");
            });
        });

        registry.register(self, "event-bus", context -> {
            boolean[] received = new boolean[1];
            Listener listener = new Listener() {
                @EventHandler
                public void on(ProbeEvent event) {
                    received[0] = true;
                }
            };
            Bukkit.getPluginManager().registerEvents(listener, self);
            context.onCleanup(() -> HandlerList.unregisterAll(listener));
            Bukkit.getPluginManager().callEvent(new ProbeEvent());
            Check.that(received[0], "Listener did not receive a fired event");
        });

        registry.register(self, "item-meta-and-pdc", context -> {
            NamespacedKey key = new NamespacedKey(self, "probe");
            ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = Check.notNull(item.getItemMeta(), "item meta");
            meta.setDisplayName("probe");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "value");
            Check.that(item.setItemMeta(meta), "setItemMeta was rejected");

            ItemMeta reread = Check.notNull(item.getItemMeta(), "re-read item meta");
            Check.equal("probe", reread.getDisplayName(), "display name");
            Check.equal("value", reread.getPersistentDataContainer().get(key, PersistentDataType.STRING), "PDC value");
        });

        registry.register(self, "inventory", context -> {
            Inventory inventory = Bukkit.createInventory(null, 9);
            Check.that(inventory.addItem(new ItemStack(Material.STONE, 5)).isEmpty(), "Item didn't fit");
            Check.that(inventory.contains(Material.STONE, 5), "Inventory lost the added items");
        });

        registry.register(self, "recipes", context ->
                Check.that(Bukkit.recipeIterator().hasNext(), "No recipes are loaded"));

        registry.register(self, "scoreboard", context -> {
            var manager = Check.notNull(Bukkit.getScoreboardManager(), "scoreboard manager");
            Objective objective = manager.getNewScoreboard()
                    .registerNewObjective("preflight", Criteria.DUMMY, "Preflight");
            objective.getScore("probe").setScore(7);
            Check.equal(7, objective.getScore("probe").getScore(), "score");
        });
    }

    /** Fired only by the event-bus test. */
    public static final class ProbeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }
}
