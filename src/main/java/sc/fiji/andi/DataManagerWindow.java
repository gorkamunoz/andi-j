package sc.fiji.andi;

import ij.IJ;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * Persistent "Manage Data" window.
 *
 * Opened by AnDi-J > Trajectory Analysis and by the "Manage Data" button
 * inside AnalysisWindow.  State (loaded files, tags, cut lengths) is
 * preserved across show/hide cycles.
 *
 * Workflow:
 *   1. Choose "Load as separate" or "Merge", optionally fill Tag / Cut length.
 *   2. Click "Load Data…" to pick one or more CSV files.
 *   3. Review the file table; edit Tag or Cut length cells, remove rows.
 *   4. Click "Perform Analysis" → files that share a tag are merged into one
 *      ExperimentData, cut-length filters are applied per file, tMSD / D / α
 *      are pre-computed, and AnalysisWindow opens (or refreshes).
 */
public class DataManagerWindow extends JFrame {

    // ── persistent state ─────────────────────────────────────────────────────
    private final List<FileRow>        rows         = new ArrayList<>();
    private final Map<String, Color>   tagColors    = new LinkedHashMap<>();
    private int                        nextColorIdx = 0;

    /** Folder of the most recently loaded CSV; reused as the file chooser's
     *  starting directory. Static so it persists across data-manager windows
     *  and re-openings within the session. */
    private static File lastLoadDir = null;

    /** Called with the assembled experiments when "Perform Analysis" is clicked. */
    private final AnalysisLauncher launcher;

    /**
     * Callback invoked by {@link DataManagerWindow} once experiments have been
     * assembled.  Implementations are responsible for running any required
     * computations (tMSD, segmentation predictions, …) and opening the target window.
     *
     * <p>This method is called on the Swing Event Dispatch Thread.</p>
     */
    public interface AnalysisLauncher {
        void launch(List<ExperimentData> experiments,
                    int                  defaultMaxLag,
                    double               defaultInterval,
                    DataManagerWindow    dataManager);
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    private final JRadioButton  separateBtn, mergeBtn;
    private final JTextField    tagField, cutField;
    private final FileRowModel  model;
    private final JTable        table;
    private final JLabel        chosenLabel;

    /** ML-model options panel (segmentation feature only); null otherwise. */
    private final SegModelOptionsPanel mlOptions;

    /** Files picked via "Choose Files…", pending until "Load Data" is clicked. */
    private File[] chosenFiles = null;

    // ═════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═════════════════════════════════════════════════════════════════════════

    /** Data manager without the ML-model options row (e.g. Trajectory Analysis). */
    public DataManagerWindow(AnalysisLauncher launcher) {
        this(launcher, false);
    }

    /**
     * @param showMlOptions when {@code true}, shows an "ML model options" row
     *                      above Perform Analysis.
     */
    public DataManagerWindow(AnalysisLauncher launcher, boolean showMlOptions) {
        // The segmentation manager carries ML options; name the window after its
        // feature so the user can tell the two apart.
        super("AnDi-J – " + (showMlOptions ? "Segmentation" : "Analysis") + " – Manage Data");
        this.launcher = launcher;

        model = new FileRowModel();
        table = new JTable(model);
        configureTable();

        // ── load-options bar ─────────────────────────────────────────────
        separateBtn = new JRadioButton("Load as separate", true);
        mergeBtn    = new JRadioButton("Merge");
        ButtonGroup bg = new ButtonGroup();
        bg.add(separateBtn);
        bg.add(mergeBtn);

        tagField = new JTextField(10);
        tagField.setToolTipText(
            "Separate: leave blank to use filename; provide a name to group files under it.\n" +
            "Merge: leave blank for auto \"Exp N\"; provide a name to use it.");
        cutField = new JTextField(5);
        cutField.setToolTipText("Blank = no filtering");

        JButton chooseBtn = new JButton("Choose Files…");
        chooseBtn.setToolTipText("Pick the CSV files; then set Tag / Cut length and click Load Data");
        chooseBtn.addActionListener(e -> chooseFiles());

        chosenLabel = new JLabel("No files chosen");
        chosenLabel.setForeground(new Color(130, 130, 130));

        JButton loadBtn = new JButton("Load Data");
        loadBtn.addActionListener(e -> loadData());

        JPanel loadBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        loadBar.setBorder(BorderFactory.createTitledBorder("Load"));
        loadBar.add(separateBtn);
        loadBar.add(mergeBtn);
        loadBar.add(Box.createHorizontalStrut(8));
        loadBar.add(chooseBtn);
        loadBar.add(chosenLabel);
        loadBar.add(Box.createHorizontalStrut(8));
        loadBar.add(new JLabel("Tag:"));
        loadBar.add(tagField);
        loadBar.add(Box.createHorizontalStrut(4));
        loadBar.add(new JLabel("Cut length:"));
        loadBar.add(cutField);
        loadBar.add(Box.createHorizontalStrut(8));
        loadBar.add(loadBtn);

        // ── table ─────────────────────────────────────────────────────────
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder(
            "Loaded files  (double-click Tag or Cut length to edit)"));

