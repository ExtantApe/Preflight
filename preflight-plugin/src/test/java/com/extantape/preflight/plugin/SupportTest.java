package com.extantape.preflight.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.extantape.preflight.api.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportTest {

    private static RegisteredTest test(String name, Severity severity) {
        return new RegisteredTest("Owner", null, name, severity, 100, context -> {});
    }

    @Test
    void registryRejectsDuplicatesAndLateRegistration() {
        DefaultRegistry registry = new DefaultRegistry();
        registry.add("A", null, "x", Severity.CRITICAL, 10, context -> {});
        registry.add("B", null, "x", Severity.CRITICAL, 10, context -> {});
        assertThrows(IllegalArgumentException.class,
                () -> registry.add("A", null, "x", Severity.CRITICAL, 10, context -> {}));
        assertEquals(2, registry.close().size());
        assertThrows(IllegalStateException.class,
                () -> registry.add("A", null, "y", Severity.CRITICAL, 10, context -> {}));
    }

    @Test
    void registryRejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultRegistry().add("A", null, "x", Severity.CRITICAL, 0, context -> {}));
    }

    @Test
    void disablePlanPutsDependentsFirstAndLeavesOthersAlone() {
        Map<String, List<String>> depends = Map.of(
                "Base", List.of(),
                "Middle", List.of("Base"),
                "Top", List.of("Middle"),
                "Unrelated", List.of());
        assertEquals(List.of("Top", "Middle", "Base"), DisablePlan.order(List.of("Base"), depends));
    }

    @Test
    void disablePlanHandlesDiamondDependency() {
        Map<String, List<String>> depends = Map.of(
                "Base", List.of(),
                "Left", List.of("Base"),
                "Right", List.of("Base"),
                "Top", List.of("Left", "Right"));
        List<String> order = DisablePlan.order(List.of("Base"), depends);
        assertEquals(4, order.size());
        assertEquals("Top", order.get(0));
        assertEquals("Base", order.get(3));
    }

    @Test
    void reportCountsOnlyCriticalFailuresAndEscapesText() {
        String xml = JUnitReport.render(List.of(
                new TestResult(test("good", Severity.CRITICAL), TestResult.Status.PASSED, null, null, 5),
                new TestResult(test("bad", Severity.CRITICAL), TestResult.Status.FAILED, "a <b> & \"c\"", null, 5),
                new TestResult(test("soft", Severity.WARNING), TestResult.Status.FAILED, "minor", null, 5)));
        assertTrue(xml.contains("tests=\"3\" failures=\"1\""), xml);
        assertTrue(xml.contains("a &lt;b&gt; &amp; &quot;c&quot;"), xml);
        assertTrue(xml.contains("<system-err>minor"), xml);
        assertFalse(xml.contains("<b>"), xml);
    }
}
