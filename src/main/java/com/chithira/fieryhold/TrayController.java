package com.chithira.fieryhold;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/** System tray icon: the only visible sign of the app while no files are pending. */
public final class TrayController {

    private static final Logger LOG = Logger.getLogger(TrayController.class.getName());

    private final AppConfig cfg;
    private TrayIcon trayIcon;

    public TrayController(AppConfig cfg) {
        this.cfg = cfg;
    }

    public void install(Runnable showWindow, Runnable exit) {
        if (!SystemTray.isSupported()) {
            LOG.warning("System tray not supported; running without tray icon");
            return;
        }
        PopupMenu menu = new PopupMenu();
        menu.add(item("Show pending files", showWindow));
        menu.add(item("Open hot folder", () -> openFolder(cfg.watchFolder())));
        menu.add(item("Open log folder", () -> openFolder(cfg.logFolder())));
        menu.addSeparator();
        menu.add(item("Exit", exit));

        trayIcon = new TrayIcon(createIcon(), "Fiery Hold Sender - watching " + cfg.watchFolder(), menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> showWindow.run());  // double-click
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            LOG.warning("Could not add tray icon: " + e);
        }
    }

    public void notify(String text, boolean error) {
        if (trayIcon != null) {
            trayIcon.displayMessage("Fiery Hold Sender", text,
                    error ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.INFO);
        }
    }

    public void remove() {
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }

    static void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warning("Cannot open " + folder + ": " + e);
        }
    }

    /** Drawn in code so the jar needs no image resources. */
    static Image createIcon() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 94, 184));
        g.fillRoundRect(1, 1, 30, 30, 8, 8);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        g.drawRect(8, 5, 16, 8);
        g.fillRect(5, 13, 22, 10);
        g.setColor(new Color(0, 94, 184));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.drawString("H", 13, 22);
        g.setColor(Color.WHITE);
        g.fillRect(9, 23, 14, 5);
        g.dispose();
        return img;
    }
}
