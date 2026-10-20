package com.chithira.fieryhold;

import com.chithira.fieryhold.AppConfig.Option;
import com.chithira.fieryhold.BatchSender.Status;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The popup shown when files land in the hot folder. Lists every pending file with a checkbox;
 * the form settings apply to all checked files, which are sent as one batch. The window is hidden
 * (not disposed) on close so pending files can be reopened from the tray.
 */
public final class JobDialog extends JFrame {

    private static final class Row {
        final Path file;
        final long size;
        boolean selected = true;
        Status status;
        String message = "Waiting";

        Row(Path file) {
            this.file = file;
            long s;
            try {
                s = Files.size(file);
            } catch (IOException e) {
                s = -1;
            }
            this.size = s;
        }

        boolean selectable() {
            return status == null || status == Status.FAILED;
        }
    }

    private final AppConfig cfg;
    private final BatchSender sender;
    private final BiConsumer<String, Boolean> notifier;
    private final List<Row> rows = new ArrayList<>();
    private final RowModel model = new RowModel();

    private final JTextField fileNameField = new JTextField();
    private final JComboBox<Option> mediaBox;
    private final JComboBox<Option> finishingBox;
    private final JCheckBox testPrintBox = new JCheckBox("Test print (1 copy)");
    private final JSpinner copiesSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 9999, 1));
    private final JTextArea instructionsArea = new JTextArea(3, 30);
    private final JTextField customerField = new JTextField();
    private final JButton sendButton = new JButton("Send selected to Fiery Hold");
    private final JLabel summaryLabel = new JLabel(" ");

    /** @param notifier shows a tray message: (text, isError) */
    public JobDialog(AppConfig cfg, BatchSender sender, BiConsumer<String, Boolean> notifier) {
        super("Fiery Hold Sender");
        this.cfg = cfg;
        this.sender = sender;
        this.notifier = notifier;
        this.mediaBox = new JComboBox<>(cfg.media().toArray(Option[]::new));
        this.finishingBox = new JComboBox<>(cfg.finishing().toArray(Option[]::new));

        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setIconImage(TrayController.createIcon());
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(buildTablePanel(), BorderLayout.CENTER);
        add(buildFormPanel(), BorderLayout.SOUTH);

        testPrintBox.addActionListener(e -> {
            copiesSpinner.setEnabled(!testPrintBox.isSelected());
            if (testPrintBox.isSelected()) copiesSpinner.setValue(1);
        });
        sendButton.addActionListener(e -> sendSelected());

        setMinimumSize(new Dimension(720, 600));
        pack();
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- layout

    private JPanel buildTablePanel() {
        JTable table = new JTable(model);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setCellRenderer(new StatusRenderer());

        JButton all = new JButton("Select all");
        all.addActionListener(e -> setAllSelected(true));
        JButton none = new JButton("Select none");
        none.addActionListener(e -> setAllSelected(false));
        JButton clear = new JButton("Clear sent");
        clear.addActionListener(e -> {
            rows.removeIf(r -> r.status == Status.DONE);
            model.fireTableDataChanged();
            refreshSummary();
        });
        JButton open = new JButton("Open hot folder");
        open.addActionListener(e -> TrayController.openFolder(cfg.watchFolder()));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(all);
        buttons.add(none);
        buttons.add(clear);
        buttons.add(open);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Files in " + cfg.watchFolder()));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildFormPanel() {
        fileNameField.setEditable(false);
        instructionsArea.setLineWrap(true);
        instructionsArea.setWrapStyleWord(true);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Job settings (applied to every selected file)"));
        int y = 0;
        addRow(form, y++, "File name", fileNameField);
        addRow(form, y++, "Media", mediaBox);
        addRow(form, y++, "Finishing", finishingBox);
        addRow(form, y++, "Print count", copiesSpinner);
        addRow(form, y++, "", testPrintBox);
        addRow(form, y++, "Customer name", customerField);
        addRow(form, y++, "Additional instruction", new JScrollPane(instructionsArea));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.add(summaryLabel, BorderLayout.CENTER);
        bottom.add(sendButton, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(4, 8));
        panel.add(form, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private static void addRow(JPanel form, int y, String label, Component field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
    }

    // ---------------------------------------------------------------- called by watcher / tray (EDT)

    public void addFiles(List<Path> files) {
        for (Path f : files) {
            Row existing = find(f);
            if (existing == null) {
                rows.add(new Row(f));
            } else if (existing.status == Status.DONE) {
                // same name dropped again after a successful send: treat as a new file
                rows.remove(existing);
                rows.add(new Row(f));
            }
        }
        model.fireTableDataChanged();
        refreshSummary();
        showWindow();
    }

    public void removeFiles(Set<Path> files) {
        // Sent rows stay visible as confirmation; only drop files that vanished without being sent.
        rows.removeIf(r -> files.contains(r.file) && (r.status == null || r.status == Status.FAILED));
        model.fireTableDataChanged();
        refreshSummary();
    }

    public void showWindow() {
        if (!isVisible()) setVisible(true);
        setExtendedState(NORMAL);
        // Windows blocks focus stealing from background apps; a brief always-on-top forces it to the front.
        setAlwaysOnTop(true);
        toFront();
        requestFocus();
        setAlwaysOnTop(false);
    }

    public boolean hasPending() {
        return rows.stream().anyMatch(Row::selectable);
    }

    // ---------------------------------------------------------------- sending

    private void sendSelected() {
        List<Row> chosen = rows.stream().filter(r -> r.selected && r.selectable()).toList();
        if (chosen.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select at least one file.", getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        Option media = (Option) mediaBox.getSelectedItem();
        Option finishing = (Option) finishingBox.getSelectedItem();
        boolean test = testPrintBox.isSelected();
        int copies = (Integer) copiesSpinner.getValue();
        String instructions = instructionsArea.getText().trim();
        String customer = customerField.getText().trim();

        List<PrintJob> jobs = chosen.stream()
                .map(r -> new PrintJob(r.file, media, finishing, test, copies, instructions, customer))
                .toList();
        chosen.forEach(r -> r.selected = false);
        sender.submit(jobs, new BatchSender.Listener() {
            @Override
            public void onStatus(PrintJob job, Status status, String message) {
                SwingUtilities.invokeLater(() -> updateStatus(job.file(), status, message));
            }

            @Override
            public void onBatchFinished(int ok, int failed) {
                SwingUtilities.invokeLater(() -> {
                    String text = ok + " file(s) sent to Fiery Hold" + (failed > 0 ? ", " + failed + " FAILED" : "");
                    notifier.accept(text, failed > 0);
                    if (failed > 0) showWindow();
                });
            }
        });
    }

    private void updateStatus(Path file, Status status, String message) {
        Row r = find(file);
        if (r == null) return;
        r.status = status;
        r.message = message;
        model.fireTableDataChanged();
        refreshSummary();
    }

    // ---------------------------------------------------------------- helpers

    private Row find(Path file) {
        return rows.stream().filter(r -> r.file.equals(file)).findFirst().orElse(null);
    }

    private void setAllSelected(boolean selected) {
        rows.stream().filter(Row::selectable).forEach(r -> r.selected = selected);
        model.fireTableDataChanged();
        refreshSummary();
    }

    private void refreshSummary() {
        List<Row> selected = rows.stream().filter(r -> r.selected && r.selectable()).toList();
        fileNameField.setText(switch (selected.size()) {
            case 0 -> "";
            case 1 -> selected.getFirst().file.getFileName().toString();
            default -> selected.size() + " files selected (batch)";
        });
        long busy = rows.stream().filter(r -> r.status == Status.QUEUED || r.status == Status.SENDING).count();
        summaryLabel.setText(busy > 0 ? "Sending " + busy + " file(s)..." : rows.size() + " file(s) listed");
        sendButton.setText(selected.size() > 1
                ? "Send " + selected.size() + " files to Fiery Hold" : "Send selected to Fiery Hold");
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private final class RowModel extends AbstractTableModel {
        private final String[] columns = {"", "File name", "Size", "Status"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0 && rows.get(r).selectable();
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.selected && row.selectable();
                case 1 -> row.file.getFileName().toString();
                case 2 -> humanSize(row.size);
                default -> row.message;
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            if (c == 0) {
                rows.get(r).selected = (Boolean) value;
                refreshSummary();
            }
        }
    }

    private final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setToolTipText(String.valueOf(v));
            if (!sel) {
                Status s = rows.get(r).status;
                comp.setForeground(s == Status.DONE ? new Color(0, 128, 0)
                        : s == Status.FAILED ? Color.RED
                        : s == null ? t.getForeground() : new Color(0, 90, 180));
            }
            return comp;
        }
    }
}
