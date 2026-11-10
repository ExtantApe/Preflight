# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

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
