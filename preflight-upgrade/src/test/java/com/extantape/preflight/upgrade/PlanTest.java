package com.extantape.preflight.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Ported from Leaf Juice's plugin-upgrade checks (commits 3db8d41 and 7fdb6d1), plus what's new here. */
class PlanTest {
    private static final List<String> JARS = List.of("MadeUpCore.jar", "BrokenThing-2.jar", "NeedsBroken.jar",
            "NeedsNeeds.jar", "FailsSmoke.jar", "WarnsOnly.jar", "SoftOnBroken.jar");

    static Map<String, String> base() {
        Map<String, String> props = new TreeMap<>();
        props.put("preflight.report.format", "1");
        props.put("preflight.finished-at", "2026-11-08T00:00:00Z");
        props.put("preflight.server-checks", "passed");
        props.put("preflight.on-failure", "WARN");
        props.put("server.minecraft-version", "26.3");
        props.put("server.brand", "Paper");
        props.put("server.build", "244");
        return props;
    }

    static Map<String, String> sample() {
        Map<String, String> props = base();
        plugin(props, "Made.Up.Core", Map.of("state", "enabled", "version", "1.0", "files", "MadeUpCore.jar"));
        plugin(props, "BrokenThing", Map.of("state", "not-loaded", "files", "BrokenThing-2.jar", "reason", "Unsupported API version 99.0"));
        plugin(props, "NeedsBroken", Map.of("state", "enabled", "files", "NeedsBroken.jar", "depends", "BrokenThing", "missing-depends", "BrokenThing"));
        plugin(props, "NeedsNeeds", Map.of("state", "enabled", "files", "NeedsNeeds.jar", "depends", "NeedsBroken"));
        plugin(props, "SoftOnBroken", Map.of("state", "enabled", "files", "SoftOnBroken.jar", "soft-depends", "BrokenThing"));
        plugin(props, "FailsSmoke", Map.of("state", "enabled", "files", "FailsSmoke.jar", "failed-tests", "FailsSmoke:works"));
        plugin(props, "WarnsOnly", Map.of("state", "enabled", "files", "WarnsOnly.jar", "failed-tests", "WarnsOnly:version"));
        props.put("test.FailsSmoke:works.result", "failed");
        props.put("test.FailsSmoke:works.severity", "critical");
        props.put("test.FailsSmoke:works.message", "made-up failure");
        props.put("test.WarnsOnly:version.result", "failed");
        props.put("test.WarnsOnly:version.severity", "warning");
        props.put("test.WarnsOnly:version.message", "unsupported server version");
        return props;
    }

    static void plugin(Map<String, String> props, String name, Map<String, String> fields) {
        fields.forEach((k, v) -> props.put("plugin." + name + "." + k, v));
    }

