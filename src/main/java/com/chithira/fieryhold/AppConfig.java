package com.chithira.fieryhold;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Typed view of config.properties. Relative paths resolve against the config file's folder. */
public record AppConfig(
        Path configFile,
        Path watchFolder,
        Set<String> extensions,
        long pollMillis,
        long settleMillis,
        Path archiveFolder,
        String fieryHost,
        String fieryMethod,
        String fieryQueue,
        int fieryPort,
        int connectTimeoutSeconds,
        int ackTimeoutSeconds,
        String powershellExe,
        Path sendScript,
        int sendTimeoutSeconds,
        int sendRetries,
        int retryDelaySeconds,
        Path logFolder,
        List<Option> media,
        List<Option> finishing,
        List<String> copiesPjl,
        List<String> customerPjl,
        List<String> instructionsPjl,
        String testPrintPrefix,
        String jobNamePattern) {

    /** A dropdown entry and the PJL lines it adds to the job header. */
    public record Option(String label, List<String> pjl) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static AppConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        Path base = file.toAbsolutePath().getParent();
        String archive = p.getProperty("archive.folder", "").trim();
        String method = p.getProperty("fiery.method", "LPR").trim().toUpperCase(Locale.ROOT);

        AppConfig cfg = new AppConfig(
                file.toAbsolutePath(),
                path(base, required(p, "watch.folder")),
                Arrays.stream(p.getProperty("watch.extensions", "pdf,ps,jpg,jpeg").split(","))
                        .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()),
                longProp(p, "watch.poll.millis", 1000),
                longProp(p, "watch.settle.millis", 2000),
                archive.isEmpty() ? null : path(base, archive),
                required(p, "fiery.host"),
                method,
                p.getProperty("fiery.queue", "hold").trim(),
                (int) longProp(p, "fiery.port", method.equals("RAW") ? 9100 : 515),
                (int) longProp(p, "fiery.connect.timeout.seconds", 10),
                (int) longProp(p, "fiery.ack.timeout.seconds", 120),
                p.getProperty("powershell.exe", "powershell.exe").trim(),
                path(base, p.getProperty("send.script", "../scripts/Send-ToFieryHold.ps1")),
                (int) longProp(p, "send.timeout.seconds", 600),
                (int) longProp(p, "send.retries", 2),
                (int) longProp(p, "send.retry.delay.seconds", 5),
                path(base, p.getProperty("log.folder", "../logs")),
                options(p, "media"),
                options(p, "finishing"),
                lines(p.getProperty("pjl.copies", "")),
                lines(p.getProperty("pjl.customer", "")),
                lines(p.getProperty("pjl.instructions", "")),
                p.getProperty("testprint.prefix", "TEST_"),
                p.getProperty("jobname.pattern", "{file}").trim());

        if (!method.equals("LPR") && !method.equals("RAW")) {
            throw new IOException("fiery.method must be LPR or RAW, was: " + method);
        }
        if (cfg.media.isEmpty() || cfg.finishing.isEmpty()) {
            throw new IOException("At least one media.N.label and finishing.N.label must be configured");
        }
        return cfg;
    }

    private static String required(Properties p, String key) throws IOException {
        String v = p.getProperty(key, "").trim();
        if (v.isEmpty()) throw new IOException("Missing required property: " + key);
        return v;
    }

    private static long longProp(Properties p, String key, long def) throws IOException {
        String v = p.getProperty(key, "").trim();
        if (v.isEmpty()) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IOException("Property " + key + " must be a number, was: " + v);
        }
    }

    private static Path path(Path base, String value) {
        return base.resolve(value.trim()).normalize();
    }

    /** Reads prefix.1.label / prefix.1.pjl, prefix.2.label ... until the first gap. */
    private static List<Option> options(Properties p, String prefix) {
        List<Option> list = new ArrayList<>();
        for (int i = 1; ; i++) {
            String label = p.getProperty(prefix + "." + i + ".label");
            if (label == null || label.isBlank()) break;
            list.add(new Option(label.trim(), lines(p.getProperty(prefix + "." + i + ".pjl", ""))));
        }
        return List.copyOf(list);
    }

    private static List<String> lines(String value) {
        return Arrays.stream(value.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
