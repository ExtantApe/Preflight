package com.extantape.preflight.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** check, apply, restore and status on made-up server folders, with a fake Paper jar. */
class FlowTest {
    @TempDir
    Path temp;
    Path server;
    Path plugins;

    @BeforeEach
    void madeUpServer() throws IOException {
        server = temp.resolve("made-up-server");
        plugins = server.resolve("plugins");
        Files.createDirectories(plugins);
        Files.writeString(server.resolve("server.properties"), "server-port=" + freePort() + "\nmotd=Made up\nenable-rcon=true\n");
        Files.writeString(server.resolve("eula.txt"), "eula=true\n");
        Files.createDirectories(server.resolve("world"));
        Files.writeString(server.resolve("world/level.dat"), "made up");
        Files.writeString(server.resolve("world/session.lock"), "");
        Files.createDirectories(server.resolve("logs"));
        Files.writeString(server.resolve("logs/latest.log"), "old log");
        for (String jar : List.of("MadeUpCore.jar", "BrokenThing-2.jar", "NeedsBroken.jar", "NeedsNeeds.jar",
                "FailsSmoke.jar", "WarnsOnly.jar", "SoftOnBroken.jar")) {
            pluginJar(plugins.resolve(jar), "plugin.yml", "name: " + jar.replace(".jar", "").replace("-2", "") + "\nversion: 1\n");
        }
        pluginJar(plugins.resolve("DiscordSRV-Build.jar"), "plugin.yml", "name: DiscordSRV\nversion: 1\n");
        pluginJar(plugins.resolve("Preflight-0.1.0.jar"), "paper-plugin.yml", "name: Preflight\nversion: '0.1.0'\n");
    }

    // ---- check

