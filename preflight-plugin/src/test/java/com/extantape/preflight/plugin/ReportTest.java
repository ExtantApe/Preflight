package com.extantape.preflight.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.extantape.preflight.api.Severity;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

class ReportTest {
    private static final Instant FINISHED = Instant.parse("2026-11-08T12:34:56.789Z");
    private static final ReportProperties.Server SERVER =
            new ReportProperties.Server("Paper", "26.1.2-74-abc (MC: 26.1.2)", "26.1.2", 74, "0.2.0");

    // Lines copied from a real Paper 26.1.2 boot with deliberately broken plugins.
    private static final List<String> LOG = List.of(
            "[07:49:59] [Server thread/ERROR]: [ModernPluginLoadingStrategy] Could not load 'plugins/MissingDep.jar' in 'plugins'",
            "org.bukkit.plugin.UnknownDependencyException: Unknown/missing dependency plugins: [NotInstalled]. Please download and install these plugins to run 'MissingDep'.",
            "\tat io.papermc.paper.plugin.entrypoint.strategy.modern.ModernPluginLoadingStrategy.loadProviders(ModernPluginLoadingStrategy.java:91) ~[paper.jar:?]",
            "[07:49:59] [Server thread/ERROR]: [ModernPluginLoadingStrategy] Could not load plugin 'BadApi.jar' in folder 'plugins'",
            "org.bukkit.plugin.InvalidPluginException: Unsupported API version 99.0",
            "[07:50:02] [Server thread/INFO]: [BrokenEnable] Enabling BrokenEnable v1",
            "[07:50:02] [Server thread/ERROR]: Error occurred while enabling BrokenEnable v1 (Is it up to date?)",
            "java.lang.IllegalStateException: deliberately broken",
            "\tat BrokenEnable.jar//fx.BrokenEnable.onEnable(BrokenEnable.java:1) ~[?:?]");

    @TempDir
    Path folder;

    // ---- descriptors

    @Test
    void readsPaperAndBukkitDescriptorsFromJars() throws Exception {
        jar("Paper.jar", Map.of("paper-plugin.yml", """
                name: PaperOne
                version: '2.0'
                main: x.Y
                dependencies:
                  server:
                    Needed: {load: BEFORE}
                    Optional: {required: false}
                """));
        jar("Old.jar", Map.of("plugin.yml", """
                name: Old Name
                version: 1
                main: x.Z
                depend: Single
                softdepend: [A, B]
                """));
        jar("Both.jar", Map.of("plugin.yml", "name: FromBukkit\nversion: 1\nmain: a.B\n",
                "paper-plugin.yml", "name: FromPaper\nversion: 1\nmain: a.B\n"));
        jar("Library.jar", Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));
        Files.writeString(folder.resolve("Corrupt.jar"), "not a zip");
        Files.writeString(folder.resolve("notes.txt"), "ignored");

        jar("Nameless.jar", Map.of("plugin.yml", "version: 1\nmain: a.B\n"));
        jar("BadYaml.jar", Map.of("plugin.yml", "name: [unclosed\n"));
        List<Path> errors = new ArrayList<>();
        PluginDescriptor.Scan scan = PluginDescriptor.scan(folder, (path, e) -> errors.add(path));
        List<PluginDescriptor> found = scan.plugins();

