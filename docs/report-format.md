# report.xml format

Preflight writes `plugins/Preflight/report.xml` after each startup run. It's ordinary JUnit XML, so CI systems can read it as test results. Since 0.2.0 it also has a `<properties>` block, for tools that need to act on a run. One example is an upgrade helper that boots a copy of a server on a new Minecraft version and suggests which plugins to switch off.

This page describes format 1.

## Shape

```xml
<testsuite name="Preflight" tests="17" failures="2" errors="0" skipped="1" time="3.086" timestamp="2026-11-08T14:55:21">
  <properties>
    <property name="preflight.report.format" value="1"/>
    ...
  </properties>
  <testcase classname="Preflight" name="worlds-loaded" time="0.0"/>
  <testcase classname="FailingTests" name="always-fails" time="0.0">
    <failure type="FAILED" message="AssertionError: deliberate failure">...stack trace...</failure>
  </testcase>
  <testcase classname="Preflight" name="block-gravity" time="1.2">
    <system-err>...a WARNING failure...</system-err>
  </testcase>
  <testcase classname="Preflight" name="entity-spawn" time="0.0">
    <skipped message="Skipped by skip-tests"/>
  </testcase>
</testsuite>
```

- `classname` is the plugin that registered the check, and `name` is the check's name. Together, `classname:name` is the check's id, as used in `skip-tests`.
- `failures` counts only CRITICAL failures. WARNING failures go in `<system-err>`, so they don't fail a CI build.
- `timestamp` is when the run finished, in UTC with no zone suffix, as JUnit expects.
- A run that didn't finish leaves no file. Preflight deletes the old report at startup, and writes the new one to `report.xml.partial` before moving it into place, so `report.xml` is never half-written.

## Properties

Each property is a name and value pair. Keys follow one of these patterns:

- `preflight.<field>`
- `server.<field>`
- `plugin.<Name>.<field>`
- `test.<Owner>:<check>.<field>`

Field names never contain dots, but plugin names can, so split a `plugin.` key on its **last** dot.

Lists are comma-separated with no spaces. Plugin names can't contain commas. A list with nothing in it is written as an empty value, which means "none". A key that's missing means Preflight doesn't know.

### Run

| Key | Value |
| --- | --- |
| `preflight.report.format` | `1`. Raised only for a change that breaks existing readers. New keys don't raise it, so ignore keys you don't know. |
| `preflight.version` | Preflight's version |
| `preflight.finished-at` | When the run finished, ISO 8601 in UTC, e.g. `2026-11-08T14:55:21Z` |
| `preflight.server-checks` | `failed` if a CRITICAL built-in server check failed (worlds, chunks, blocks, scheduler and so on), else `passed`. When it's `failed`, the server itself is suspect, and switching plugins off won't fix it. |
| `preflight.on-failure` | The `on-failure` action in effect for this run, after any `-D` override: `LOCKDOWN`, `WARN`, `SHUTDOWN` or `DISABLE_PLUGIN` |
| `preflight.unreadable-jars` | Jars in `plugins/` whose `paper-plugin.yml` or `plugin.yml` couldn't be read: not a valid jar, broken YAML, or no `name`. These have no `plugin.` keys because they have no usable name. |
| `preflight.other-jars` | Jars in `plugins/` with no plugin descriptor at all, such as libraries |
| `server.brand` | e.g. `Paper` |
| `server.version` | The server's full version string, e.g. `26.1.2-74-e4e17fc (MC: 26.1.2)` |
| `server.minecraft-version` | e.g. `26.1.2` |
| `server.build` | Paper's build number, e.g. `74`. Left out if the server doesn't report one. |

### Plugins

There's one set of keys per plugin. This covers every plugin the server loaded, plus every jar directly in `plugins/` whose `paper-plugin.yml` or `plugin.yml` names a plugin that didn't load. Preflight reads those jars itself.

| Field | Value |
| --- | --- |
| `state` | `enabled`, `disabled` (loaded but not running, usually because `onEnable` threw) or `not-loaded` (the server refused the jar: a bad `api-version`, a missing dependency, a missing main class) |
| `version` | From the descriptor. Left out if empty. |
| `files` | Jar file names relative to `plugins/`. If two jars claim the same name, both are listed, and the report can't say which one the server used. |
| `descriptor` | `paper-plugin.yml` or `plugin.yml` |
| `depends` | Hard dependencies |
| `soft-depends` | Optional dependencies |
| `missing-depends` | Hard dependencies that aren't enabled. **Paper 26.x enables a plugin whose hard dependency failed to load**, so `state=enabled` with a non-empty `missing-depends` still means the plugin can't be relied on. |
| `required-by` | Every plugin that hard-depends on this one, directly or not. These are the plugins that would have to go off with it. |
| `failed-tests` | Ids of failed checks that point at this plugin: its own checks, plus `Preflight:all-plugins-enabled` if it's `disabled`, plus `Preflight:plugins-loaded` if it's `not-loaded` or has `missing-depends`. Preflight's own server checks are never listed against a plugin. |
| `reason` | The server's own error line from `logs/latest.log`, joined with the exception line under it and capped at 300 characters. For example: `Could not load plugin 'BadApi.jar' in folder 'plugins': org.bukkit.plugin.InvalidPluginException: Unsupported API version 99.0`. Only Paper's wording is matched. It's left out when not found, never guessed. |

Every jar directly in `plugins/` appears exactly once: in a plugin's `files`, in `preflight.unreadable-jars`, or in `preflight.other-jars`.

A plugin needs attention when its `state` isn't `enabled`, its `missing-depends` isn't empty, or its `failed-tests` isn't empty.

### Checks

| Field | Value |
| --- | --- |
| `result` | `passed`, `failed`, `timed-out` or `skipped` |
| `severity` | `critical` or `warning` |
| `message` | Failures only. The first line of the failure message, capped at 300 characters. |

## Built-in plugin checks

- `Preflight:all-plugins-enabled` (CRITICAL): every loaded plugin is enabled.
- `Preflight:plugins-loaded` (WARNING, new in 0.2.0): every plugin jar was readable and loaded, and every enabled plugin's hard dependencies are running. It's WARNING so that servers that already had a stray jar don't start locking down after updating.

## Running it on a throwaway copy

Two JVM flags override `config.yml` without editing it. Everything else in the copy's config, such as `skip-tests`, still applies.

```bash
java -Dpreflight.on-failure=WARN -Dpreflight.shutdown-after-run=true -jar paper.jar --nogui
```

With these flags the server boots, runs the checks, writes `report.xml` and stops itself. Preflight logs a line saying it's using the overrides.

## What's tested

Unit tests validate the report against a JUnit schema (`preflight-plugin/src/test/resources/junit.xsd`), check that every property survives an XML round trip, and check Paper's log wording against lines copied from a real boot. Live runs on Paper 26.1.2, 26.2 and 26.3 boot deliberately broken plugins of each kind above and check the resulting report field by field.