    @Test
    void checkCopiesBootsReadsAndDeletes() throws Exception {
        Files.write(server.resolve("fake-report.xml"), PlanTest.xml(PlanTest.sample()));
        Path work = Files.createDirectories(temp.resolve("work"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        Plan plan = new Check(options(work, null), new PrintStream(progress, true, StandardCharsets.UTF_8), "test").run();

        assertEquals(Plan.Status.SWITCH_OFF, plan.status(), plan.stop());
        assertEquals(List.of("BrokenThing", "FailsSmoke", "NeedsBroken", "NeedsNeeds"),
                plan.off().stream().map(Plan.Off::name).toList());
        assertEquals(ServerFolder.sha256(plugins.resolve("NeedsBroken.jar")),
                plan.off().stream().filter(o -> o.name().equals("NeedsBroken")).findFirst().orElseThrow()
                        .sha256().get("NeedsBroken.jar"), "hashes come from the real jars");
        assertEquals(List.of("DiscordSRV"), plan.notChecked().stream().map(Plan.NotChecked::name).toList());
        try (Stream<Path> left = Files.list(work)) {
            assertEquals(0, left.count(), "the copy is deleted");
        }
        assertTrue(Files.exists(plugins.resolve("DiscordSRV-Build.jar")), "the real server is untouched");
        assertTrue(Files.exists(plugins.resolve("Preflight-0.1.0.jar")));
        assertTrue(progress.toString(StandardCharsets.UTF_8).contains("Deleting the copy"));
    }

    @Test
    void givenCopyIsPreparedAndKept() throws Exception {
        Files.write(server.resolve("fake-report.xml"), PlanTest.xml(PlanTest.base()));
        Path copy = temp.resolve("their-copy");
        copyTree(server, copy);
        Plan plan = new Check(options(null, copy), quiet(), "test").run();
        assertEquals(Plan.Status.ALL_CLEAR, plan.status(), plan.stop());
        assertTrue(Files.isDirectory(copy), "a copy the caller made isn't deleted");

        Properties saw = new Properties();
        try (var reader = Files.newBufferedReader(copy.resolve("fake-paper-saw.properties"))) {
            saw.load(reader);
        }
        assertEquals("WARN", saw.getProperty("on-failure"));
        assertEquals("true", saw.getProperty("shutdown-after-run"));
        assertEquals("127.0.0.1", saw.getProperty("server-ip"));
        assertEquals("false", saw.getProperty("enable-rcon"));
        assertFalse(saw.getProperty("server-port").equals(ServerFolder.serverProperties(server).getProperty("server-port")),
                "the copy gets its own port");
        String seen = saw.getProperty("plugins");
        assertTrue(seen.contains("Preflight.jar") && !seen.contains("Preflight-0.1.0.jar"), "this release's Preflight replaces the old one: " + seen);
        assertTrue(seen.contains("DiscordSRV-Build.jar.disabled") && !seen.contains("DiscordSRV-Build.jar,"), "outward plugins are off: " + seen);
    }

    @Test
    void ownCopyLeavesOutLogsLocksAndState() throws Exception {
        Files.createDirectories(server.resolve(Switcher.STATE_DIR).resolve("restore-points"));
        Files.writeString(server.resolve(Switcher.STATE_DIR).resolve("restore-points/old.zip"), "made up");
        Files.createDirectories(server.resolve("backups"));
        Files.writeString(server.resolve("backups/big.zip"), "made up");
        Path work = Files.createDirectories(temp.resolve("work"));
        Check.Options base = options(work, null);
        Check.Options excluding = new Check.Options(base.server(), base.paperJar(), base.rulesFile(), base.workDir(),
                null, base.memory(), base.timeoutSeconds(), false, List.of("backups/"), base.java());
        Check check = new Check(excluding, quiet(), "test");
        Path copy = check.makeCopy(server.toRealPath(), "2026-11-08T00:00:00Z");
        try {
            assertTrue(Files.exists(copy.resolve("world/level.dat")));
            assertTrue(Files.exists(copy.resolve("plugins/MadeUpCore.jar")));
            assertTrue(Files.exists(copy.resolve(Check.MARKER)));
            assertFalse(Files.exists(copy.resolve("logs")), "no old logs, so Preflight reads only this boot's");
            assertFalse(Files.exists(copy.resolve("world/session.lock")));
            assertFalse(Files.exists(copy.resolve(Switcher.STATE_DIR)), "not this tool's own restore points");
            assertFalse(Files.exists(copy.resolve("backups")), "nor anything excluded");
            assertEquals(copy.getParent().toRealPath(), work.toRealPath());
        } finally {
            check.deleteCopy(copy);
        }
        assertFalse(Files.exists(copy));
    }

    @Test
    void aCopyThatHangsIsStoppedAndDeleted() throws Exception {
        Files.writeString(server.resolve("fake-hang"), "");
        Path work = Files.createDirectories(temp.resolve("work"));
        Check.Options base = options(work, null);
        Check.Options quick = new Check.Options(base.server(), base.paperJar(), base.rulesFile(), base.workDir(),
                null, base.memory(), 3, false, List.of(), base.java());
        Plan plan = new Check(quick, quiet(), "test").run();
        assertEquals(Plan.Status.STOP, plan.status());
        assertTrue(plan.stop().contains("didn't finish within 3 seconds"), plan.stop());
        try (Stream<Path> left = Files.list(work)) {
            assertEquals(0, left.count(), "even a copy that hung is deleted");
        }
    }

    @Test
    void checkStopsOnSharedDatabaseUnlessAllowed() throws Exception {
        Files.createDirectories(plugins.resolve("LuckPerms"));
        Files.writeString(plugins.resolve("LuckPerms/config.yml"), "storage-method: mysql\n");
        Files.write(server.resolve("fake-report.xml"), PlanTest.xml(PlanTest.base()));
        Path work = Files.createDirectories(temp.resolve("work"));
        Plan stopped = new Check(options(work, null), quiet(), "test").run();
        assertEquals(Plan.Status.STOP, stopped.status());
        assertTrue(stopped.stop().contains("LuckPerms") && stopped.stop().contains("--allow-shared-database"), stopped.stop());
        try (Stream<Path> left = Files.list(work)) {
            assertEquals(0, left.count(), "and makes no copy");
        }
        Check.Options base = options(work, null);
        Check.Options allowed = new Check.Options(base.server(), base.paperJar(), base.rulesFile(), base.workDir(),
                null, base.memory(), base.timeoutSeconds(), true, List.of(), base.java());
        Plan plan = new Check(allowed, quiet(), "test").run();
        assertEquals(Plan.Status.ALL_CLEAR, plan.status(), plan.stop());
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("LuckPerms")));
    }

