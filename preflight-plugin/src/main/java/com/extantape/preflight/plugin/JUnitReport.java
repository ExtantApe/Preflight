package com.extantape.preflight.plugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Renders results as JUnit XML so CI systems can read them. Only CRITICAL failures count as
 * {@code <failure>}; WARNING failures go in {@code <system-err>} so they don't fail a build. Skipped tests
 * are standard {@code <skipped/>} cases. Machine-readable detail goes in the suite's {@code <properties>},
 * which JUnit readers that don't know them ignore.
 */
final class JUnitReport {
    /** Always with seconds: LocalDateTime.toString() drops ":00", which JUnit readers reject. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private JUnitReport() {}

    static String render(List<TestResult> results) {
        return render(results, Map.of(), null);
    }

    /**
     * @param properties written in order as {@code <property>} elements, before any test case
     * @param finishedAt written as the suite's {@code timestamp}, in UTC without a zone as JUnit expects
     */
    static String render(List<TestResult> results, Map<String, String> properties, Instant finishedAt) {
        long failures = results.stream().filter(TestResult::blocking).count();
        long skipped = results.stream().filter(result -> result.status() == TestResult.Status.SKIPPED).count();
        double totalSeconds = results.stream().mapToLong(TestResult::durationMillis).sum() / 1000.0;

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"Preflight\" tests=\"").append(results.size())
                .append("\" failures=\"").append(failures)
                .append("\" errors=\"0\" skipped=\"").append(skipped)
                .append("\" time=\"").append(totalSeconds).append('"');
        if (finishedAt != null) {
            xml.append(" timestamp=\"")
                    .append(TIMESTAMP.format(finishedAt.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)))
                    .append('"');
        }
        xml.append(">\n");
        if (!properties.isEmpty()) {
            xml.append("  <properties>\n");
            properties.forEach((name, value) -> xml.append("    <property name=\"").append(escape(name))
                    .append("\" value=\"").append(escape(value)).append("\"/>\n"));
            xml.append("  </properties>\n");
        }

        for (TestResult result : results) {
            xml.append("  <testcase classname=\"").append(escape(result.test().ownerName()))
                    .append("\" name=\"").append(escape(result.test().name()))
                    .append("\" time=\"").append(result.durationMillis() / 1000.0).append('"');
            if (result.status() == TestResult.Status.SKIPPED) {
                xml.append(">\n    <skipped message=\"").append(escape(result.message()))
                        .append("\"/>\n  </testcase>\n");
                continue;
            }
            if (!result.failed()) {
                xml.append("/>\n");
                continue;
            }
            String tag = result.blocking() ? "failure" : "system-err";
            String details = escape(result.message() + stackTrace(result.cause()));
            xml.append(">\n    <").append(tag);
            if (result.blocking()) {
                xml.append(" type=\"").append(result.status()).append("\" message=\"")
                        .append(escape(result.message())).append('"');
            }
            xml.append('>').append(details).append("</").append(tag).append(">\n  </testcase>\n");
        }
        return xml.append("</testsuite>\n").toString();
    }

    private static String stackTrace(Throwable cause) {
        if (cause == null) {
            return "";
        }
        StringWriter out = new StringWriter();
        cause.printStackTrace(new PrintWriter(out));
        return "\n" + out;
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // XML 1.0 can't represent most control characters, even escaped.
                    boolean legal = c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF);
                    if (legal) {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }
}
