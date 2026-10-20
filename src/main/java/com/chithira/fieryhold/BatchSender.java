package com.chithira.fieryhold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Sends jobs one by one on a single background thread. A file is removed from the hot folder
 * (deleted, or moved to the archive folder) only after the Fiery has confirmed receipt.
 */
public final class BatchSender {

    public enum Status { QUEUED, SENDING, DONE, FAILED }

    /** Called on the sender thread; UI listeners must hop to the EDT themselves. */
    public interface Listener {
        void onStatus(PrintJob job, Status status, String message);

        void onBatchFinished(int ok, int failed);
    }

    private static final Logger LOG = Logger.getLogger(BatchSender.class.getName());

    private final AppConfig cfg;
    private final FierySender sender;
    private final AtomicInteger pending = new AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fiery-sender");
        t.setDaemon(true);
        return t;
    });

    public BatchSender(AppConfig cfg, FierySender sender) {
        this.cfg = cfg;
        this.sender = sender;
    }

    public boolean isBusy() {
        return pending.get() > 0;
    }

    public void submit(List<PrintJob> jobs, Listener listener) {
        pending.addAndGet(jobs.size());
        jobs.forEach(j -> listener.onStatus(j, Status.QUEUED, "Queued"));
        worker.submit(() -> {
            int ok = 0;
            int failed = 0;
            for (PrintJob job : jobs) {
                try {
                    if (process(job, listener)) ok++;
                    else failed++;
                } catch (RuntimeException e) {
                    failed++;
                    listener.onStatus(job, Status.FAILED, "Unexpected error: " + e.getMessage());
                } finally {
                    pending.decrementAndGet();
                }
            }
            LOG.info("Batch finished: " + ok + " sent, " + failed + " failed");
            listener.onBatchFinished(ok, failed);
        });
    }

    private boolean process(PrintJob job, Listener listener) {
        if (!Files.exists(job.file())) {
            listener.onStatus(job, Status.FAILED, "File no longer exists");
            return false;
        }
        FierySender.Result result = null;
        for (int attempt = 1; attempt <= cfg.sendRetries() + 1; attempt++) {
            listener.onStatus(job, Status.SENDING, attempt == 1 ? "Sending..." : "Retry " + (attempt - 1) + "...");
            LOG.info("Sending " + job.fileName() + " (attempt " + attempt + ") " + job);
            result = sender.send(job);
            if (result.ok()) break;
            LOG.warning("Send failed for " + job.fileName() + ": " + result.message());
            if (attempt <= cfg.sendRetries()) sleep(cfg.retryDelaySeconds());
        }
        if (!result.ok()) {
            listener.onStatus(job, Status.FAILED, result.message());
            return false;
        }
        LOG.info("Verified on Fiery: " + job.fileName() + " - " + result.message());
        try {
            removeFromHotFolder(job.file());
            listener.onStatus(job, Status.DONE, "On Fiery hold" + (cfg.archiveFolder() == null ? "" : ", archived"));
        } catch (IOException e) {
            // The job IS on the Fiery; report it as done but warn so it is not sent twice.
            LOG.warning("Sent but could not remove " + job.file() + ": " + e);
            listener.onStatus(job, Status.DONE, "On Fiery hold, but file could not be removed: " + e.getMessage());
        }
        return true;
    }

    private void removeFromHotFolder(Path file) throws IOException {
        IOException last = null;
        for (int i = 0; i < 5; i++) {  // the file can be briefly locked by antivirus / indexer
            try {
                if (cfg.archiveFolder() == null) {
                    Files.deleteIfExists(file);
                } else {
                    Files.createDirectories(cfg.archiveFolder());
                    Files.move(file, uniqueTarget(cfg.archiveFolder(), file.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                sleep(1);
            }
        }
        throw last;
    }

    private static Path uniqueTarget(Path folder, String name) {
        Path target = folder.resolve(name);
        if (!Files.exists(target)) return target;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return folder.resolve(base + "_" + System.currentTimeMillis() + ext);
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
