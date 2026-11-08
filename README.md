# Preflight

A Paper plugin that runs smoke tests when the server boots, before players can join. Other plugins can add their own tests.

**Status:** pre-release, version 0.1.0-SNAPSHOT. It needs Minecraft 26.1 or newer and Java 25; 1.21 isn't supported. It has passed clean runs, lockdown, plugin disable, and shutdown tests on throwaway Paper 26.1.2, 26.2 and 26.3 servers, using a protocol bot for logins. It's Paper-only, so it won't load on Spigot. A real plugin stack hasn't been tested. There's no release yet, so build it yourself.

## What it does

1. Other plugins register tests during `onEnable`.
2. When the server finishes loading, Preflight creates a disposable flat world and refuses logins.
3. It runs each test on the main thread, one at a time.
4. It writes `plugins/Preflight/report.xml` (JUnit format) and deletes the test world.
5. It applies the `on-failure` setting if a `CRITICAL` test failed. Otherwise it opens logins.

Built-in tests check that every installed plugin enabled, then cover worlds, chunks, blocks, gravity, entities, the scheduler, events, item meta and persistent data, inventories, recipes, and scoreboards.

## Build

Requires Java 25. The Gradle wrapper is included.

```bash
./gradlew build
```

Copy `preflight-plugin/build/libs/Preflight-*.jar` to your server's `plugins/` folder.

## Configure

Edit `plugins/Preflight/config.yml`:

| `on-failure` value | When a critical test fails |
| --- | --- |
| `LOCKDOWN` (default) | Refuses logins until an admin runs `/preflight unlock` |
| `WARN` | Logs the failures and lets players in |
| `SHUTDOWN` | Stops the server |
| `DISABLE_PLUGIN` | Disables the failing plugin and anything that hard-depends on it. Failures in Preflight's own tests still cause a lockdown |

Use `skip-tests` to turn off false positives on your server, for example `Preflight:entity-spawn` if a plugin deliberately blocks mob spawning.

`/preflight status` shows the results. It requires `preflight.admin`, which defaults to ops.

## Add tests to your plugin

Add the `preflight-api` jar as a `compileOnly` dependency. Build the jar yourself for now. Releases will attach it on GitHub.

In a `paper-plugin.yml`, declare Preflight as an optional dependency that loads first and joins your classpath:

```yaml
dependencies:
  server:
    Preflight:
      load: BEFORE
      required: false
      join-classpath: true
```

In an older `plugin.yml`, use `softdepend: [Preflight]` instead.

Keep every reference to Preflight in its own class, so your plugin still loads when Preflight isn't installed:

```java
// In your main class
@Override
public void onEnable() {
    if (getServer().getPluginManager().getPlugin("Preflight") != null) {
        MyTests.register(this);
    }
}

// In MyTests.java
static void register(Plugin plugin) {
    PreflightRegistry registry = Bukkit.getServicesManager().load(PreflightRegistry.class);

    registry.register(plugin, "shop-block-persists", context -> {
        Block block = context.origin().getBlock();
        block.setType(Material.CHEST, false);
        context.later(20, () -> Check.equal(Material.CHEST, block.getType(), "chest after 1s"));
    });
}
```

A test passes when it and all its `later` and `await` steps return without throwing. See [`preflight-example`](preflight-example/src/main/java/com/extantape/preflight/example/ExampleTests.java) for a complete plugin.

## Use it in CI

Set `shutdown-after-run: true` so the server stops after each run, then check the report:

```bash
grep -q 'failures="0"' plugins/Preflight/report.xml
```

Preflight deletes the old report at startup, so a run that didn't finish leaves no file and this command fails. You can also feed `report.xml` to any CI system that reads JUnit XML.

## Limitations

- **No simulated players.** The Bukkit API can't create one, so join, chat, and player-action flows aren't covered.
- **Tests fire real events.** Logging or Discord bridge plugins may react to activity in `preflight_test`, so test in staging where you can.
- **Timeouts only cover waiting.** A test that blocks the main thread stalls the server until the watchdog steps in.
- **Only startup runs tests.** `/reload` skips them.

## License

[MIT](LICENSE)
