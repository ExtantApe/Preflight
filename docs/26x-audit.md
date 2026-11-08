# 26.x audit

Preflight targets Paper 26.1 and up, on Java 25. It was first written for Spigot 1.21.11 and moved before its first release. 1.21.x and Spigot are no longer supported. The code compiles against the Paper 26.1.2 API with deprecation and removal warnings turned on, and there are none.

## Modernised

- **`paper-plugin.yml` instead of `plugin.yml`.** Preflight declares no dependencies of its own. Plugins that add tests list Preflight as an optional server dependency with `load: BEFORE` and `join-classpath: true`, so it enables first and its API classes are visible to them. The example plugin does this. Paper's docs still mark Paper plugins as experimental. That's a small risk, and the live scenarios run on every supported version to cover it.
- **Brigadier command through the lifecycle API.** `/preflight`, `/preflight status` and `/preflight unlock` are a command tree registered in `onEnable`. The permission check is in the tree, so players without `preflight.admin` don't see the command. `plugin.yml` commands aren't available to Paper plugins, so this was required, not optional.
- **Adventure text.** The login refusal, command replies, the item display name test and the scoreboard objective title are Components. There are no legacy colour codes.
- **Plugin metadata.** `DISABLE_PLUGIN` reads hard dependencies from `getPluginMeta()`, which covers both `paper-plugin.yml` and `plugin.yml` plugins.
- **Flat test world.** Paper 26.1.2 logged `No key layers in MapLike[{}]` when the flat world was created with empty settings, so the layers are spelled out.
- **Java 25 toolchain and bytecode,** with `api-version: '26.1'`.

## Kept, and why

- **The `preflight-api` module stays plain Bukkit types.** It only uses `Plugin`, `World` and `Location`, so a plugin can depend on it without anything Paper-specific in its signatures.
- **`ServicesManager` for the registry.** It still works for Paper plugins and for older `plugin.yml` plugins alike, and it doesn't need the bootstrapper.
- **Bukkit events** for `ServerLoadEvent` and `AsyncPlayerPreLoginEvent`. Paper has no lifecycle replacement for either. The login gate runs before a player exists, which is what Preflight needs.
- **No bootstrapper.** It's marked highly experimental and only matters for datapack functions and registry changes, which Preflight doesn't use.
- **`ItemMeta` in the item test.** The test checks that item meta and persistent data survive a round trip. That's the API other plugins still use, so it's the one worth testing.
- **Bukkit's scheduler.** Preflight runs on the main thread of a single server. Folia isn't a target.

## Tested

Unit tests pass. Five live scenarios ran on throwaway Paper 26.1.2 (build 74), 26.2 (build 135) and 26.3 (build 244) servers, all passing on each: a clean run that stops itself and deletes the test world, a lockdown after a plugin throws in `onEnable` and then `/preflight unlock`, `DISABLE_PLUGIN` disabling a dependent plugin before the failing one, `SHUTDOWN`, and every login refused until the tests finished. Logins came from a small protocol bot that only gets as far as the login step. The example plugin added its tests through `paper-plugin.yml`, and the test fixtures added theirs through an older `plugin.yml` with `softdepend`. Both worked.

Nothing was mocked: the servers and plugins were real. Not tested: a real game client, so whether non-admins can see `/preflight` in tab completion hasn't been checked; a `DISABLE_PLUGIN` dependency declared in `paper-plugin.yml` rather than `plugin.yml`; and a real plugin stack.
