package com.extantape.preflight.upgrade;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A stand-in for a Paper server jar in unit tests. Run from the copy's folder, it records what it was
 * started with and what it found, then writes {@code fake-report.xml} as Preflight's report. With a
 * {@code fake-hang} file it waits for "stop" on stdin instead, like a server that never finishes.
 */
public final class FakePaper {
    private FakePaper() {}

    public static void main(String[] args) throws Exception {
        Path here = Path.of("").toAbsolutePath();
        Properties saw = new Properties();
        saw.setProperty("on-failure", System.getProperty("preflight.on-failure", ""));
        saw.setProperty("shutdown-after-run", System.getProperty("preflight.shutdown-after-run", ""));
        Properties server = new Properties();
        try (var reader = Files.newBufferedReader(here.resolve("server.properties"))) {
            server.load(reader);
        }
        saw.setProperty("server-ip", server.getProperty("server-ip", ""));
        saw.setProperty("server-port", server.getProperty("server-port", ""));
        saw.setProperty("enable-rcon", server.getProperty("enable-rcon", ""));
        List<String> plugins = new ArrayList<>();
        try (var stream = Files.list(here.resolve("plugins"))) {
            stream.map(p -> p.getFileName().toString()).sorted().forEach(plugins::add);
        }
        saw.setProperty("plugins", String.join(",", plugins));
        saw.setProperty("has-logs", Boolean.toString(Files.exists(here.resolve("logs"))));
        saw.setProperty("has-session-lock", Boolean.toString(Files.exists(here.resolve("world/session.lock"))));
        try (var writer = Files.newBufferedWriter(here.resolve("fake-paper-saw.properties"))) {
            saw.store(writer, null);
        }
        if (Files.exists(here.resolve("fake-hang"))) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (String line; (line = in.readLine()) != null; ) {
                if (line.strip().equals("stop")) {
                    System.out.println("Stopping server");
                    return;
                }
            }
            return;
        }
        Path report = here.resolve("fake-report.xml");
        if (Files.exists(report)) {
            Files.createDirectories(here.resolve("plugins/Preflight"));
            Files.copy(report, here.resolve("plugins/Preflight/report.xml"));
        }
        System.out.println("[Preflight] 5/5 startup tests passed.");
    }
}