    @Test
    void checkStopsWithoutTheEulaOrWithBrokenRules() throws Exception {
        Files.writeString(server.resolve("eula.txt"), "eula=false\n");
        Plan noEula = new Check(options(temp, null), quiet(), "test").run();
        assertTrue(noEula.stop().contains("eula=true"), noEula.stop());

        Files.writeString(server.resolve("eula.txt"), "eula=true\n");
        Path rules = Files.createDirectories(server.resolve(Switcher.STATE_DIR)).resolve("rules.json");
        Files.writeString(rules, "{ broken");
        Plan broken = new Check(options(temp, null), quiet(), "test").run();
        assertTrue(broken.stop().contains("couldn't be read"), "an unreadable rules file is an error, not 'no rules'");
    }

    @Test
    void reportlessBootSaysSo() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        Plan plan = new Check(options(work, null), quiet(), "test").run();
        assertEquals(Plan.Status.STOP, plan.status());
        assertTrue(plan.stop().contains("without a Preflight report"), plan.stop());
        assertTrue(plan.stop().contains("startup tests passed"), "with the copy's last lines: " + plan.stop());
    }

    @Test
    void deleteRefusesAFolderWithoutTheMarker() throws Exception {
        new Check(options(temp, null), quiet(), "test").deleteCopy(server);
        assertTrue(Files.exists(plugins.resolve("MadeUpCore.jar")), "a real server folder is never deleted");
    }

    @Test
    void refusesAJarThatIsntPaperOrIsTooOld() throws Exception {
        Path notPaper = temp.resolve("not-paper.jar");
        pluginJar(notPaper, "plugin.yml", "name: Nope\n");
        Check.Options base = options(temp, null);
        Plan wrong = new Check(new Check.Options(base.server(), notPaper, base.rulesFile(), base.workDir(), null,
                base.memory(), base.timeoutSeconds(), false, List.of(), base.java()), quiet(), "test").run();
        assertEquals(Plan.Status.STOP, wrong.status());
        assertTrue(wrong.stop().contains("isn't a Paper server jar"), wrong.stop());
        Plan old = new Check(new Check.Options(base.server(), fakePaperJar("1.21.11"), base.rulesFile(), base.workDir(),
                null, base.memory(), base.timeoutSeconds(), false, List.of(), base.java()), quiet(), "test").run();
        assertTrue(old.stop().contains("26.1 or newer"), old.stop());
        assertEquals("1.21.11", old.target().minecraft());
        assertTrue(ServerFolder.supportedVersion("26.1") && ServerFolder.supportedVersion("26.3.1")
                && ServerFolder.supportedVersion("27.0") && !ServerFolder.supportedVersion("1.21.11")
                && !ServerFolder.supportedVersion("26.0") && !ServerFolder.supportedVersion("weird"));
    }

    @Test
    void aGivenCopyIsCleanedOfTheRealServersLastRun() throws Exception {
        Files.write(server.resolve("fake-report.xml"), PlanTest.xml(PlanTest.base()));
        Path copy = temp.resolve("their-copy");
        copyTree(server, copy);
        Files.createDirectories(copy.resolve("plugins/Preflight"));
        Files.writeString(copy.resolve(Report.FILE), "an old report that must not be read");
        Plan plan = new Check(options(null, copy), quiet(), "test").run();
        assertEquals(Plan.Status.ALL_CLEAR, plan.status(), plan.stop());
        Properties saw = new Properties();
        try (var reader = Files.newBufferedReader(copy.resolve("fake-paper-saw.properties"))) {
            saw.load(reader);
        }
        assertEquals("false", saw.getProperty("has-logs"), "old logs gone before boot");
        assertEquals("false", saw.getProperty("has-session-lock"), "locks gone before boot");
    }

    @Test
    void leftoverCopiesAreListedNotDeleted() throws Exception {
        Files.write(server.resolve("fake-report.xml"), PlanTest.xml(PlanTest.base()));
        Path work = Files.createDirectories(temp.resolve("work"));
        Path stale = Files.createDirectories(work.resolve("made-up-server.preflight-check-20260101-000000"));
        Files.writeString(stale.resolve(Check.MARKER), "");
        Path unrelated = Files.createDirectories(work.resolve("made-up-server.preflight-check-not-ours"));
        Plan plan = new Check(options(work, null), quiet(), "test").run();
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains(stale.getFileName().toString())), plan.warnings().toString());
        assertTrue(plan.warnings().stream().noneMatch(w -> w.contains("not-ours")), "only folders with the marker");
        assertTrue(Files.exists(stale) && Files.exists(unrelated), "listed, not deleted: another check may be using one");
    }

    // ---- apply, restore, status

    @Test
    void applyThenRestoreOneByOne() throws Exception {
        Plan plan = realPlan();
        Switcher switcher = new Switcher(server.toRealPath());

        Plan withGhost = new Plan(plan.tool(), plan.server(), plan.checkedAt(), plan.target(), null,
                Stream.concat(plan.off().stream(), Stream.of(new Plan.Off("Ghost", List.of("Ghost.jar"),
                        Map.of("Ghost.jar", "x"), List.of("made up")))).toList(),
                List.of(), List.of(), List.of(), List.of(), null);
        Switcher.Refused ghost = assertThrows(Switcher.Refused.class, () -> switcher.apply(withGhost));
        assertTrue(ghost.getMessage().contains("Ghost.jar"), "a file that isn't there stops everything before anything moves");
        assertTrue(Files.exists(plugins.resolve("BrokenThing-2.jar")));
        assertTrue(switcher.restorePoints().isEmpty(), "and no restore point is made for nothing");

        Path point = switcher.apply(plan);
        for (String name : List.of("BrokenThing-2.jar", "NeedsBroken.jar", "NeedsNeeds.jar", "FailsSmoke.jar")) {
            assertTrue(Files.exists(plugins.resolve(name + ".disabled")) && !Files.exists(plugins.resolve(name)), name + " is switched off");
        }
        assertTrue(Files.exists(plugins.resolve("WarnsOnly.jar")) && Files.exists(plugins.resolve("SoftOnBroken.jar")), "and nothing else");
        try (ZipFile zip = new ZipFile(point.toFile())) {
            assertTrue(zip.getEntry("plugins/BrokenThing-2.jar") != null && zip.getEntry("plugins/WarnsOnly.jar") != null,
                    "the restore point has every jar");
            assertTrue(zip.getEntry("README.txt") != null);
        }
        assertEquals(List.of("BrokenThing", "FailsSmoke", "NeedsBroken", "NeedsNeeds"),
                switcher.switchedOff().stream().map(Switcher.Entry::name).sorted().toList(), "they're remembered, to offer back");

        List<Switcher.Restored> back = switcher.restore(List.of("brokenthing", "NeedsBroken"));
        assertTrue(back.stream().allMatch(Switcher.Restored::done));
        assertTrue(Files.exists(plugins.resolve("BrokenThing-2.jar")) && Files.exists(plugins.resolve("NeedsBroken.jar")), "chosen ones go back on, matched ignoring case");
        assertTrue(Files.exists(plugins.resolve("NeedsNeeds.jar.disabled")), "and only those");
        assertEquals(List.of("FailsSmoke", "NeedsNeeds"), switcher.switchedOff().stream().map(Switcher.Entry::name).sorted().toList());

        Files.writeString(plugins.resolve("FailsSmoke.jar"), "a newer one");
        List<Switcher.Restored> kept = switcher.restore(List.of("FailsSmoke"));
        assertFalse(kept.getFirst().done());
        assertTrue(kept.getFirst().note().contains("already"), kept.getFirst().note());
        assertEquals("a newer one", Files.readString(plugins.resolve("FailsSmoke.jar")), "a newer jar in its place isn't overwritten");
        assertTrue(Files.exists(plugins.resolve("FailsSmoke.jar.disabled")), "and the old one isn't deleted");

        assertThrows(Switcher.Refused.class, () -> switcher.restore(List.of("NeverOff")), "only plugins this tool switched off");
        switcher.restore(List.of("NeedsNeeds"));
        assertTrue(Files.exists(plugins.resolve("NeedsNeeds.jar")), "the last one goes back on too");
    }

    @Test
    void applyRefusesAChangedJarAnotherServerOrAnUnfitPlan() throws Exception {
        Plan plan = realPlan();
        Switcher switcher = new Switcher(server.toRealPath());
        Files.writeString(plugins.resolve("NeedsBroken.jar"), "replaced since the check");
        Switcher.Refused changed = assertThrows(Switcher.Refused.class, () -> switcher.apply(plan));
        assertTrue(changed.getMessage().contains("changed since the check"), changed.getMessage());
        assertTrue(Files.exists(plugins.resolve("BrokenThing-2.jar")), "nothing moved");

        Path other = Files.createDirectories(temp.resolve("other-server/plugins")).getParent();
        assertThrows(Switcher.Refused.class, () -> new Switcher(other.toRealPath()).checkPlan(plan), "a plan for another server");

        Plan stop = new Plan("t", server.toString(), "x", plan.target(), "made up", List.of(), List.of(), List.of(), List.of(), List.of(), null);
        assertThrows(Switcher.Refused.class, () -> switcher.checkPlan(stop));
        Plan wait = new Plan("t", server.toString(), "x", plan.target(), null, plan.off(),
                List.of(new Plan.Blocked("Keep", "made up", List.of())), List.of(), List.of(), List.of(), null);
        assertThrows(Switcher.Refused.class, () -> switcher.checkPlan(wait));
    }

    @Test
    void applyNeverOverwritesADisabledJar() throws Exception {
        Plan plan = realPlan();
        Files.writeString(plugins.resolve("NeedsBroken.jar.disabled"), "an older one, switched off by hand");
        Switcher.Refused refused = assertThrows(Switcher.Refused.class, () -> new Switcher(server.toRealPath()).apply(plan));
        assertTrue(refused.getMessage().contains("never overwrites"), refused.getMessage());
    }

    @Test
    void refusesWhileTheServerRuns() throws Exception {
        Plan plan = realPlan();
        Switcher switcher = new Switcher(server.toRealPath());
        int port = Integer.parseInt(ServerFolder.serverProperties(server).getProperty("server-port"));
        try (ServerSocket listening = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            Switcher.Refused byPort = assertThrows(Switcher.Refused.class, () -> switcher.apply(plan));
            assertTrue(byPort.getMessage().contains("answering on"), byPort.getMessage());
        }
        try (FileChannel channel = FileChannel.open(server.resolve("world/session.lock"), StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            Switcher.Refused byLock = assertThrows(Switcher.Refused.class, () -> switcher.restore(List.of()));
            assertTrue(byLock.getMessage().contains("world"), byLock.getMessage());
        }
        assertFalse(ServerFolder.liveness(server).running(), "stopped again afterwards");
        assertTrue(Files.exists(plugins.resolve("BrokenThing-2.jar")), "nothing moved while it ran");
        assertFalse(Files.exists(plugins.resolve("Preflight/upgrade/.lock")), "a refusal changes nothing, not even a lock file");
    }

    @Test
    void commandLineAsksForYesAndUsesExitCodes() throws Exception {
        Plan plan = realPlan();
        Path planFile = temp.resolve("plan.json");
        Files.writeString(planFile, Json.write(plan.toJson()));
        String[] apply = {"apply", "--server", server.toString(), "--plan", planFile.toString()};

        Result no = run(apply, "no\n");
        assertEquals(Main.REFUSED, no.code);
        assertTrue(no.out.contains("Nothing was changed"));
        assertTrue(Files.exists(plugins.resolve("BrokenThing-2.jar")), "no means no");

        Result yes = run(apply, "yes\n");
        assertEquals(Main.OK, yes.code, yes.err);
        assertTrue(yes.out.contains("Restore point:"), yes.out);
        assertTrue(Files.exists(plugins.resolve("BrokenThing-2.jar.disabled")));

        Result status = run(new String[] {"status", "--server", server.toString(), "--json"}, "");
        Map<String, Object> root = Json.object(Json.parse(status.out), "status");
        assertEquals(Boolean.FALSE, root.get("running"));
        assertEquals(4, ((List<?>) root.get("switchedOff")).size());
        assertEquals(1, ((List<?>) root.get("restorePoints")).size());

        Result all = run(new String[] {"restore", "--server", server.toString(), "--all", "--yes"}, "");
        assertEquals(Main.OK, all.code, all.err);
        assertTrue(Files.exists(plugins.resolve("BrokenThing-2.jar")) && Files.exists(plugins.resolve("NeedsNeeds.jar")));

        assertEquals(Main.USAGE, run(new String[] {"restore", "--server", server.toString()}, "").code, "needs --plugin or --all");
        assertEquals(Main.USAGE, run(new String[] {"apply", "--server", server.toString(), "--plan", planFile.toString(), "--json"}, "").code,
                "an option a command doesn't take is an error");
        assertEquals(Main.USAGE, run(new String[] {"frobnicate"}, "").code);
    }

    // ---- other pieces

    @Test
    void sharedDatabaseDetection() {
        assertTrue(ServerFolder.usesOutsideDatabase("storage-method: MySQL\n"));
        assertTrue(ServerFolder.usesOutsideDatabase("database:\n  type: 'mariadb' # here\n"));
        assertFalse(ServerFolder.usesOutsideDatabase("storage-method: h2\n"));
        assertFalse(ServerFolder.usesOutsideDatabase("sql:\n  enabled: false\n  type: mysql\n"),
                "AuraSkills names a database it doesn't use");
        assertTrue(ServerFolder.usesOutsideDatabase("sql:\n  enabled: true\n  type: mysql\n"));
    }

    @Test
    void pluginNamesFromJars() throws Exception {
        Path jar = temp.resolve("x.jar");
        pluginJar(jar, "plugin.yml", "# comment\nname: 'Some Plugin' # trailing\nversion: 1\n");
        assertEquals("Some_Plugin", ServerFolder.pluginName(jar));
        pluginJar(jar, "paper-plugin.yml", "name: \"Paperish\"\n");
        assertEquals("Paperish", ServerFolder.pluginName(jar));
        Files.writeString(jar, "not a zip");
        assertEquals(null, ServerFolder.pluginName(jar));
    }

    @Test
    void jsonReadsAndWritesWhatWeNeed() throws Exception {
        Object parsed = Json.parse("{\"a\": [1, -2.5e1, true, null, \"x\\u00e9\\n\"], \"b\": {}}");
        assertEquals("{\n  \"a\": [1, -25.0, true, null, \"xé\\n\"],\n  \"b\": {}\n}\n", Json.write(parsed));
        for (String bad : List.of("{", "{\"a\":1,}", "[1 2]", "{\"a\":1,\"a\":2}", "\"\u0001\"", "01", "tru", "{} x")) {
            assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
        }
        assertThrows(Json.ParseException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)), "too deep");
    }

    // ---- helpers

    record Result(int code, String out, String err) {}

    private static Result run(String[] args, String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** A plan as check would make it for this server, without booting anything. */
    private Plan realPlan() throws IOException {
        return Plan.make(Report.parse(PlanTest.xml(PlanTest.sample())), null, new Plan.Context("test",
                server.toRealPath().toString(), "2026-11-08T00:00:00Z", "paper.jar", ServerFolder.jarHashes(plugins),
                Map.of(), List.of()));
    }

    private Check.Options options(Path work, Path givenCopy) throws IOException {
        return new Check.Options(server.toRealPath(), fakePaperJar(),
                server.resolve(Switcher.STATE_DIR).resolve("rules.json"), work, givenCopy, "256M", 60, false,
                List.of(), ProcessHandle.current().info().command().orElse("java"));
    }

    /** Packs FakePaper into a runnable jar shaped like Paper's paperclip jar, for the given version. */
    private Path fakePaperJar(String version) throws IOException {
        Path jar = temp.resolve("paper-made-up-" + version + ".jar");
        if (Files.exists(jar)) {
            return jar;
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, io.papermc.paperclip.Main.class.getName());
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            for (Class<?> type : List.of(io.papermc.paperclip.Main.class, FakePaper.class)) {
                String path = type.getName().replace('.', '/') + ".class";
                try (InputStream in = type.getClassLoader().getResourceAsStream(path)) {
                    out.putNextEntry(new JarEntry(path));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
            out.putNextEntry(new JarEntry("META-INF/versions.list"));
            out.write(("made-up-hash\t" + version + "\t" + version + "/paper-" + version + ".jar\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private Path fakePaperJar() throws IOException {
        return fakePaperJar("26.3");
    }

    private static void pluginJar(Path jar, String descriptor, String yaml) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(descriptor));
            zip.write(yaml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private static PrintStream quiet() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