        // ── bottom buttons ────────────────────────────────────────────────
        JButton removeBtn  = new JButton("Remove selected");
        JButton analyseBtn = new JButton("Perform Analysis");
        analyseBtn.setFont(analyseBtn.getFont().deriveFont(Font.BOLD));

        removeBtn.addActionListener(e  -> removeSelected());
        analyseBtn.addActionListener(e -> performAnalysis());

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        bottom.add(removeBtn);
        bottom.add(analyseBtn);

        // ── ML model options (segmentation feature only) ──────────────────
        mlOptions = showMlOptions ? new SegModelOptionsPanel() : null;

        JPanel south = new JPanel(new BorderLayout(0, 2));
        if (mlOptions != null) south.add(mlOptions, BorderLayout.NORTH);
        south.add(bottom, BorderLayout.SOUTH);

        // ── assemble ──────────────────────────────────────────────────────
        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setBorder(new EmptyBorder(6, 6, 6, 6));
        content.add(Toolbars.makeScrollable(loadBar), BorderLayout.NORTH);
        content.add(scroll,  BorderLayout.CENTER);
        content.add(south,   BorderLayout.SOUTH);
        setContentPane(content);

        setSize(860, 420);
        setLocationRelativeTo(null);
        // Hide instead of destroy so state is preserved
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════

    public void showWindow() {
        setVisible(true);
        toFront();
        requestFocus();
    }

    /** The segmentation ML-model selection, or the default when no options panel. */
    public SegModelConfig getSegModelConfig() {
        return mlOptions != null ? mlOptions.getConfig() : SegModelConfig.defaultModel();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Table configuration
    // ═════════════════════════════════════════════════════════════════════════

    private void configureTable() {
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);

        // Color swatch – fixed narrow column (no header text)
        table.getColumnModel().getColumn(0).setMinWidth(26);
        table.getColumnModel().getColumn(0).setMaxWidth(26);
        table.getColumnModel().getColumn(0).setCellRenderer(new ColorSwatchRenderer());

        // Tag (editable – default white background)
        table.getColumnModel().getColumn(1).setPreferredWidth(130);

        // File name (read-only → grey)
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setCellRenderer(new ReadOnlyRenderer(SwingConstants.LEFT));

        // Numeric: # trajs, min length, max length (read-only → grey + centered)
        for (int c : new int[]{3, 4, 5}) {
            table.getColumnModel().getColumn(c).setPreferredWidth(75);
            table.getColumnModel().getColumn(c).setCellRenderer(new ReadOnlyRenderer(SwingConstants.CENTER));
        }

        // Cut length (editable – default white background)
        table.getColumnModel().getColumn(6).setPreferredWidth(80);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Load data
    // ═════════════════════════════════════════════════════════════════════════

    /** Step 1 – pick the CSV files (does not add them yet). */
    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        chooser.setDialogTitle("Select CSV files");
        if (lastLoadDir != null && lastLoadDir.isDirectory())
            chooser.setCurrentDirectory(lastLoadDir);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) return;
        Arrays.sort(files, Comparator.comparing(File::getName));

        // Remember the folder for next time (shared across data managers)
        lastLoadDir = files[0].getParentFile();

        chosenFiles = files;
        // Keep the label no wider than the original "No files chosen" text so it
        // never pushes the Tag / Cut length / Load Data controls out of view.
        String text = files.length == 1
            ? truncateName(files[0].getName(), 10)
            : files.length + " files chosen";
        chosenLabel.setText(text);
        chosenLabel.setToolTipText(files.length == 1
            ? files[0].getName()
            : files.length + " files chosen");
        chosenLabel.setForeground(new Color(0, 120, 0));
    }

    /** Truncates a file name to {@code max} characters, adding an ellipsis. */
    private static String truncateName(String name, int max) {
        if (name.length() <= max) return name;
        return name.substring(0, Math.max(1, max - 1)) + "…";
    }

