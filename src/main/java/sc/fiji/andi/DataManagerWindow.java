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
 * Opened by AnDi-J > Trajectory Analysis and > Trajectory Segmentation (one
 * window per command) and by the "Manage Data" button of their windows.  State
 * (loaded files, tags, length filters) is preserved across show/hide cycles.
 *
 * Workflow:
 *   1. Click "Choose Files…" to pick one or more CSV files.
 *   2. Choose "Load as separate" or "Merge", optionally fill Tag / Min. length /
 *      Max. length, and click "Load Data" to add them to the table.
 *   3. Review the file table; edit Tag, Min. length or Max. length cells, remove rows.
 *   4. Click "Perform Analysis" → files that share a tag are merged into one
 *      ExperimentData, the length filters are applied per file, and the
 *      command's launcher runs (pre-computing tMSD / D / α, or the
 *      segmentation) and opens its window.
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
                    DataManagerWindow    dataManager);
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    private final JRadioButton  separateBtn, mergeBtn;
    private final JTextField    tagField, minLenField, maxLenField;
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
        minLenField = new JTextField(4);
        minLenField.setToolTipText(MIN_TIP);
        maxLenField = new JTextField(4);
        maxLenField.setToolTipText(MAX_TIP);

        JButton chooseBtn = new JButton("Choose Files…");
        chooseBtn.setToolTipText("Pick the CSV files; then set Tag / Min. length / Max. length and click Load Data");
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
        loadBar.add(new JLabel("Min. length:"));
        loadBar.add(minLenField);
        loadBar.add(new JLabel("Max. length:"));
        loadBar.add(maxLenField);
        loadBar.add(Box.createHorizontalStrut(8));
        loadBar.add(loadBtn);

        // ── table ─────────────────────────────────────────────────────────
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder(
            "Loaded files  (double-click Tag, Min. length, Max. length or the units to edit)"));

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

        // Length range of the file and trajectories kept / in the file (read-only → grey)
        table.getColumnModel().getColumn(3).setPreferredWidth(95);
        table.getColumnModel().getColumn(3).setCellRenderer(new ReadOnlyRenderer(SwingConstants.CENTER,
            "Shortest – longest trajectory in the file"));
        table.getColumnModel().getColumn(6).setPreferredWidth(95);
        table.getColumnModel().getColumn(6).setCellRenderer(new ReadOnlyRenderer(SwingConstants.CENTER,
            "Trajectories kept by Min. length / trajectories in the file"));

        // Length filters (editable – default white background)
        table.getColumnModel().getColumn(4).setPreferredWidth(80);
        table.getColumnModel().getColumn(5).setPreferredWidth(80);
        table.getColumnModel().getColumn(4).setCellRenderer(new EditableRenderer(MIN_TIP));
        table.getColumnModel().getColumn(5).setCellRenderer(new EditableRenderer(MAX_TIP));

        // Units (editable): μm per position unit, μs per frame; empty = unknown
        table.getColumnModel().getColumn(7).setPreferredWidth(100);
        table.getColumnModel().getColumn(8).setPreferredWidth(110);
        table.getColumnModel().getColumn(7).setCellRenderer(new EditableRenderer(
            "Size of one position unit in μm: 1 for data in μm, the pixel size for data in "
            + "pixels. Read from the CSV units row; empty when unknown."));
        table.getColumnModel().getColumn(8).setCellRenderer(new EditableRenderer(
            "Time between frames in μs. Read from POSITION_T and the CSV units row; empty "
            + "when unknown."));

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
        // never pushes the Tag / length / Load Data controls out of view.
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

    /** Step 2 – add the chosen files with the current Tag / length settings. */
    private void loadData() {
        if (chosenFiles == null || chosenFiles.length == 0) {
            JOptionPane.showMessageDialog(this,
                "No files chosen yet. Click \"Choose Files…\" first.",
                "Load Data", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        boolean merge  = mergeBtn.isSelected();
        String  tagTxt = tagField.getText().trim();
        int     minLen = parseLength(minLenField.getText());
        int     maxLen = parseLength(maxLenField.getText());
        if (!lengthsValid(minLen, maxLen)) return;

        if (merge) {
            String tag = tagTxt.isEmpty() ? nextExpTag() : tagTxt;
            ensureColor(tag);
            for (File f : chosenFiles) addFile(f, tag, minLen, maxLen);
        } else {
            for (File f : chosenFiles) {
                String tag = tagTxt.isEmpty()
                    ? f.getName().replaceFirst("\\.[^.]+$", "")
                    : tagTxt;
                ensureColor(tag);
                addFile(f, tag, minLen, maxLen);
            }
        }
        model.fireTableDataChanged();

        // Consume the selection so the next batch starts fresh
        chosenFiles = null;
        chosenLabel.setText("No files chosen");
        chosenLabel.setForeground(new Color(130, 130, 130));
    }

    private void addFile(File f, String tag, int minLen, int maxLen) {
        try {
            TrajectoryIO.ParseResult parsed = TrajectoryIO.parseCsv(f);
            rows.add(new FileRow(f, parsed, tag, minLen, maxLen));
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
            int    idOffset = 0;

            for (FileRow row : group) {
                // Deep-copy so filters never corrupt the stored originals
                Map<Integer, List<double[]>> copy = deepCopy(row.originalTracks);
                applyLengthFilters(copy, row.minLength, row.maxLength);
                for (Map.Entry<Integer, List<double[]>> te : copy.entrySet())
                    allTracks.put(te.getKey() + idOffset, te.getValue());
                idOffset += row.originalTracks.size() + 1;
            }

            if (allTracks.isEmpty()) continue;
            experiments.add(new ExperimentData(tag, allTracks, color,
                                               group.get(0).spaceUm, group.get(0).frameUs));
        }

        if (experiments.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No tracks survive the length filters.",
                "AnDi-J", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String unitProblem = unitProblem(byTag);
        if (unitProblem != null) {
            JOptionPane.showMessageDialog(this, unitProblem,
                "AnDi-J – Units", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // ── defaults ─────────────────────────────────────────────────────
        int maxLen = experiments.stream()
            .flatMap(e -> e.tracks.values().stream())
            .mapToInt(List::size).max().orElse(4);

        // Default max lag: a quarter of the longest trajectory
        int defaultMaxLag = Math.max(1, maxLen / 4);

        // ── delegate to injected launcher ────────────────────────────────
        launcher.launch(experiments, defaultMaxLag, this);
    }

    /**
     * Why the units of the loaded files cannot be analysed together, or null. Files
     * merged into one experiment must share their units, and the experiments must be
     * either all calibrated or all uncalibrated (separately for space and time):
     * otherwise values in μm and in pixels, or in seconds and in frames, would be
     * compared on the same axes.
     */
    private static String unitProblem(Map<String, List<FileRow>> byTag) {
        List<String> issues = new ArrayList<>();
        for (Map.Entry<String, List<FileRow>> e : byTag.entrySet()) {
            FileRow first = e.getValue().get(0);
            for (FileRow r : e.getValue())
                if (!sameValue(r.spaceUm, first.spaceUm) || !sameValue(r.frameUs, first.frameUs)) {
                    issues.add("The files merged into \"" + e.getKey() + "\" have different units.");
                    break;
                }
        }
        int withSpace = 0, withTime = 0, n = 0;
        for (List<FileRow> g : byTag.values())
            for (FileRow r : g) {
                n++;
                if (r.spaceUm != null) withSpace++;
                if (r.frameUs != null) withTime++;
            }
        if (withSpace > 0 && withSpace < n)
            issues.add("Some files have a spatial unit and others do not: " + missing(byTag, true) + ".");
        if (withTime > 0 && withTime < n)
            issues.add("Some files have a frame interval and others do not: " + missing(byTag, false) + ".");
        if (issues.isEmpty()) return null;
        return "<html>The files cannot be analysed together:<ul><li>"
             + String.join("</li><li>", issues) + "</li></ul>"
             + "Fill in the missing <b>Space unit (μm)</b> / <b>Frame interval (μs)</b> values in "
             + "the table, or clear them for all files to analyse in pixels and frames.<br>"
             + "α, turning angles, diffusion models and diffusive states do not depend on the "
             + "units, so any consistent choice works for them.</html>";
    }

    private static String missing(Map<String, List<FileRow>> byTag, boolean space) {
        List<String> names = new ArrayList<>();
        for (List<FileRow> g : byTag.values())
            for (FileRow r : g)
                if ((space ? r.spaceUm : r.frameUs) == null) names.add(r.file.getName());
        return names.size() <= 4 ? "missing in " + String.join(", ", names)
                                 : "missing in " + names.size() + " files";
    }

    private static boolean sameValue(Double a, Double b) {
        if (a == null || b == null) return a == b;
        return Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
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

    private static final String MIN_TIP =
        "Minimum length: trajectories with fewer frames are left out. Blank = no minimum";
    private static final String MAX_TIP =
        "Maximum length: longer trajectories are cut to their first N frames. Blank = no maximum";

    /** A length filter value: a positive integer, or -1 (blank or invalid = off). */
    private static int parseLength(String s) {
        try { int n = Integer.parseInt(s.trim()); return n >= 1 ? n : -1; }
        catch (NumberFormatException e) { return -1; }
    }

    /** False, after telling the user, when both filters are set and min > max. */
    private boolean lengthsValid(int minLen, int maxLen) {
        if (minLen < 0 || maxLen < 0 || minLen <= maxLen) return true;
        JOptionPane.showMessageDialog(this,
            "Min. length (" + minLen + ") is larger than Max. length (" + maxLen + ").",
            "Length filters", JOptionPane.WARNING_MESSAGE);
        return false;
    }

    private static Map<Integer, List<double[]>> deepCopy(
            Map<Integer, List<double[]>> src) {
        Map<Integer, List<double[]>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> e : src.entrySet())
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        return copy;
    }

    /** Drops trajectories shorter than minLen and cuts longer than maxLen to their
     *  first maxLen frames; -1 switches a filter off. */
    private static void applyLengthFilters(
            Map<Integer, List<double[]>> tracks, int minLen, int maxLen) {
        Iterator<Map.Entry<Integer, List<double[]>>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<double[]>> e = it.next();
            List<double[]> spots = e.getValue();
            if (minLen > 0 && spots.size() < minLen) {
                it.remove();
            } else if (maxLen > 0 && spots.size() > maxLen) {
                spots.sort(Comparator.comparingDouble(s -> s[0]));
                e.setValue(new ArrayList<>(spots.subList(0, maxLen)));
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
        final int                            minLen, maxLen;
        String  tag;
        int     minLength;  // -1 = no minimum
        int     maxLength;  // -1 = no maximum
        Double  spaceUm;    // μm per position unit; null = unknown
        Double  frameUs;    // μs per frame; null = unknown

        FileRow(File f, TrajectoryIO.ParseResult p, String tag, int minLength, int maxLength) {
            this.file              = f;
            this.originalTracks   = p.tracks;
            this.spaceUm           = p.spaceUm;
            this.frameUs           = p.frameUs;
            this.tag               = tag;
            this.minLength         = minLength;
            this.maxLength         = maxLength;
            int mn = Integer.MAX_VALUE, mx = 0;
            for (List<double[]> s : p.tracks.values()) {
                mn = Math.min(mn, s.size());
                mx = Math.max(mx, s.size());
            }
            this.minLen = p.tracks.isEmpty() ? 0 : mn;
            this.maxLen = mx;
        }

        /** Trajectories that pass the minimum length. */
        int kept() {
            if (minLength <= 0) return originalTracks.size();
            int n = 0;
            for (List<double[]> s : originalTracks.values()) if (s.size() >= minLength) n++;
            return n;
        }
    }

    // -------------------------------------------------------------------------

    /** AbstractTableModel backed by the rows list. */
    private class FileRowModel extends AbstractTableModel {

        private final String[] COLS = {
            "", "Tag", "File Name", "Length range",
            "Min. length", "Max. length", "Trajectories",
            "Space unit (μm)", "Frame interval (μs)"
        };

        @Override public int    getRowCount()        { return rows.size(); }
        @Override public int    getColumnCount()     { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Class<?> getColumnClass(int c) {
            if (c == 0) return Color.class;
            return String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) { return c == 1 || c == 4 || c == 5 || c >= 7; }

        @Override
        public Object getValueAt(int r, int c) {
            FileRow row = rows.get(r);
            switch (c) {
                case 0: return colorForTag(row.tag);
                case 1: return row.tag;
                case 2: return row.file.getName();
                case 3: return row.minLen + " – " + row.maxLen;
                case 4: return row.minLength > 0 ? String.valueOf(row.minLength) : "";
                case 5: return row.maxLength > 0 ? String.valueOf(row.maxLength) : "";
                case 6: return row.kept() + " / " + row.originalTracks.size();
                case 7: return fmtUnit(row.spaceUm);
                case 8: return fmtUnit(row.frameUs);
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
            } else if (c == 4 || c == 5) {
                int n = parseLength(val.toString());
                int minLen = c == 4 ? n : row.minLength, maxLen = c == 5 ? n : row.maxLength;
                if (!lengthsValid(minLen, maxLen)) return;
                row.minLength = minLen;
                row.maxLength = maxLen;
                fireTableRowsUpdated(r, r);   // Trajectories follows Min. length
            } else if (c == 7) {
                row.spaceUm = parseUnit(val.toString());
                fireTableCellUpdated(r, c);
            } else if (c == 8) {
                row.frameUs = parseUnit(val.toString());
                fireTableCellUpdated(r, c);
            }
        }
    }

    // -------------------------------------------------------------------------

    /** A positive unit value, or null (blank, zero or not a number). */
    private static Double parseUnit(String s) {
        try { double v = Double.parseDouble(s.trim()); return v > 0 && Double.isFinite(v) ? v : null; }
        catch (NumberFormatException e) { return null; }
    }

    private static String fmtUnit(Double v) {
        if (v == null) return "";
        return String.format(Locale.ROOT, "%.6g", v).replaceAll("\\.?0+$", "").replaceAll("\\.?0+(e)", "$1");
    }

    /** Editable, centred cell with its meaning as tooltip. */
    private static class EditableRenderer extends DefaultTableCellRenderer {
        private final String tip;
        EditableRenderer(String tip) { this.tip = tip; setHorizontalAlignment(SwingConstants.CENTER); }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v,
                boolean sel, boolean focus, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            l.setToolTipText(tip);
            return l;
        }
    }

    /** Grey background for read-only (non-editable) cells. */
    private static class ReadOnlyRenderer extends DefaultTableCellRenderer {
        private static final Color READONLY_BG = new Color(240, 240, 240);
        private final String tip;
        ReadOnlyRenderer(int alignment) { this(alignment, null); }
        ReadOnlyRenderer(int alignment, String tip) { setHorizontalAlignment(alignment); this.tip = tip; }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v,
                boolean sel, boolean focus, int r, int c) {
            JLabel comp = (JLabel) super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel) comp.setBackground(READONLY_BG);
            comp.setToolTipText(tip);
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
