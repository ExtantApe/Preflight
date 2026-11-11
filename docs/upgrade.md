# preflight-upgrade

A command-line tool for moving a Paper server to a new Minecraft version. It boots a throwaway copy of the server on the new Paper jar with Preflight, then says which plugins won't start there and what needs them. If you agree, it switches those plugins off on the real server, while it's stopped, after a restore point. It can switch each one back on later.

It ships as `preflight-upgrade-<version>.jar` on each [release](https://github.com/ExtantApe/Preflight/releases), next to the plugin, with a `.sha256` file to check the download against. You don't need Preflight installed on the server: the tool puts its own copy into the test copy. It needs Java 25.

A Preflight pass means a plugin starts, not that it works. Try the important ones by hand after upgrading.

## Check

```bash
java -jar preflight-upgrade-0.3.0.jar check --server /path/to/server --paper /path/to/paper-26.3-244.jar
```

1. Copies the server to a folder next to it, named `<server>.preflight-check-<time>`. A different place can be set with `--work-dir`. Logs, old-version caches and this tool's own files are left out. Links are copied as real files, so the copy can never open the real worlds.
2. Prepares the copy so it can run next to the real server:
   - its own free port on 127.0.0.1
   - no RCON or query
   - plugins that talk to the outside world switched off: Discord bridges, vote listeners, map uploaders, proxies, Geyser
   - this release's Preflight in place of whatever was there
3. Starts the copy with `-Dpreflight.on-failure=WARN -Dpreflight.shutdown-after-run=true`, so it runs every check and stops by itself.
4. Reads `plugins/Preflight/report.xml` from the copy, prints the plan, and deletes the copy.

The real server isn't changed and can keep running. A copy of a running server may catch a world mid-save, and the plan says so in its warnings. That's fine for checking plugins, because the copy is thrown away.

If an earlier check was killed before it could clean up, its copy is listed in the warnings next time. It isn't deleted, because another check might be using it. Each copy holds a `.preflight-upgrade-copy` file, and only folders with that file are ever deleted by the tool.

The plan has four parts:

- **Would be switched off:** plugins that didn't load, didn't start, are missing a hard dependency, or failed a critical Preflight check. Also everything that hard-depends on those, however far down the chain.
- **Must stay on, but wouldn't work:** a plugin in your never-off rules, or one whose file can't be named safely. If anything is here, the upgrade should wait.
- **Not checked:** plugins switched off in the copy, and anything that needs them. These are never offered for switching off.
- **For you to decide:** warnings and soft dependents, listed only.

Options:

| Option | Default | |
| --- | --- | --- |
| `--json` | off | Print the plan as JSON on stdout. Progress always goes to stderr. |
| `--rules <file>` | `<server>/plugins/Preflight/upgrade/rules.json` | Never-off rules, below |
| `--work-dir <dir>` | the server's parent folder | Where the copy goes. It needs room for the server plus 2 GB. |
| `--copy <dir>` | | Use a copy you've already made, for example one taken while saving was paused. It's prepared and booted as above, with its old logs, world locks and Preflight report removed first. The tool doesn't delete it afterwards, because it only deletes folders it made itself. It must not be the server folder, or inside or around it. |
| `--exclude <path>` | | Leave a path out of the copy, relative to the server, e.g. `backups`. Can be repeated. |
| `--memory <size>` | `4G` | Memory for the copy, e.g. `2G` |
| `--timeout <seconds>` | `600` | How long the copy may run before it's stopped |
| `--allow-shared-database` | off | See below |
| `--java <path>` | the Java running the tool | Java for the copy |

It stops without a plan, and says why, when:

- The `--paper` file isn't a Paper server jar, or is for a Minecraft version older than 26.1. Nothing is copied or started.
- `eula.txt` doesn't say `eula=true`. The copy needs the same agreement, and the tool won't accept it for you.
- The rules file exists but can't be read. A lost never-off rule could let a protective plugin go.
- A plugin's config points at an outside database such as MySQL, MariaDB, PostgreSQL or MongoDB. The copy would share it with the real server and could write to it. If that's fine, run the check again with `--allow-shared-database`.
- The copy didn't write a report, or the report is unfinished, from an unknown format, or from a Preflight older than 0.2.0.
- Preflight's own server checks failed. Then the new version itself doesn't run properly on this server, and switching plugins off won't fix that.

## Apply

```bash
java -jar preflight-upgrade-0.3.0.jar check --server /path/to/server --paper paper.jar --json > plan.json
java -jar preflight-upgrade-0.3.0.jar apply --server /path/to/server --plan plan.json
```

It shows what it will switch off and asks you to type `yes`. `--yes` skips the question, for a tool that has already asked a person. Without a terminal and without `--yes`, it refuses. It refuses, with nothing changed, when:

- **the server is running.** Something answers on its port, or a world's `session.lock` is held. If either can't be checked, that counts as running.
- **the plan is for a different server folder.**
- **the plan isn't a "switch-off" plan,** or its status doesn't match its contents.
- **a jar has changed or gone since the check,** by sha256.
- **a `.jar.disabled` with the same name is already there.**

Then it writes a restore point to `plugins/Preflight/upgrade/restore-points/<UTC time>.zip`. That's every `.jar` and `.jar.disabled` in `plugins/`, read back and compared by sha256 before anything moves. Each plugin then goes off by renaming `Name.jar` to `Name.jar.disabled`, which Paper doesn't load. Nothing is deleted. If a rename fails part-way, the ones already renamed are put back.

The restore point covers only plugin jars. **Worlds aren't backed up by this tool: take a full backup before the upgrade itself.**

## Restore

```bash
java -jar preflight-upgrade-0.3.0.jar restore --server /path/to/server --plugin Name
java -jar preflight-upgrade-0.3.0.jar restore --server /path/to/server --all
```

Switches plugins this tool switched off back on, one at a time, only while the server is stopped. Names match ignoring case. If a jar with the same name is already back in `plugins/`, for example a newer version you installed, that plugin is left as it is and says so. Nothing is overwritten.

## Status

```bash
java -jar preflight-upgrade-0.3.0.jar status --server /path/to/server [--json]
```

Shows whether the server is running, what this tool has switched off and why, and the restore points.

## Never-off rules

`<server>/plugins/Preflight/upgrade/rules.json`, the same format as Leaf Juice's `upgrade-rules.json`:

```json
{
  "format": 1,
  "neverOff": [{"plugin": "LuckPerms", "why": "Permissions; nothing is safe without it."}],
  "recheck": [{"plugin": "ShopPlugin", "note": "Buy and sell one item by hand."}]
}
```

- **`neverOff`:** plugins that must never be switched off. If one would have to go, the plan says to wait, and `apply` refuses it.
- **`recheck`:** things to try by hand after the upgrade. They're listed for installed plugins only, and never switch anything.

Names match ignoring case.

## Files

Everything the tool keeps is plain text or a plain zip, under `<server>/plugins/Preflight/upgrade/`:

- **`rules.json`:** your rules, above.
- **`switched-off.json`:** what's off, why, when, for which version, and which restore point covers it.
- **`restore-points/*.zip`:** one per apply, each with a `README.txt` listing every jar's sha256. To put a jar back by hand, copy it from the zip into `plugins/`.

## JSON

`check --json` prints one object:

```json
{
  "format": 1,
  "tool": "0.3.0",
  "server": "/absolute/path/to/server",
  "checkedAt": "2026-11-08T16:00:00Z",
  "target": {"file": "paper-26.3-244.jar", "brand": "Paper", "minecraft": "26.3", "build": 244, "version": "26.3-244-35a2d15 (MC: 26.3)"},
  "status": "switch-off",
  "off": [{"name": "BadApi", "files": ["BadApi.jar"], "sha256": {"BadApi.jar": "…"}, "why": ["It didn't load. …"]}],
  "blocked": [{"name": "…", "rule": "…", "why": ["…"]}],
  "notChecked": [{"name": "DiscordSRV", "why": "Off in the test copy, so it can't reach anything outside. It wasn't checked."}],
  "warnings": ["…"],
  "recheck": [{"plugin": "…", "note": "…"}],
  "preflight": {"version": "0.3.0", "onFailure": "WARN", "serverChecks": "passed"}
}
```

- **`status`:** one of `switch-off`, `all-clear`, `wait` or `stop`. `stop` comes with a `stop` field giving the reason. In a `stop` plan, `target` may have only `file`.
- **`format`:** raised only for a change that breaks readers. Ignore keys you don't know.

`status --json` prints `{"format", "server", "running", "runningWhy", "switchedOff", "restorePoints"}`.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Done, or the check found nothing to switch off |
| 10 | The check found plugins to switch off |
| 11 | Wait: a never-off plugin would have to go |
| 12 | The check couldn't judge (see `stop`) |
| 13 | Refused, or partly done (restore left a plugin off). Nothing was changed unless the output says so. |
| 2 | Bad arguments |
| 1 | Unexpected error |

## Limits

- Paper only, 26.1 and newer.
- It checks that plugins start, not that they work.
- The list of plugins that talk to the outside world is fixed. A plugin it doesn't know can still reach outside services from the copy.
- Database detection reads only each plugin's `config.yml`, `config.yaml` or `settings.yml`.
- Worlds aren't in the restore point.

## Live checks

`test/live/upgrade.mjs` checks the promises on this page against real Paper servers in a temp folder, which it deletes when it ends: `./gradlew build`, then `node test/live/upgrade.mjs` with the variables listed at the top of that file. [test-audit.md](test-audit.md) maps each promise to its check.
