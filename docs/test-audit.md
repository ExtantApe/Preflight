# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

## 2026-11-11: preflight-upgrade's live checks, in the repo

The first upgrade live checks ran from a scratch folder that's gone, so nobody could rerun them. `test/live/upgrade.mjs` replaces them. It runs the released CLI jar against a made-up server folder that a real Paper jar booted once to make its worlds, and lets the tool boot its own copies. Everything lives in a fresh temp folder, which an exit handler deletes, pass or fail. Four test-only plugins, built from `test/live/upgrade-fixtures`, fail in different ways: `PfBadApi` (api-version 99.0), `PfNoMain` (a main class the jar lacks), `PfNeedsBad` (`depend` on PfBadApi) and `PfChainEnd` (`paper-plugin.yml`, requires PfNeedsBad). `test/live/HoldLock.java` holds a world's `session.lock` the way a running server does.

Results: 95/95 on a server made with 26.1.2 and checked on 26.3, a real upgrade, after the fix below. Before it, both 26.1.2 and 26.3 were 94/95.

### Each promise in docs/upgrade.md, and its check

- **Check, all clear:** exit 0, status `all-clear`, nothing off or blocked; the copy is made next to the server and deleted afterwards; the real server folder is byte-for-byte unchanged.
- **Links are copied as real files:** the server's first world is a link to a folder outside it, and a second world's `level.dat` is a linked file. While the check runs, the copy's world is seen as a real folder, never a link; afterwards every region file and `level.dat` of the real worlds has the same sha256.
- **Broken plugins and the whole required-by chain:** exit 10, status `switch-off`; all four fixtures are offered, the two that didn't load say so, `PfNeedsBad` is off because of PfBadApi and `PfChainEnd` because of PfNeedsBad; the good plugins aren't offered; each entry names its jar and that jar's real sha256. The text plan lists them too.
- **Never-off rules:** a rule (in lower case) on PfNeedsBad makes the plan `wait`, exit 11, with the rule and the reason under `blocked`; `recheck` lists only installed plugins; `apply` refuses the plan.
- **Check stops before copying (exit 12, `stop`, no copy, server untouched):** a plugin jar as `--paper`; a made-up paperclip jar for 1.21.4; no `eula.txt` (moved aside, never written); a rules file that's broken JSON, an unknown format, or unreadable (mode 000); a config naming MySQL, PostgreSQL (nested under `storage:`) or MongoDB. With `--allow-shared-database`, the check runs and the plan warns.
- **Apply refuses (exit 13, server untouched):** no `--yes` with stdin closed, and with `yes` piped in but no terminal; a plan for another folder or a missing one; a status that doesn't match the contents; a jar changed (one byte appended) or gone since the check; a `.jar.disabled` already there; an all-clear plan; a wait plan; the same plan applied twice; a held `session.lock` with nothing on the port; a running Paper server on the port.
- **Apply:** exit 0; the four jars become `.jar.disabled` with the same bytes; good jars and everything outside `plugins/` unchanged; `switched-off.json` lists the four with why, date, target and restore point; one restore point zip holding every jar and a `README.txt` whose sha256s match the originals, and each jar in the zip is byte-for-byte the original.
- **Status:** `--json` has the documented keys, says stopped, lists the four and one restore point; it says running when a lock is held or the port answers. The text form says the same.
- **Restore:** a name matched ignoring case puts one back; a name the tool didn't switch off is refused; without `--yes` nothing changes; a plugin whose jar name is taken stays off, says so, exits 13, and the newer jar isn't touched; `--all` puts the rest back, and the plugins folder then matches what it was before apply, byte for byte.
- **JSON and exit codes:** stdout is only the JSON plan and progress goes to stderr; every documented key is there, with format 1, the tool version, the server's real path, an ISO time, the full target and the Preflight block; a stop plan has `stop` and `target.file`. Exit codes 0, 2 (an unknown option, apply without `--plan`), 10, 11, 12 and 13 are each seen.

### Gaps

- **Fixed: apply and restore left a lock file behind when they refused a running server.**
  - With a world's `session.lock` held, `apply` refused for the right reason, but it had already created `plugins/Preflight/upgrade/.lock`. Its own lock was taken before `refuseIfRunning`.
  - Both now check before taking the lock, and again once they hold it.
  - `FlowTest.refusesWhileTheServerRuns` now also asserts that no lock file appears. It fails without the fix.
  - The live check passes.

### Skipped, with reasons

- **Plugins switched off in the copy (Discord bridges and so on) and "Not checked".** The list is matched by real plugins' names, so a fixture would have to pose as one. Unit tests cover it (`givenCopyIsPreparedAndKept`, `anythingThatCantBeJudgedOffersNothing`).
- **`--copy`, `--exclude`, `--work-dir`, leftover copies, a copy that hangs, and a report that's missing, unfinished or from an old Preflight.** Covered by FlowTest with FakePaper. Nothing about them depends on a real Paper.
- **Preflight's own server checks failing.** A real Paper can't be made to fail them on purpose without a broken jar; covered by PlanTest.
- **A rename failing part-way during apply.** It can't be forced on a real filesystem without root tricks; the code puts renamed jars back.
- **26.2.** Run on 26.1.2 and 26.3 only, as asked.
- **Network.** Each Paper boot, including the tool's own copies (which leave out `cache/`), has paperclip fetch the official Mojang server jar. The game ports are only ever on 127.0.0.1.

