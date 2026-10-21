package com.chithira.fieryhold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
        if (!Files.isRegularFile(cfg.sendScript())) {
            String msg = "Send script not found: " + cfg.sendScript() + " (check send.script in " + cfg.configFile() + ")";
            LOG.severe(msg);
            return new Result(false, msg);
        }
        Path jobFile = null;
        Path outFile = null;
        Result result = null;
        try {
            jobFile = Files.createTempFile("fiery-job-", ".json");
            outFile = Files.createTempFile("fiery-job-", ".out");
            String json = toJson(job);
            Files.writeString(jobFile, json, StandardCharsets.UTF_8);

            List<String> command = List.of(
                    cfg.powershellExe(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-File", cfg.sendScript().toString(), "-JobFile", jobFile.toString());
            LOG.info("Executing PowerShell for " + job.fileName() + ":\n  " + commandLine(command)
                    + "\n  Job file " + jobFile + ":\n" + json.indent(4).stripTrailing());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(outFile.toFile());
            long start = System.nanoTime();
            Process process = pb.start();

            boolean finished = process.waitFor(cfg.sendTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            long millis = (System.nanoTime() - start) / 1_000_000;
            String output = readOutput(outFile);
            String exit = finished ? Integer.toString(process.exitValue()) : "killed (timeout)";
            LOG.info("PowerShell finished for " + job.fileName() + ": exit=" + exit + ", " + millis + " ms, output:\n"
                    + (output.isBlank() ? "    <no output>" : output.indent(4).stripTrailing()));

            if (!finished) {
                return result = new Result(false, "Timed out after " + cfg.sendTimeoutSeconds() + " s");
            }
            String resultLine = output.lines().filter(l -> l.startsWith("RESULT|")).reduce((a, b) -> b).orElse(null);
            if (resultLine == null) {
                // Script never reached its end: usually PowerShell itself failed (blocked script, syntax error, ...)
                String tail = output.lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("no output");
                return result = new Result(false, "Script exit " + exit + ": " + tail);
            }
            String[] parts = resultLine.split("\\|", 3);
            boolean ok = "OK".equals(parts[1]) && process.exitValue() == 0;
            return result = new Result(ok, parts.length > 2 ? parts[2] : "");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not run PowerShell (" + cfg.powershellExe()
                    + ") - check powershell.exe in " + cfg.configFile(), e);
            return result = new Result(false, "Could not run PowerShell: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result = new Result(false, "Interrupted");
        } finally {
            if (result != null && !result.ok()) keepForRerun(job, jobFile);
            deleteQuietly(jobFile);
            deleteQuietly(outFile);
        }
    }

    /** Copies a failed job's JSON to logs/failed-jobs and logs a command that reruns it by hand. */
    private void keepForRerun(PrintJob job, Path jobFile) {
        if (jobFile == null || !Files.exists(jobFile)) return;
        try {
            Path dir = Files.createDirectories(cfg.logFolder().resolve("failed-jobs"));
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path kept = dir.resolve(stamp + "_" + job.fileName().replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
            Files.copy(jobFile, kept, StandardCopyOption.REPLACE_EXISTING);
            LOG.warning("Failed job saved. Rerun it in a PowerShell window to see the full error:\n  "
                    + commandLine(List.of(cfg.powershellExe(), "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", cfg.sendScript().toString(), "-JobFile", kept.toString())));
        } catch (IOException e) {
            LOG.warning("Could not save failed job file: " + e);
        }
    }

    /** Command as it would be typed in cmd / PowerShell, arguments with spaces quoted. */
    private static String commandLine(List<String> command) {
        return command.stream()
                .map(a -> a.isEmpty() || a.contains(" ") ? '"' + a + '"' : a)
                .collect(Collectors.joining(" "));
    }

    /** PowerShell may write in the console code page; decode leniently instead of failing on bad UTF-8. */
    private static String readOutput(Path outFile) throws IOException {
        return new String(Files.readAllBytes(outFile), StandardCharsets.UTF_8);
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