        assertEquals(List.of("BadYaml.jar", "Corrupt.jar", "Nameless.jar"), scan.unreadable());
        assertEquals(List.of("Library.jar"), scan.other());
        assertEquals(3, errors.size(), "each unreadable jar is logged");
        assertEquals(List.of("Both.jar", "Old.jar", "Paper.jar"), found.stream().map(PluginDescriptor::file).toList());
        PluginDescriptor both = found.get(0);
        assertEquals("FromPaper", both.name(), "Paper prefers paper-plugin.yml");
        PluginDescriptor old = found.get(1);
        assertEquals("Old_Name", old.name(), "Bukkit turns spaces into underscores");
        assertEquals("1", old.version());
        assertEquals("plugin.yml", old.descriptor());
        assertEquals(List.of("Single"), old.depends());
        assertEquals(List.of("A", "B"), old.softDepends());
        PluginDescriptor paper = found.get(2);
        assertEquals("paper-plugin.yml", paper.descriptor());
        assertEquals(List.of("Needed"), paper.depends(), "required defaults to true");
        assertEquals(List.of("Optional"), paper.softDepends());
    }

    @Test
    void missingFolderScansAsEmpty() {
        assertEquals(PluginDescriptor.Scan.EMPTY, PluginDescriptor.scan(folder.resolve("absent"), (path, e) -> {}));
    }

    // ---- inventory

    @Test
    void inventoryAddsUnloadedJarsAndFlagsMissingDependencies() {
        List<PluginInventory.Status> plugins = PluginInventory.build(sampleJars(), sampleLoaded());

        assertEquals(List.of("BadApi", "BrokenEnable", "FailingTests", "MissingDep", "NeedsBadApi",
                "PaperDependsOnFailing", "Preflight"), plugins.stream().map(PluginInventory.Status::name).toList());

        PluginInventory.Status badApi = find(plugins, "BadApi");
        assertEquals(PluginInventory.State.NOT_LOADED, badApi.state());
        assertEquals(List.of("BadApi.jar"), badApi.files());
        assertEquals(List.of("NeedsBadApi"), badApi.requiredBy());

        // Paper enabled this even though BadApi never loaded; the report has to say so.
        PluginInventory.Status needs = find(plugins, "NeedsBadApi");
        assertEquals(PluginInventory.State.ENABLED, needs.state());
        assertEquals(List.of("BadApi"), needs.missingDepends());
        assertTrue(PluginInventory.broken(needs));

        assertEquals(List.of("NotInstalled"), find(plugins, "MissingDep").missingDepends());
        assertEquals(PluginInventory.State.DISABLED, find(plugins, "BrokenEnable").state());
        assertEquals(List.of("PaperDependsOnFailing"), find(plugins, "FailingTests").requiredBy());
        assertFalse(PluginInventory.broken(find(plugins, "FailingTests")));
        assertEquals("paper-plugin.yml", find(plugins, "Preflight").descriptor());
    }

    @Test
    void requiredByIsTransitiveAndDuplicateJarsAreBothListed() {
        List<PluginDescriptor> jars = List.of(
                descriptor("Base", "Base-1.jar"), descriptor("Base", "Base-2.jar"),
                descriptor("Middle", "Middle.jar", "Base"), descriptor("Top", "Top.jar", "Middle"));
        List<PluginInventory.Loaded> loaded = List.of(
                loaded("Base", true), loaded("Middle", true, "Base"), loaded("Top", true, "Middle"));
        List<PluginInventory.Status> plugins = PluginInventory.build(jars, loaded);
        assertEquals(List.of("Base-1.jar", "Base-2.jar"), find(plugins, "Base").files());
        assertEquals(List.of("Middle", "Top"), find(plugins, "Base").requiredBy());
        assertEquals(List.of(), find(plugins, "Top").requiredBy());
    }

    @Test
    void dependencyCycleAmongUnloadedJarsDoesNotOverflow() {
        List<PluginDescriptor> jars = List.of(descriptor("A", "A.jar", "B"), descriptor("B", "B.jar", "A"),
                descriptor("C", "C.jar", "A"));
        List<PluginInventory.Status> plugins = PluginInventory.build(jars, List.of());
        assertEquals(List.of("B", "C"), find(plugins, "A").requiredBy());
        assertEquals(List.of("A", "C"), find(plugins, "B").requiredBy());
        assertEquals(List.of("A", "B", "C"), DisablePlan.order(List.of("A"), Map.of(
                "A", List.of("B"), "B", List.of("A"), "C", List.of("A"))).stream().sorted().toList());
    }

    // ---- reasons from the log

    @Test
    void findsPaperReasonsAndLeavesOthersUnknown() {
        List<PluginInventory.Status> plugins = PluginInventory.build(sampleJars(), sampleLoaded());
        assertEquals(Optional.of("Could not load plugin 'BadApi.jar' in folder 'plugins': "
                + "org.bukkit.plugin.InvalidPluginException: Unsupported API version 99.0"),
                LogReasons.find(LOG, find(plugins, "BadApi")));
        assertTrue(LogReasons.find(LOG, find(plugins, "MissingDep")).orElseThrow()
                .startsWith("Could not load 'plugins/MissingDep.jar' in 'plugins': "
                        + "org.bukkit.plugin.UnknownDependencyException: Unknown/missing dependency plugins: [NotInstalled]"));
        assertEquals(Optional.of("Error occurred while enabling BrokenEnable v1 (Is it up to date?): "
                + "java.lang.IllegalStateException: deliberately broken"),
                LogReasons.find(LOG, find(plugins, "BrokenEnable")));
        assertEquals(Optional.empty(), LogReasons.find(LOG, find(plugins, "NeedsBadApi")), "enabled: no reason");
        PluginInventory.Status quiet = new PluginInventory.Status("Quiet", "1", List.of("Quiet.jar"),
                "plugin.yml", PluginInventory.State.NOT_LOADED, List.of(), List.of(), List.of(), List.of());
        assertEquals(Optional.empty(), LogReasons.find(LOG, quiet), "not in the log: unknown, not guessed");
    }

    @Test
    void oneLineKeepsTheFirstLineAndCapsLength() {
        assertEquals("first", LogReasons.oneLine("first\nsecond"));
        assertEquals(LogReasons.MAX_LENGTH, LogReasons.oneLine("x".repeat(1000)).length());
        assertEquals("", LogReasons.oneLine(null));
    }

    // ---- the whole report

    @Test
    void fullReportIsValidJUnitAndCarriesPluginDetail() throws Exception {
        List<PluginInventory.Status> plugins = PluginInventory.build(sampleJars(), sampleLoaded());
        Map<String, String> reasons = new LinkedHashMap<>();
        for (PluginInventory.Status plugin : plugins) {
            LogReasons.find(LOG, plugin).ifPresent(reason -> reasons.put(plugin.name(), reason));
        }
        List<TestResult> results = List.of(
                result("Preflight", "all-plugins-enabled", Severity.CRITICAL, TestResult.Status.FAILED,
                        "AssertionError: Plugins failed to enable: [BrokenEnable]"),
                result("Preflight", "plugins-loaded", Severity.WARNING, TestResult.Status.FAILED,
                        "AssertionError: BadApi (BadApi.jar) didn't load\nsecond line"),
                result("Preflight", "worlds-loaded", Severity.CRITICAL, TestResult.Status.PASSED, null),
                result("FailingTests", "always-fails", Severity.CRITICAL, TestResult.Status.TIMED_OUT,
                        "Timed out after 100 ticks"),
                TestResult.skipped(test("Preflight", "entity-spawn", Severity.WARNING)));

        PluginInventory.Snapshot snapshot = new PluginInventory.Snapshot(plugins, List.of("Corrupt.jar"),
                List.of("some-library.jar"));
        Map<String, String> properties = ReportProperties.build(results, snapshot, reasons, SERVER, "WARN", FINISHED);
        String xml = JUnitReport.render(results, properties, FINISHED);
        validate(xml);
        Map<String, String> parsed = parseProperties(xml);
        assertEquals(properties, parsed, "every property survives XML round trip, in order");

        assertEquals("1", parsed.get("preflight.report.format"));
        assertEquals("0.2.0", parsed.get("preflight.version"));
        assertEquals("2026-11-08T12:34:56Z", parsed.get("preflight.finished-at"));
        assertEquals("passed", parsed.get("preflight.server-checks"), "plugin-pointing checks don't count");
        assertEquals("74", parsed.get("server.build"));
        assertEquals("WARN", parsed.get("preflight.on-failure"));
        assertEquals("Corrupt.jar", parsed.get("preflight.unreadable-jars"));
        assertEquals("some-library.jar", parsed.get("preflight.other-jars"));
        assertEquals("26.1.2", parsed.get("server.minecraft-version"));

        assertEquals("not-loaded", parsed.get("plugin.BadApi.state"));
        assertEquals("BadApi.jar", parsed.get("plugin.BadApi.files"));
        assertEquals("NeedsBadApi", parsed.get("plugin.BadApi.required-by"));
        assertEquals("Preflight:plugins-loaded", parsed.get("plugin.BadApi.failed-tests"));
        assertTrue(parsed.get("plugin.BadApi.reason").contains("Unsupported API version 99.0"));
        assertEquals("enabled", parsed.get("plugin.NeedsBadApi.state"));
        assertEquals("BadApi", parsed.get("plugin.NeedsBadApi.missing-depends"));
        assertEquals("Preflight:plugins-loaded", parsed.get("plugin.NeedsBadApi.failed-tests"));
        assertFalse(parsed.containsKey("plugin.NeedsBadApi.reason"));
        assertEquals("disabled", parsed.get("plugin.BrokenEnable.state"));
        assertEquals("Preflight:all-plugins-enabled", parsed.get("plugin.BrokenEnable.failed-tests"));
        assertEquals("FailingTests:always-fails", parsed.get("plugin.FailingTests.failed-tests"));
        assertEquals("", parsed.get("plugin.Preflight.failed-tests"), "Preflight's own checks aren't its fault");

        assertEquals("timed-out", parsed.get("test.FailingTests:always-fails.result"));
        assertEquals("critical", parsed.get("test.FailingTests:always-fails.severity"));
        assertEquals("AssertionError: BadApi (BadApi.jar) didn't load", parsed.get("test.Preflight:plugins-loaded.message"));
        assertEquals("skipped", parsed.get("test.Preflight:entity-spawn.result"));
        assertFalse(parsed.containsKey("test.Preflight:worlds-loaded.message"));

        assertTrue(xml.contains("tests=\"5\" failures=\"2\" errors=\"0\" skipped=\"1\""), xml);
        assertTrue(xml.contains("timestamp=\"2026-11-08T12:34:56\""), xml);
        assertTrue(xml.contains("<skipped message=\"Skipped by skip-tests\"/>"), xml);
    }

    @Test
    void criticalServerCheckFailureIsReported() {
        List<TestResult> results = List.of(result("Preflight", "chunk-load", Severity.CRITICAL,
                TestResult.Status.FAILED, "no chunk"));
        assertEquals("failed", ReportProperties.build(results, empty(), Map.of(), SERVER, "LOCKDOWN", FINISHED)
                .get("preflight.server-checks"));
        List<TestResult> warningOnly = List.of(result("Preflight", "block-gravity", Severity.WARNING,
                TestResult.Status.FAILED, "sand floated"));
        assertEquals("passed", ReportProperties.build(warningOnly, empty(), Map.of(), SERVER, "LOCKDOWN", FINISHED)
                .get("preflight.server-checks"));
    }

    @Test
    void timestampKeepsZeroSeconds() throws Exception {
        Instant onTheMinute = Instant.parse("2026-11-08T15:01:00.004Z");
        Map<String, String> properties = ReportProperties.build(List.of(), empty(), Map.of(), SERVER, "WARN",
                onTheMinute);
        String xml = JUnitReport.render(List.of(), properties, onTheMinute);
        validate(xml);
        assertTrue(xml.contains("timestamp=\"2026-11-08T15:01:00\""), xml);
        assertEquals("2026-11-08T15:01:00Z", parseProperties(xml).get("preflight.finished-at"));
    }

    @Test
    void reportWithoutPropertiesStillMatchesTheOldShape() throws Exception {
        String xml = JUnitReport.render(List.of(
                result("Owner", "a", Severity.CRITICAL, TestResult.Status.PASSED, null)));
        validate(xml);
        assertFalse(xml.contains("<properties>"));
        assertTrue(xml.contains("failures=\"0\""), "the README's grep still works");
    }

    @Test
    void awkwardPluginNamesAndValuesStayValid() throws Exception {
        PluginInventory.Status odd = new PluginInventory.Status("Odd.Name-1", "1 <beta> & \"x\"",
                List.of("odd 'jar'.jar"), "plugin.yml", PluginInventory.State.NOT_LOADED,
                List.of(), List.of(), List.of(), List.of());
        Map<String, String> properties = ReportProperties.build(List.of(),
                new PluginInventory.Snapshot(List.of(odd), List.of(), List.of()),
                Map.of("Odd.Name-1", "bad \u0001control"), SERVER, "LOCKDOWN", FINISHED);
        String xml = JUnitReport.render(List.of(), properties, FINISHED);
        validate(xml);
        Map<String, String> parsed = parseProperties(xml);
        assertEquals("1 <beta> & \"x\"", parsed.get("plugin.Odd.Name-1.version"));
        assertEquals("bad control", parsed.get("plugin.Odd.Name-1.reason"));
    }

    // ---- helpers

    private static PluginInventory.Snapshot empty() {
        return new PluginInventory.Snapshot(List.of(), List.of(), List.of());
    }

    private static List<PluginDescriptor> sampleJars() {
        return List.of(
                descriptor("BadApi", "BadApi.jar"),
                descriptor("BrokenEnable", "BrokenEnable.jar"),
                descriptor("FailingTests", "FailingTests.jar"),
                descriptor("MissingDep", "MissingDep.jar", "NotInstalled"),
                descriptor("NeedsBadApi", "NeedsBadApi.jar", "BadApi"),
                new PluginDescriptor("PaperDependsOnFailing", "1", "PaperDependsOnFailing.jar", "paper-plugin.yml",
                        List.of("FailingTests"), List.of()),
                new PluginDescriptor("Preflight", "0.2.0", "Preflight-0.2.0.jar", "paper-plugin.yml",
                        List.of(), List.of()));
    }

    private static List<PluginInventory.Loaded> sampleLoaded() {
        return List.of(
                loaded("BrokenEnable", false),
                loaded("FailingTests", true),
                loaded("NeedsBadApi", true, "BadApi"),
                new PluginInventory.Loaded("PaperDependsOnFailing", "1", true, List.of("FailingTests"), List.of(),
                        "paper-plugin.yml"),
                new PluginInventory.Loaded("Preflight", "0.2.0", true, List.of(), List.of(), "paper-plugin.yml"));
    }

    private static PluginDescriptor descriptor(String name, String file, String... depends) {
        return new PluginDescriptor(name, "1", file, "plugin.yml", List.of(depends), List.of());
    }

    private static PluginInventory.Loaded loaded(String name, boolean enabled, String... depends) {
        return new PluginInventory.Loaded(name, "1", enabled, List.of(depends), List.of(), "plugin.yml");
    }

    private static PluginInventory.Status find(List<PluginInventory.Status> plugins, String name) {
        return plugins.stream().filter(plugin -> plugin.name().equals(name)).findFirst().orElseThrow();
    }

    private static RegisteredTest test(String owner, String name, Severity severity) {
        return new RegisteredTest(owner, null, name, severity, 100, context -> {});
    }

    private static TestResult result(String owner, String name, Severity severity, TestResult.Status status,
            String message) {
        return new TestResult(test(owner, name, severity), status, message, null, 12);
    }

    private void jar(String name, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(folder.resolve(name));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void validate(String xml) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.newSchema(ReportTest.class.getResource("/junit.xsd"))
                .newValidator().validate(new StreamSource(new StringReader(xml)));
    }

    private static Map<String, String> parseProperties(String xml) throws Exception {
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml))).getElementsByTagName("property");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element property = (Element) nodes.item(i);
            out.put(property.getAttribute("name"), property.getAttribute("value"));
        }
        return out;
    }
}
