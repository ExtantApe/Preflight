# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

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