    static byte[] xml(Map<String, String> props) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"Preflight\" tests=\"3\"><properties>\n");
        props.forEach((k, v) -> out.append("    <property name=\"").append(k).append("\" value=\"")
                .append(v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")).append("\"/>\n"));
        return out.append("</properties><testcase classname=\"Made.Up\" name=\"x\"/></testsuite>").toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    static Plan.Context context(Map<String, String> offInCopy) {
        Map<String, String> hashes = new LinkedHashMap<>();
        JARS.forEach(jar -> hashes.put(jar, "hash-of-" + jar));
        return new Plan.Context("test", "/made/up/server", "2026-11-08T00:00:00Z", "paper-26.3-244.jar",
                hashes, offInCopy, List.of());
    }

    static Plan plan(Map<String, String> props, Rules rules) {
        return Plan.make(Report.parse(xml(props)), rules, context(Map.of()));
    }

    @Test
    void readsTheReport() {
        Report parsed = Report.parse(xml(sample()));
        assertNotNull(parsed);
        assertEquals(List.of("MadeUpCore.jar"), parsed.plugins().get("Made.Up.Core").files(), "a plugin name with dots is split on the last dot");
        assertEquals(Report.State.NOT_LOADED, parsed.plugins().get("BrokenThing").state(), "a jar that never loaded is read");
        assertEquals("critical", parsed.tests().get("FailsSmoke:works").severity(), "and tests by their own id");
        assertEquals(244L, parsed.build());
    }

    @Test
    void plansWhatGoesOffAndWhy() {
        Rules rules = new Rules(List.of(), List.of(new Rules.Recheck("made.up.core", "Try it by hand."),
                new Rules.Recheck("NotInstalled", "Never shown.")));
        Plan plan = plan(sample(), rules);
        assertEquals(List.of("BrokenThing", "FailsSmoke", "NeedsBroken", "NeedsNeeds"),
                plan.off().stream().map(Plan.Off::name).toList(),
                "what didn't load, what failed a critical check, and everything that needs them, however far down");
        assertTrue(off(plan, "BrokenThing").why().getFirst().contains("Unsupported API version 99.0"), "with Paper's own reason");
        assertEquals(List.of("Off because NeedsBroken is off."), off(plan, "NeedsNeeds").why(), "a dependent says what it's off because of");
        assertTrue(off(plan, "NeedsBroken").why().stream().anyMatch(w -> w.contains("needs BrokenThing")), "enabled, but missing a dependency, counts as broken");
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("WarnsOnly:version")), "a warning is listed for a person");
        assertTrue(plan.off().stream().noneMatch(o -> o.name().equals("WarnsOnly")), "and switches nothing off");
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("SoftOnBroken stays on")), "a soft dependent stays on, flagged");
        assertEquals(List.of("made.up.core"), plan.recheck().stream().map(Rules.Recheck::plugin).toList(), "rechecks for installed plugins only, matched ignoring case");
        assertEquals(Plan.Status.SWITCH_OFF, plan.status(), "a plan with nothing protected can be applied");
        assertEquals("hash-of-BrokenThing-2.jar", off(plan, "BrokenThing").sha256().get("BrokenThing-2.jar"), "with each jar's hash at check time");
        assertEquals("Paper 26.3 build 244", plan.target().label());
    }

    @Test
    void neverOffRulesHoldTheUpgrade() {
        Plan held = plan(sample(), new Rules(List.of(new Rules.Keep("needsbroken", "Made-up protection.")), List.of()));
        assertEquals(List.of("NeedsBroken"), held.blocked().stream().map(Plan.Blocked::name).toList(), "a never-off plugin that would have to go is held, matched ignoring case");
        assertEquals(Plan.Status.WAIT, held.status(), "and then the upgrade waits");
        Plan later = plan(sample(), new Rules(List.of(new Rules.Keep("NotInstalledYet", "Arrives later.")), List.of()));
        assertEquals(Plan.Status.SWITCH_OFF, later.status(), "a never-off plugin that isn't installed is fine");
    }

    @Test
    void anythingThatCantBeJudgedOffersNothing() {
        Map<String, String> bad = sample();
        bad.remove("preflight.finished-at");
        assertTrue(plan(bad, null).stop().contains("isn't finished"), "an unfinished report offers nothing");
        bad = sample();
        bad.put("preflight.report.format", "2");
        assertTrue(plan(bad, null).stop().contains("format 2"), "a newer format offers nothing");
        bad = sample();
        bad.remove("preflight.report.format");
        assertTrue(plan(bad, null).stop().contains("too old"), "a report from an older Preflight offers nothing");
        bad = sample();
        bad.put("preflight.server-checks", "failed");
        assertTrue(plan(bad, null).stop().contains("doesn't run properly"), "a broken version says so instead of offering plugins");
        assertEquals(Plan.Status.STOP, Plan.make(null, null, context(Map.of())).status(), "no report offers nothing");
        assertNull(Report.parse("<not xml".getBytes(StandardCharsets.UTF_8)), "unreadable XML isn't a report");
        assertNull(Report.parse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"
                .getBytes(StandardCharsets.UTF_8)), "a report with a DTD is refused, so it can't read other files");
    }

    @Test
    void neverTouchesAFileOutsidePlugins() {
        for (String sneaky : List.of("../outside.jar", "sub/inside.jar", ".hidden.jar", "notajar.zip", "a\\b.jar")) {
            Map<String, String> bad = sample();
            bad.put("plugin.BrokenThing.files", sneaky);
            Plan plan = plan(bad, null);
            assertTrue(plan.blocked().stream().anyMatch(b -> b.name().equals("BrokenThing")), sneaky);
            assertEquals(Plan.Status.WAIT, plan.status(), sneaky + " is never touched");
        }
    }

    @Test
    void aJarThatWasntThereAtCheckTimeIsHeld() {
        Map<String, String> props = sample();
        props.put("plugin.BrokenThing.files", "SomethingElse.jar");
        Plan plan = plan(props, null);
        assertTrue(plan.blocked().stream().anyMatch(b -> b.name().equals("BrokenThing") && b.rule().contains("wasn't in")));
    }

    @Test
    void nothingToSwitchOffIsAllClear() {
        Plan clean = plan(base(), null);
        assertEquals(Plan.Status.ALL_CLEAR, clean.status());
        assertEquals(0, clean.status().exitCode);
    }

    @Test
    void flagsARunThatDidntWarnAndUnreadableJars() {
        Map<String, String> bad = sample();
        bad.put("preflight.on-failure", "LOCKDOWN");
        bad.put("preflight.unreadable-jars", "Junk.jar");
        Plan odd = plan(bad, null);
        assertTrue(odd.warnings().stream().anyMatch(w -> w.contains("LOCKDOWN")));
        assertTrue(odd.warnings().stream().anyMatch(w -> w.contains("Junk.jar")));
    }

    @Test
    void pluginsOffInTheCopyAndWhatNeedsThemAreNeverOffered() {
        Map<String, String> props = base();
        // DiscordSRV was switched off in the copy, so it's not in the report; its addon then failed to load.
        plugin(props, "ChatAddon", Map.of("state", "not-loaded", "files", "ChatAddon.jar", "depends", "DiscordSRV",
                "missing-depends", "DiscordSRV", "reason", "Unknown/missing dependency plugins: [DiscordSRV]"));
        plugin(props, "NeedsAddon", Map.of("state", "not-loaded", "files", "NeedsAddon.jar", "depends", "ChatAddon",
                "missing-depends", "ChatAddon"));
        plugin(props, "Unrelated", Map.of("state", "disabled", "files", "Unrelated.jar"));
        Map<String, String> hashes = Map.of("ChatAddon.jar", "a", "NeedsAddon.jar", "b", "Unrelated.jar", "c");
        Plan plan = Plan.make(Report.parse(xml(props)), null, new Plan.Context("test", "/s", "t", "p.jar", hashes,
                Map.of("DiscordSRV", "Off in the test copy."), List.of()));
        assertEquals(List.of("Unrelated"), plan.off().stream().map(Plan.Off::name).toList(), "only what's really broken");
        assertEquals(List.of("ChatAddon", "DiscordSRV", "NeedsAddon"), plan.notChecked().stream().map(Plan.NotChecked::name).toList());
        assertTrue(plan.notChecked().stream().filter(n -> n.name().equals("NeedsAddon")).findFirst().orElseThrow()
                .why().contains("ChatAddon"));
    }

    @Test
    void aPluginNeedingSomethingReallyBrokenStillGoesOffEvenIfItAlsoNeedsAnUncheckedOne() {
        Map<String, String> props = base();
        plugin(props, "Broken", Map.of("state", "disabled", "files", "Broken.jar"));
        plugin(props, "Both", Map.of("state", "not-loaded", "files", "Both.jar", "depends", "Broken,DiscordSRV"));
        Plan plan = Plan.make(Report.parse(xml(props)), null, new Plan.Context("test", "/s", "t", "p.jar",
                Map.of("Broken.jar", "a", "Both.jar", "b"), Map.of("DiscordSRV", "off in copy"), List.of()));
        assertEquals(List.of("Both", "Broken"), plan.off().stream().map(Plan.Off::name).toList());
        assertEquals(List.of("DiscordSRV"), plan.notChecked().stream().map(Plan.NotChecked::name).toList());
    }

    @Test
    void jsonRoundTripsAndRefusesEdits() throws Exception {
        Plan plan = plan(sample(), null);
        String json = Json.write(plan.toJson());
        Plan back = Plan.fromJson(json);
        assertEquals(plan.off(), back.off());
        assertEquals(plan.blocked(), back.blocked());
        assertEquals(plan.notChecked(), back.notChecked());
        assertEquals(plan.status(), back.status());
        assertEquals(plan.target(), back.target());

        String edited = json.replace("\"status\": \"switch-off\"", "\"status\": \"all-clear\"");
        assertThrows(Json.ParseException.class, () -> Plan.fromJson(edited), "a status that doesn't match the contents");
        String newer = json.replace("\"format\": 1", "\"format\": 2");
        assertThrows(Json.ParseException.class, () -> Plan.fromJson(newer));
    }

    @Test
    void jsonShapeIsWhatLeafJuiceReads() throws Exception {
        Map<String, Object> root = Json.object(Json.parse(Json.write(plan(sample(), null).toJson())), "plan");
        assertEquals(List.of("format", "tool", "server", "checkedAt", "target", "status", "off", "blocked",
                "notChecked", "warnings", "recheck", "preflight"), List.copyOf(root.keySet()));
        assertEquals("switch-off", root.get("status"));
        Map<String, Object> target = Json.object(root.get("target"), "target");
        assertEquals(244L, target.get("build"));
        Map<String, Object> stopped = Json.object(Json.parse(Json.write(Plan.make(null, null, context(Map.of())).toJson())), "plan");
        assertEquals("stop", stopped.get("status"));
        assertTrue(stopped.get("stop") instanceof String);
        assertFalse(root.containsKey("stop"), "stop appears only when stopped");
    }

    private static Plan.Off off(Plan plan, String name) {
        return plan.off().stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow();
    }
}
