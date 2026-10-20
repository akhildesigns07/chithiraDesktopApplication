package com.chithira.fieryhold;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Background tray application: watches the hot folder, pops up the job form for new files and sends
 * the chosen files to the Fiery Hold queue one by one.
 *
 * <pre>javaw -jar fiery-hold-sender.jar [path\to\config.properties]</pre>
 */
public final class FieryHoldApp {

    private static final Logger LOG = Logger.getLogger(FieryHoldApp.class.getName());

    private static FileLock instanceLock;  // held for the lifetime of the process

    public static void main(String[] args) {
        Path configPath = resolveConfig(args);
        AppConfig cfg;
        try {
            cfg = AppConfig.load(configPath);
            initLogging(cfg.logFolder());
        } catch (IOException e) {
            fatal("Cannot load configuration " + configPath + ":\n" + e.getMessage());
            return;
        }
        if (!acquireSingleInstance()) {
            LOG.info("Another instance is already running; exiting");
            return;
        }
        LOG.info("Starting with config " + cfg.configFile());
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }

        BatchSender sender = new BatchSender(cfg, new FierySender(cfg));
        TrayController tray = new TrayController(cfg);
        JobDialog[] dialog = new JobDialog[1];
        HotFolderWatcher watcher = new HotFolderWatcher(cfg, new HotFolderWatcher.Listener() {
            @Override
            public void onFilesReady(List<Path> files) {
                SwingUtilities.invokeLater(() -> dialog[0].addFiles(files));
            }

            @Override
            public void onFilesRemoved(Set<Path> files) {
                SwingUtilities.invokeLater(() -> dialog[0].removeFiles(files));
            }
        });

        try {
            SwingUtilities.invokeAndWait(() -> {
                dialog[0] = new JobDialog(cfg, sender, tray::notify);
                tray.install(dialog[0]::showWindow, () -> exit(dialog[0], sender, watcher, tray));
            });
            watcher.start();
            tray.notify("Watching " + cfg.watchFolder(), false);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Startup failed", e);
            fatal("Startup failed: " + e.getMessage());
        }
    }

    private static void exit(JobDialog dialog, BatchSender sender, HotFolderWatcher watcher, TrayController tray) {
        if (sender.isBusy() && JOptionPane.showConfirmDialog(dialog,
                "Files are still being sent. Exit anyway?", "Fiery Hold Sender",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        LOG.info("Exiting");
        watcher.stop();
        sender.shutdown();
        tray.remove();
        System.exit(0);
    }

    /** args[0], else config.properties next to the jar (or its parent / config folder), else working dir. */
    private static Path resolveConfig(String[] args) {
        if (args.length > 0) return Path.of(args[0]);
        try {
            Path appDir = Path.of(FieryHoldApp.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParent();
            // walk up: <dir>/config.properties or <dir>/config/config.properties, for the plain jar,
            // target/ during development and FieryHoldSender/app/ inside the jpackage image
            for (Path dir = appDir; dir != null && dir.getNameCount() >= appDir.getNameCount() - 2; dir = dir.getParent()) {
                for (Path candidate : List.of(dir.resolve("config.properties"), dir.resolve("config/config.properties"))) {
                    if (Files.isRegularFile(candidate)) return candidate;
                }
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // fall through
        }
        return Path.of("config", "config.properties");
    }

    private static void initLogging(Path logFolder) throws IOException {
        Files.createDirectories(logFolder);
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT.%1$tL [%4$-7s] %3$s - %5$s%6$s%n");
        FileHandler fh = new FileHandler(logFolder.resolve("fiery-hold-app-%g.log").toString(),
                5 * 1024 * 1024, 5, true);
        fh.setFormatter(new SimpleFormatter());
        Logger root = Logger.getLogger("");
        root.addHandler(fh);
        Logger.getLogger("com.chithira").setLevel(Level.FINE);
        fh.setLevel(Level.FINE);
    }

    private static boolean acquireSingleInstance() {
        try {
            String base = System.getenv("LOCALAPPDATA");
            Path dir = Path.of(base != null ? base : System.getProperty("user.home"), "FieryHoldSender");
            Files.createDirectories(dir);
            @SuppressWarnings("resource")  // must stay open while the app runs
            RandomAccessFile raf = new RandomAccessFile(dir.resolve("app.lock").toFile(), "rw");
            instanceLock = raf.getChannel().tryLock();
            return instanceLock != null;
        } catch (IOException e) {
            LOG.warning("Single-instance check failed, continuing: " + e);
            return true;
        }
    }

    private static void fatal(String message) {
        LOG.severe(message);
        JOptionPane.showMessageDialog(null, message, "Fiery Hold Sender", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }
}
