package com.chithira.fieryhold;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls the hot folder and reports files once they are completely copied: size and modified time
 * unchanged for {@code watch.settle.millis} and the file can be opened for writing (Windows keeps
 * a file locked while Explorer is still copying it). Polling also works for network folders, where
 * WatchService events are unreliable.
 */
public final class HotFolderWatcher {

    public interface Listener {
        /** Files that are ready and have not been reported before. */
        void onFilesReady(List<Path> files);

        /** Previously reported files that disappeared from the folder. */
        void onFilesRemoved(Set<Path> files);
    }

    private record Candidate(long size, long modified, long stableSince) {}

    private static final Logger LOG = Logger.getLogger(HotFolderWatcher.class.getName());

    private final AppConfig cfg;
    private final Listener listener;
    private final Map<Path, Candidate> candidates = new HashMap<>();
    private final Set<Path> reported = new HashSet<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hot-folder-watcher");
        t.setDaemon(true);
        return t;
    });

    public HotFolderWatcher(AppConfig cfg, Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
    }

    public void start() throws IOException {
        Files.createDirectories(cfg.watchFolder());
        LOG.info("Watching " + cfg.watchFolder() + " for " + cfg.extensions());
        scheduler.scheduleWithFixedDelay(this::scanSafely, 0, cfg.pollMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void scanSafely() {
        try {
            scan();
        } catch (Exception e) {  // never let an exception cancel the scheduled task
            LOG.log(Level.WARNING, "Hot folder scan failed", e);
        }
    }

    private void scan() throws IOException {
        long now = System.currentTimeMillis();
        Set<Path> present = new HashSet<>();
        List<Path> ready = new ArrayList<>();

        try (DirectoryStream<Path> dir = Files.newDirectoryStream(cfg.watchFolder(), this::accepts)) {
            for (Path file : dir) {
                present.add(file);
                if (reported.contains(file)) continue;

                long size = Files.size(file);
                long modified = Files.getLastModifiedTime(file).toMillis();
                Candidate c = candidates.get(file);
                if (c == null || c.size() != size || c.modified() != modified) {
                    candidates.put(file, new Candidate(size, modified, now));
                } else if (size > 0 && now - c.stableSince() >= cfg.settleMillis() && isUnlocked(file)) {
                    candidates.remove(file);
                    reported.add(file);
                    ready.add(file);
                }
            }
        }

        candidates.keySet().retainAll(present);
        Set<Path> removed = new HashSet<>(reported);
        removed.removeAll(present);
        reported.removeAll(removed);

        if (!ready.isEmpty()) {
            LOG.info("New file(s) ready: " + ready);
            listener.onFilesReady(ready);
        }
        if (!removed.isEmpty()) listener.onFilesRemoved(removed);
    }

    private boolean accepts(Path p) {
        String name = p.getFileName().toString();
        if (name.startsWith(".") || name.startsWith("~$")) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !cfg.extensions().contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) return false;
        try {
            return Files.isRegularFile(p) && !Files.isHidden(p);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isUnlocked(Path file) {
        if (!Files.isWritable(file)) return true;  // read-only file: rely on the size check only
        try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
