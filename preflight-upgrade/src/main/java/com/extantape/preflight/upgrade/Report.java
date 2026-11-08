package com.extantape.preflight.upgrade;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * What Preflight wrote to {@code plugins/Preflight/report.xml}. Only the suite's properties are read, as
 * described in docs/report-format.md. Plugin keys are split on the last dot, because names can contain dots.
 */
record Report(
        Integer format,
        String preflightVersion,
        String finishedAt,
        String serverChecks,
        String onFailure,
        List<String> unreadableJars,
        String brand,
        String serverVersion,
        String minecraft,
        Long build,
        Map<String, Plugin> plugins,
        Map<String, Test> tests) {

    static final String FILE = "plugins/Preflight/report.xml";

    enum State {
        ENABLED("enabled"), DISABLED("disabled"), NOT_LOADED("not-loaded"), UNKNOWN("unknown");

        final String label;

        State(String label) {
            this.label = label;
        }

        static State of(String label) {
            return Arrays.stream(values()).filter(state -> state.label.equals(label)).findFirst().orElse(UNKNOWN);
        }
    }

    record Plugin(String name, State state, String version, List<String> files, List<String> depends,
            List<String> softDepends, List<String> requiredBy, List<String> missingDepends, List<String> failedTests,
            String reason) {}

    record Test(String id, String result, String severity, String message) {
        boolean failed() {
            return result.equals("failed") || result.equals("timed-out");
        }
    }

    /** Returns null when the bytes aren't well-formed XML, which counts as no report at all. */
    static Report parse(byte[] xml) {
        Map<String, String> properties = new LinkedHashMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The file comes from a copy of someone's server: no DTDs, no outside entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            NodeList nodes = builder.parse(new ByteArrayInputStream(xml)).getElementsByTagName("property");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element property = (Element) nodes.item(i);
                if (property.hasAttribute("name") && property.hasAttribute("value")) {
                    properties.put(property.getAttribute("name"), property.getAttribute("value"));
                }
            }
        } catch (Exception e) {
            return null;
        }
        return fromProperties(properties);
    }

    static Report fromProperties(Map<String, String> properties) {
        Map<String, Map<String, String>> pluginFields = new LinkedHashMap<>();
        Map<String, Map<String, String>> testFields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            int dot = key.lastIndexOf('.');
            if (dot < 0) {
                continue;
            }
            String head = key.substring(0, dot);
            String field = key.substring(dot + 1);
            if (head.startsWith("plugin.") && head.length() > "plugin.".length()) {
                pluginFields.computeIfAbsent(head.substring("plugin.".length()), k -> new LinkedHashMap<>())
                        .put(field, entry.getValue());
            } else if (head.startsWith("test.") && head.length() > "test.".length()) {
                testFields.computeIfAbsent(head.substring("test.".length()), k -> new LinkedHashMap<>())
                        .put(field, entry.getValue());
            }
        }

        Map<String, Plugin> plugins = new LinkedHashMap<>();
        pluginFields.forEach((name, fields) -> plugins.put(name, new Plugin(name,
                State.of(fields.getOrDefault("state", "")),
                fields.getOrDefault("version", ""),
                list(fields.getOrDefault("files", fields.get("file"))),
                list(fields.get("depends")),
                list(fields.get("soft-depends")),
                list(fields.get("required-by")),
                list(fields.get("missing-depends")),
                list(fields.get("failed-tests")),
                fields.get("reason"))));
        Map<String, Test> tests = new LinkedHashMap<>();
        testFields.forEach((id, fields) -> tests.put(id, new Test(id, fields.getOrDefault("result", ""),
                fields.getOrDefault("severity", ""), fields.get("message"))));

        return new Report(integer(properties.get("preflight.report.format")),
                properties.get("preflight.version"),
                properties.get("preflight.finished-at"),
                properties.get("preflight.server-checks"),
                properties.get("preflight.on-failure"),
                list(properties.get("preflight.unreadable-jars")),
                properties.get("server.brand"),
                properties.get("server.version"),
                properties.get("server.minecraft-version"),
                longValue(properties.get("server.build")),
                plugins, tests);
    }

    static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static Integer integer(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longValue(String value) {
        try {
            return value == null ? null : Long.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