    /** Step 2 – add the chosen files with the current Tag / Cut length settings. */
    private void loadData() {
        if (chosenFiles == null || chosenFiles.length == 0) {
            JOptionPane.showMessageDialog(this,
                "No files chosen yet. Click \"Choose Files…\" first.",
                "Load Data", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        boolean merge  = mergeBtn.isSelected();
        String  tagTxt = tagField.getText().trim();
        int     cut    = parseCut(cutField.getText());

        if (merge) {
            String tag = tagTxt.isEmpty() ? nextExpTag() : tagTxt;
            ensureColor(tag);
            for (File f : chosenFiles) addFile(f, tag, cut);
        } else {
            for (File f : chosenFiles) {
                String tag = tagTxt.isEmpty()
                    ? f.getName().replaceFirst("\\.[^.]+$", "")
                    : tagTxt;
                ensureColor(tag);
                addFile(f, tag, cut);
            }
        }
        model.fireTableDataChanged();

        // Consume the selection so the next batch starts fresh
        chosenFiles = null;
        chosenLabel.setText("No files chosen");
        chosenLabel.setForeground(new Color(130, 130, 130));
    }

    private void addFile(File f, String tag, int cut) {
        try {
            TrajectoryIO.ParseResult parsed = TrajectoryIO.parseCsv(f);
            rows.add(new FileRow(f, parsed, tag, cut));
        } catch (IOException e) {
            IJ.error("AnDi-J – Load error",
                "Could not parse " + f.getName() + ":\n" + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Remove selected rows
    // ═════════════════════════════════════════════════════════════════════════

    private void removeSelected() {
        int[] sel = table.getSelectedRows();
        for (int i = sel.length - 1; i >= 0; i--) rows.remove(sel[i]);
        model.fireTableDataChanged();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Perform Analysis
    // ═════════════════════════════════════════════════════════════════════════

    private void performAnalysis() {
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No files loaded. Use \"Load Data…\" first.",
                "AnDi-J", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Group by tag, preserving first-occurrence order
        Map<String, List<FileRow>> byTag = new LinkedHashMap<>();
        for (FileRow row : rows)
            byTag.computeIfAbsent(row.tag, k -> new ArrayList<>()).add(row);

        List<ExperimentData> experiments = new ArrayList<>();

        for (Map.Entry<String, List<FileRow>> entry : byTag.entrySet()) {
            String        tag   = entry.getKey();
            Color         color = colorForTag(tag);
            List<FileRow> group = entry.getValue();

            Map<Integer, List<double[]>> allTracks = new LinkedHashMap<>();
            double estimatedInterval = group.get(0).estimatedInterval;
            int    idOffset = 0;

            for (FileRow row : group) {
                // Deep-copy so filters never corrupt the stored originals
                Map<Integer, List<double[]>> copy = deepCopy(row.originalTracks);
                if (row.cutLength > 0) applyTrajLengthFilter(copy, row.cutLength);
                for (Map.Entry<Integer, List<double[]>> te : copy.entrySet())
                    allTracks.put(te.getKey() + idOffset, te.getValue());
                idOffset += row.originalTracks.size() + 1;
            }

            if (allTracks.isEmpty()) continue;
            experiments.add(new ExperimentData(tag, allTracks, color, estimatedInterval));
        }

        if (experiments.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No tracks survive the length filters.",
                "AnDi-J", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // ── defaults ─────────────────────────────────────────────────────
        int maxLen = experiments.stream()
            .flatMap(e -> e.tracks.values().stream())
            .mapToInt(List::size).max().orElse(4);

        // If every row shares the same positive cut length, use max(cut-2, 4)
        int cutVal = uniformCutLength();
        int defaultMaxLag = (cutVal > 0)
            ? Math.max(cutVal - 2, 4)
            : Math.max(1, maxLen / 4);
        double defaultInterval = experiments.get(0).estimatedInterval;

        // ── delegate to injected launcher ────────────────────────────────
        final List<ExperimentData> exps     = experiments;
        final int                  maxLag   = defaultMaxLag;
        final double               interval = defaultInterval;
        final DataManagerWindow    self     = this;
        launcher.launch(exps, maxLag, interval, self);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns "Exp N" where N is one higher than the highest existing "Exp N" tag.
     * Scans current rows so the counter is always consistent even after removals.
     */
    private String nextExpTag() {
        int max = 0;
        for (FileRow r : rows) {
            if (r.tag.matches("Exp \\d+")) {
                try { max = Math.max(max, Integer.parseInt(r.tag.substring(4))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return "Exp " + (max + 1);
    }

    /** Ensures the tag has a palette color assigned; assigns the next one if not. */
    private void ensureColor(String tag) {
        tagColors.computeIfAbsent(tag, k ->
            TrajectoryAnalysisCommand.PALETTE[nextColorIdx++ % TrajectoryAnalysisCommand.PALETTE.length]);
    }

    Color colorForTag(String tag) {
        ensureColor(tag);
        return tagColors.get(tag);
    }

    private static int parseCut(String s) {
        try { int n = Integer.parseInt(s.trim()); return n >= 1 ? n : -1; }
        catch (NumberFormatException e) { return -1; }
    }

    /** Returns the shared cut length if every row has the same positive value; else -1. */
    private int uniformCutLength() {
        if (rows.isEmpty()) return -1;
        int first = rows.get(0).cutLength;
        if (first <= 0) return -1;
        for (FileRow r : rows) if (r.cutLength != first) return -1;
        return first;
    }

    private static Map<Integer, List<double[]>> deepCopy(
            Map<Integer, List<double[]>> src) {
        Map<Integer, List<double[]>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> e : src.entrySet())
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        return copy;
    }

    private static void applyTrajLengthFilter(
            Map<Integer, List<double[]>> tracks, int n) {
        Iterator<Map.Entry<Integer, List<double[]>>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<double[]>> e = it.next();
            List<double[]> spots = e.getValue();
            if (spots.size() < n) {
                it.remove();
            } else if (spots.size() > n) {
                spots.sort(Comparator.comparingDouble(s -> s[0]));
                e.setValue(new ArrayList<>(spots.subList(0, n)));
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Inner classes
    // ═════════════════════════════════════════════════════════════════════════

    /** One CSV file in the table, with its parsed data and display metadata. */
    static class FileRow {
        final File                           file;
        final Map<Integer, List<double[]>>   originalTracks;
        final double                         estimatedInterval;
        final int                            minLen, maxLen;
        String  tag;
        int     cutLength;  // -1 = no filter

        FileRow(File f, TrajectoryIO.ParseResult p, String tag, int cutLength) {
            this.file              = f;
            this.originalTracks   = p.tracks;
            this.estimatedInterval = p.estimatedInterval;
            this.tag               = tag;
            this.cutLength         = cutLength;
            int mn = Integer.MAX_VALUE, mx = 0;
            for (List<double[]> s : p.tracks.values()) {
                mn = Math.min(mn, s.size());
                mx = Math.max(mx, s.size());
            }
            this.minLen = p.tracks.isEmpty() ? 0 : mn;
            this.maxLen = mx;
        }
    }

    // -------------------------------------------------------------------------

    /** AbstractTableModel backed by the rows list. */
    private class FileRowModel extends AbstractTableModel {

        private final String[] COLS = {
            "", "Tag", "File Name", "# Trajs",
            "Min. length", "Max. length", "Cut length"
        };

        @Override public int    getRowCount()        { return rows.size(); }
        @Override public int    getColumnCount()     { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Class<?> getColumnClass(int c) {
            if (c == 0) return Color.class;
            if (c >= 3 && c <= 5) return Integer.class;
            return String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) { return c == 1 || c == 6; }

        @Override
        public Object getValueAt(int r, int c) {
            FileRow row = rows.get(r);
            switch (c) {
                case 0: return colorForTag(row.tag);
                case 1: return row.tag;
                case 2: return row.file.getName();
                case 3: return row.originalTracks.size();
                case 4: return row.minLen;
                case 5: return row.maxLen;
                case 6: return row.cutLength > 0 ? String.valueOf(row.cutLength) : "";
                default: return null;
            }
        }

        @Override
        public void setValueAt(Object val, int r, int c) {
            FileRow row = rows.get(r);
            if (c == 1) {
                String newTag = val.toString().trim();
                if (newTag.isEmpty())
                    newTag = row.file.getName().replaceFirst("\\.[^.]+$", "");
                row.tag = newTag;
                ensureColor(newTag);
                fireTableRowsUpdated(r, r);   // refresh colour swatch in col 0 too
            } else if (c == 6) {
                row.cutLength = parseCut(val.toString());
                fireTableCellUpdated(r, c);
            }
        }
    }

    // -------------------------------------------------------------------------

    /** Grey background for read-only (non-editable) cells. */
    private static class ReadOnlyRenderer extends DefaultTableCellRenderer {
        private static final Color READONLY_BG = new Color(240, 240, 240);
        ReadOnlyRenderer(int alignment) { setHorizontalAlignment(alignment); }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v,
                boolean sel, boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel) comp.setBackground(READONLY_BG);
            return comp;
        }
    }

    /** Renders a Color as a solid filled rectangle. */
    private static class ColorSwatchRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean sel, boolean focus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(
                t, "", sel, focus, row, col);
            if (value instanceof Color) {
                Color c = (Color) value;
                lbl.setBackground(sel ? c.darker() : c);
                lbl.setOpaque(true);
                lbl.setText("");
            }
            return lbl;
        }
    }
}
