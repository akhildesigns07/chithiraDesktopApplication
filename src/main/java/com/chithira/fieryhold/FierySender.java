package com.chithira.fieryhold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends one file to the Fiery by running Send-ToFieryHold.ps1. The job settings are handed over in a
 * temporary JSON file (avoids PowerShell argument quoting problems). The script ends with a line
 * {@code RESULT|OK|message} or {@code RESULT|FAIL|message} and exit code 0 / 1.
 */
public final class FierySender {

    public record Result(boolean ok, String message) {}

    private static final Logger LOG = Logger.getLogger(FierySender.class.getName());

    private final AppConfig cfg;

    public FierySender(AppConfig cfg) {
        this.cfg = cfg;
    }

    public Result send(PrintJob job) {
        Path jobFile = null;
        Path outFile = null;
        try {
            jobFile = Files.createTempFile("fiery-job-", ".json");
            outFile = Files.createTempFile("fiery-job-", ".out");
            Files.writeString(jobFile, toJson(job), StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    cfg.powershellExe(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-File", cfg.sendScript().toString(), "-JobFile", jobFile.toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(outFile.toFile());
            Process process = pb.start();

            if (!process.waitFor(cfg.sendTimeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, "Timed out after " + cfg.sendTimeoutSeconds() + " s");
            }
            String output = Files.readString(outFile, StandardCharsets.UTF_8);
            LOG.fine(() -> "Script output for " + job.fileName() + ":\n" + output);

            String resultLine = output.lines().filter(l -> l.startsWith("RESULT|")).reduce((a, b) -> b).orElse(null);
            if (resultLine == null) {
                String tail = output.lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("no output");
                return new Result(false, "Script exit " + process.exitValue() + ": " + tail);
            }
            String[] parts = resultLine.split("\\|", 3);
            boolean ok = "OK".equals(parts[1]) && process.exitValue() == 0;
            return new Result(ok, parts.length > 2 ? parts[2] : "");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not run send script", e);
            return new Result(false, "Could not run PowerShell: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Interrupted");
        } finally {
            deleteQuietly(jobFile);
            deleteQuietly(outFile);
        }
    }

    /** Job name as it appears in the Fiery Hold queue. */
    String jobName(PrintJob job) {
        String name = cfg.jobNamePattern()
                .replace("{file}", job.fileName())
                .replace("{customer}", job.customerName().isBlank() ? "" : job.customerName().trim())
                .replaceAll("^[_\\-\\s]+", "");
        return sanitize((job.testPrint() ? cfg.testPrintPrefix() : "") + name);
    }

    /** All PJL lines for this job, built from the dropdown options and the field templates. */
    List<String> pjlLines(PrintJob job) {
        List<String> pjl = new ArrayList<>(job.media().pjl());
        pjl.addAll(job.finishing().pjl());
        int copies = job.testPrint() ? 1 : job.copies();
        cfg.copiesPjl().forEach(t -> pjl.add(t.replace("{value}", Integer.toString(copies))));
        if (!job.customerName().isBlank()) {
            cfg.customerPjl().forEach(t -> pjl.add(t.replace("{value}", sanitize(job.customerName()))));
        }
        if (!job.instructions().isBlank()) {
            cfg.instructionsPjl().forEach(t -> pjl.add(t.replace("{value}", sanitize(job.instructions()))));
        }
        return pjl;
    }

    private String toJson(PrintJob job) {
        StringBuilder sb = new StringBuilder("{\n");
        field(sb, "file", job.file().toAbsolutePath().toString());
        field(sb, "jobName", jobName(job));
        field(sb, "method", cfg.fieryMethod());
        field(sb, "host", cfg.fieryHost());
        field(sb, "queue", cfg.fieryQueue());
        sb.append("  \"port\": ").append(cfg.fieryPort()).append(",\n");
        sb.append("  \"connectTimeoutSeconds\": ").append(cfg.connectTimeoutSeconds()).append(",\n");
        sb.append("  \"ackTimeoutSeconds\": ").append(cfg.ackTimeoutSeconds()).append(",\n");
        sb.append("  \"copies\": ").append(job.testPrint() ? 1 : job.copies()).append(",\n");
        sb.append("  \"testPrint\": ").append(job.testPrint()).append(",\n");
        field(sb, "media", job.media().label());
        field(sb, "finishing", job.finishing().label());
        field(sb, "customerName", job.customerName());
        field(sb, "instructions", job.instructions());
        field(sb, "logFile", cfg.logFolder().resolve("fiery_hold_send.log").toString());
        sb.append("  \"pjl\": [");
        List<String> pjl = pjlLines(job);
        for (int i = 0; i < pjl.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(escape(pjl.get(i))).append('"');
        }
        return sb.append("]\n}\n").toString();
    }

    private static void field(StringBuilder sb, String name, String value) {
        sb.append("  \"").append(name).append("\": \"").append(escape(value)).append("\",\n");
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Values go inside PJL/LPD lines: strip quotes and control characters so they cannot break the header. */
    static String sanitize(String s) {
        return s.replaceAll("[\\p{Cntrl}\"]", " ").replaceAll("\\s+", " ").trim();
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file, the OS cleans it up eventually
        }
    }
}
