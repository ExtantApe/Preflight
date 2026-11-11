# Preflight

A Paper plugin that runs smoke tests when the server boots, before players can join. Other plugins can add their own tests.

**Status:** early, version 0.3.0. It needs Minecraft 26.1 or newer and Java 25; 1.21 isn't supported. It has passed clean runs, lockdown, plugin disable, shutdown and broken-plugin report tests on throwaway Paper 26.1.2, 26.2 and 26.3 servers, using a protocol bot for logins. It's Paper-only, so it won't load on Spigot. The upgrade tool passed live checks, apply and restore on made-up servers on the same three versions. A real plugin stack hasn't been tested.

## What it does

1. Other plugins register tests during `onEnable`.
2. When the server finishes loading, Preflight creates a disposable flat world and refuses logins.
3. It runs each test on the main thread, one at a time.
4. It writes `plugins/Preflight/report.xml` (JUnit format) and deletes the test world.
5. It applies the `on-failure` setting if a `CRITICAL` test failed. Otherwise it opens logins.

Built-in tests check that every installed plugin enabled and that every plugin jar loaded with its hard dependencies running, then cover worlds, chunks, blocks, gravity, entities, the scheduler, events, item meta and persistent data, inventories, recipes, and scoreboards.

## Install

Download `Preflight-0.3.0.jar` from [Releases](https://github.com/ExtantApe/Preflight/releases), put it in your server's `plugins/` folder and restart. Paper 26.1 or newer and Java 25 are required.

## Build

Requires Java 25. The Gradle wrapper is included.

```bash
./gradlew build
```

The plugin jar is `preflight-plugin/build/libs/Preflight-*.jar`.

## Configure

Edit `plugins/Preflight/config.yml`:

| `on-failure` value | When a critical test fails |
| --- | --- |
| `LOCKDOWN` (default) | Refuses logins until an admin runs `/preflight unlock` |
| `WARN` | Logs the failures and lets players in |
| `SHUTDOWN` | Stops the server |
| `DISABLE_PLUGIN` | Disables the failing plugin and anything that hard-depends on it. Failures in Preflight's own tests still cause a lockdown |

Use `skip-tests` to turn off false positives on your server, for example `Preflight:entity-spawn` if a plugin deliberately blocks mob spawning.

To override `on-failure` or `shutdown-after-run` without editing the file, for example on a throwaway copy of a server, start Java with `-Dpreflight.on-failure=WARN` or `-Dpreflight.shutdown-after-run=true`.

`/preflight status` shows the results. It requires `preflight.admin`, which defaults to ops.

## Add tests to your plugin

Add the `preflight-api` jar as a `compileOnly` dependency. Each release attaches it on GitHub.

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

The report also lists every plugin with its state, jar file, dependencies, failed checks and the server's own error line, including jars the server refused to load. [docs/report-format.md](docs/report-format.md) describes the fields.

## Upgrade to a new Minecraft version

`preflight-upgrade-0.3.0.jar`, on the same release page, is a command-line tool for a version upgrade. It boots a throwaway copy of your server on the new Paper jar with Preflight, then lists the plugins that won't start there and everything that needs them. With your yes, it switches them off on the real server, which must be stopped, after a restore point. It can switch each one back on later. It never deletes a jar: it renames it to `.jar.disabled`.

```bash
java -jar preflight-upgrade-0.3.0.jar check --server /path/to/server --paper paper-26.3-244.jar
```

You don't need Preflight installed on the server to use it. Some details:

- The copy can't post to Discord or join a proxy.
- The check stops if a plugin uses an outside database the copy would share.
- Never-off rules can make the plan wait instead.

[docs/upgrade.md](docs/upgrade.md) has every command and option.

## Testing

Unit tests run with `./gradlew build`. The live scenarios in `test/live/run.mjs` boot throwaway Paper servers on 127.0.0.1 with two test-only plugins and a bot that only logs in: a clean run, logins held during the tests, a lockdown and `/preflight unlock`, `DISABLE_PLUGIN`, and `SHUTDOWN`. Instructions are at the top of that file.

`test/live/upgrade.mjs` runs the real `preflight-upgrade` jar against a made-up server that a real Paper jar has booted, in a temp folder that's deleted afterwards: an all-clear check, broken plugins and their dependents, a never-off rule, every refusal in [docs/upgrade.md](docs/upgrade.md), apply, status and restore. `PF_TEST_FROM` and `PF_TEST_PAPER` pick the version the server is made with and the one it's checked on. Instructions are at the top of that file.

## Limitations

- **No simulated players.** The Bukkit API can't create one, so join, chat, and player-action flows aren't covered.
- **Tests fire real events.** Logging or Discord bridge plugins may react to activity in `preflight_test`, so test in staging where you can.
- **Timeouts only cover waiting.** A test that blocks the main thread stalls the server until the watchdog steps in.
- **Only startup runs tests.** `/reload` skips them.

## License

[MIT](LICENSE)