## 2026-11-10: the live scenarios, in the repo

The first audit's main gap is closed. `test/live/run.mjs` runs the five scenarios on throwaway Paper servers, one at a time, with two test-only plugins (`test/live/fixture`, old-style `plugin.yml` with `softdepend`; `test/live/dependent`, `paper-plugin.yml` requiring the first) and a mineflayer bot for logins. 17/17 on Paper 26.1.2:

- **Clean run:** the server stops itself, the report has no failures and replaces a stale one, the example plugin's tests ran, the test world is deleted.
- **Logins held:** while a slow test runs, the old report is already gone and a login is refused with the reason; once the tests pass, the same login works.
- **Lockdown:** a plugin that throws in `onEnable` locks the server; logins are refused with the reason; the report names the plugin; `/preflight unlock` lets logins in.
- **Disable plugin:** the plugin that needs the failing one, declared in `paper-plugin.yml`, is disabled first and really stops; with no server-level failure, logins are open. This closes the other gap.
- **Shutdown:** a critical failure stops the server, and the report records it.

Not run on 26.2 or 26.3 this time; the harness takes `PF_TEST_PAPER` and adds ViaVersion and ViaBackwards for them.

## 2026-11-10: first full audit (at 0.3.0)

What Preflight promises (README, `docs/upgrade.md`, `docs/decisions.md`), and what proves it. Unit tests are in `preflight-plugin/src/test` and `preflight-upgrade/src/test`. The live scenarios are in `docs/26x-audit.md`: five, run by hand on throwaway Paper 26.1.2, 26.2 and 26.3 servers.

### Covered

**The run itself**
- **Tests run one at a time, in order. One failure doesn't stop the rest**, and late steps, timeouts and awaits behave as documented: `TestRunnerTest`, 15 tests.
- **Cleanups run in reverse order, even when a test fails, and a throwing cleanup doesn't stop the run**, so the test world never keeps a plugin's leftovers: `cleanupsRunInReverseOrderEvenOnFailure`, `throwingCleanupDoesNotStopTheRun`.
- **Late or duplicate registration is refused:** `registryRejectsDuplicatesAndLateRegistration`, `registryRejectsNonPositiveTimeout`.

**Failing closed**
- **Every login is refused until the tests finish, and a lockdown holds until `/preflight unlock`:** live, on all three versions.
- **Only critical failures block logins:** `warningFailureIsNotBlocking`, `reportCountsOnlyCriticalFailuresAndEscapesText`.
- **`DISABLE_PLUGIN` disables dependents first and leaves the rest alone:** `disablePlanPutsDependentsFirstAndLeavesOthersAlone`, `disablePlanHandlesDiamondDependency`, plus live.
- **A failure in Preflight's own tests still locks down,** even under `DISABLE_PLUGIN`: live.
- **A run that didn't finish leaves no report,** so CI's `grep` fails. The upgrade tool's side is tested (`reportlessBootSaysSo`); the plugin deleting the old report at start isn't, and goes into the harness below.

**The report**
- **Valid JUnit XML, with every plugin's state and the server's own error line, including jars that never loaded:** `ReportTest` and `SupportTest`, 14 tests, including awkward names and values.

**The upgrade tool** (it touches a real server folder, so this is the part with the most to lose)
- **It never touches a file outside `plugins/`, and never overwrites a disabled jar:** `neverTouchesAFileOutsidePlugins`, `applyNeverOverwritesADisabledJar`.
- **It refuses while the server runs, refuses a changed jar or another server's plan, and asks for a yes:** `refusesWhileTheServerRuns`, `applyRefusesAChangedJarAnotherServerOrAnUnfitPlan`, `commandLineAsksForYesAndUsesExitCodes`.
- **Every change can be undone, one by one:** `applyThenRestoreOneByOne`.
- **It only deletes copies it made, and lists leftovers rather than deleting them:** `deleteRefusesAFolderWithoutTheMarker`, `leftoverCopiesAreListedNotDeleted`.
- **It stops rather than share an outside database, or run without the EULA:** `checkStopsOnSharedDatabaseUnlessAllowed`, `sharedDatabaseDetection`, `checkStopsWithoutTheEulaOrWithBrokenRules`.
- **Unknown stays unknown:** `anythingThatCantBeJudgedOffersNothing`, `findsPaperReasonsAndLeavesOthersUnknown`, `flagsARunThatDidntWarnAndUnreadableJars`.

### Gaps

- **The live scenarios aren't in the repo.** They ran by hand for 0.1.0 and 0.3.0, so nobody can rerun them. Next step: a `test/live` harness for the five scenarios, like the other plugins have. It's on the lead's list, too big for this pass.
- **A `DISABLE_PLUGIN` dependency declared in `paper-plugin.yml`.** The code reads both kinds through `getPluginMeta()`, but only `plugin.yml` fixtures were run live. It goes into the harness above.

### Skipped, with reasons

- **A non-admin running `/preflight unlock`.** During a lockdown no player can log in, so only the console can unlock. Otherwise Brigadier's `requires` hides the command, and that's Paper's code.
- **Join, chat and player-action flows.** The Bukkit API can't make a player, as the README says.
- **A real client and a real plugin stack:** they belong on the real-server list, not here.
