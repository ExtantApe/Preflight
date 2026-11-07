package com.extantape.preflight.plugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Renders results as JUnit XML so CI systems can read them. Only CRITICAL failures count as
 * {@code <failure>}; WARNING failures go in {@code <system-err>} so they don't fail a build.
 */
final class JUnitReport {
    private JUnitReport() {}

    static String render(List<TestResult> results) {
        long failures = results.stream().filter(TestResult::blocking).count();
        double totalSeconds = results.stream().mapToLong(TestResult::durationMillis).sum() / 1000.0;

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"Preflight\" tests=\"").append(results.size())
                .append("\" failures=\"").append(failures)
                .append("\" errors=\"0\" time=\"").append(totalSeconds).append("\">\n");

        for (TestResult result : results) {
            xml.append("  <testcase classname=\"").append(escape(result.test().ownerName()))
                    .append("\" name=\"").append(escape(result.test().name()))
                    .append("\" time=\"").append(result.durationMillis() / 1000.0).append('"');
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

    private static String escape(String text) {
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
