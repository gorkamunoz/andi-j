package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * "D vs α" tab.
 *
 * One point per trajectory in a 2-D scatter plot:
 *   X axis → anomalous exponent α   (from the Anomalous exponent tab)
 *   Y axis → diffusion coefficient D (from the Diffusion coefficient tab)
 *
 * <p>D and α values are read directly from the pre-computed
 * {@link ExperimentData#dCoeffs} and {@link ExperimentData#alphaCoeffs}.</p>
 *
 * <h3>Population tagging</h3>
 * In <b>Select</b> mode the user circles (lassos) a region of the scatter;
 * the enclosed trajectories can then be tagged as a named population via the
 * "Population tag:" row at the bottom.  Tagged points are recoloured
 * to a lighter shade of their parent experiment's colour, get their own
 * marginal-KDE curve, and are pushed to the Turning Angles and Diffusion Model
 * tabs as a new dataset. The <b>Save</b> column of the population table writes a
 * population's trajectories to a CSV that the Data Manager can load again
 * ({@link #savePopulationCsv}).
 *
 * <p>Marginal KDE strips along the top (α) and right (D) edges track zoom/pan
 * and are recomputed every repaint, so tagged populations appear automatically.</p>
 */
public class FullAnalysisPanel extends JPanel {

    private final List<ExperimentData>              experiments;
    private final Map<ExperimentData, Set<Integer>> componentSelection = new LinkedHashMap<>();
    private final List<JToggleButton>               allCompButtons     = new ArrayList<>();

    /** Committed tagged populations (shared with the scatter panel). */
    private final List<Population> populations = new ArrayList<>();
    /** How many populations already created per parent – used to pick distinct shades. */
    private final Map<ExperimentData, Integer> popCountByParent = new LinkedHashMap<>();

    /** Sink that receives each new population as an ExperimentData (Angles + Diffusion tabs). */
    private final Consumer<ExperimentData> populationSink;

    private final ScatterPanel scatterPanel;
    private final JTextField   tagField;
    private final JLabel       selStatus;
    private final PopulationTableModel populationModel = new PopulationTableModel();
    private JSplitPane popSplit;
    private boolean    popSplitInited = false;

    // -----------------------------------------------------------------------

    FullAnalysisPanel(List<ExperimentData> experiments, Units units,
                      Consumer<ExperimentData> populationSink) {
        super(new BorderLayout(0, 4));
        this.experiments    = experiments;
        this.populationSink = populationSink;
        setBorder(new EmptyBorder(6, 6, 6, 6));

        for (ExperimentData exp : experiments) {
            Set<Integer> s = new LinkedHashSet<>();
            s.add(0);
            componentSelection.put(exp, s);
        }

        // ── info message ─────────────────────────────────────────────────
        JLabel infoLabel = new JLabel(
            "<html><i>D and α are taken from the last computation in their respective tabs. " +
            "To change lags, frame interval, or fitting method, use the " +
            "'Diffusion coefficient' and 'Anomalous exponent' tabs.</i></html>");
        infoLabel.setBorder(new EmptyBorder(2, 4, 4, 4));
        infoLabel.setForeground(new Color(80, 80, 80));
        add(infoLabel, BorderLayout.NORTH);

        // ── scatter canvas ───────────────────────────────────────────────
        scatterPanel = new ScatterPanel(experiments, componentSelection, populations, units);
        units.addListener(() -> {
            scatterPanel.clearPending(); updateSelStatus();
            scatterPanel.resetViewport(); scatterPanel.repaint();
        });
        scatterPanel.setSelectionListener(this::updateSelStatus);

        // ── view toolbar ─────────────────────────────────────────────────
        JToggleButton zoomBtn      = new JToggleButton("⊕ Zoom",  true);
        JToggleButton panBtn       = new JToggleButton("✥ Pan",   false);
        JToggleButton selectBtn    = new JToggleButton("◌ Select", false);
        JButton       resetViewBtn = new JButton("↺ Reset view");
        JCheckBox     logYBox      = new JCheckBox("Log D axis", true);
        ButtonGroup   bg           = new ButtonGroup();
        bg.add(zoomBtn); bg.add(panBtn); bg.add(selectBtn);
        for (AbstractButton b : new AbstractButton[]{zoomBtn, panBtn, selectBtn, resetViewBtn, logYBox}) {
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            b.setFocusPainted(false);
        }
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolbar.add(zoomBtn); toolbar.add(panBtn); toolbar.add(selectBtn);
        toolbar.add(resetViewBtn);
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(logYBox);
        toolbar.add(Box.createHorizontalStrut(8));
        toolbar.add(units.button());

        zoomBtn  .addActionListener(e -> { scatterPanel.setPanMode(false); scatterPanel.setSelectMode(false); });
        panBtn   .addActionListener(e -> scatterPanel.setPanMode(true));
        selectBtn.addActionListener(e -> scatterPanel.setSelectMode(true));
        resetViewBtn.addActionListener(e -> { scatterPanel.resetViewport(); scatterPanel.repaint(); });
        logYBox  .addActionListener(e -> { scatterPanel.setLogY(logYBox.isSelected()); scatterPanel.repaint(); });

        JPanel centerArea = new JPanel(new BorderLayout(0, 2));
        toolbar.add(SvgExport.button(() -> scatterPanel, "d_vs_alpha"));
        centerArea.add(Toolbars.makeScrollable(toolbar), BorderLayout.NORTH);
        centerArea.add(scatterPanel, BorderLayout.CENTER);

        // ── left experiment selector ──────────────────────────────────────
        JScrollPane leftScroll = new JScrollPane(buildExperimentsPanel());
        leftScroll.setPreferredSize(new Dimension(170, 0));
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, centerArea);
        split.setDividerLocation(180);
        split.setResizeWeight(0.0);

        // ── tag-population row (always visible) ────────────────────────────
        JPanel tagRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        tagRow.add(new JLabel("Population tag:"));
        tagField = new JTextField(12);
        tagRow.add(tagField);
        JButton tagBtn = new JButton("Tag population");
        tagBtn.setToolTipText("Tag the circled trajectories as a named population "
            + "(Select mode). This does not write a file.");
        tagRow.add(tagBtn);
        selStatus = new JLabel(" ");
        selStatus.setForeground(new Color(90, 90, 90));
        tagRow.add(selStatus);

        tagBtn.addActionListener(e -> savePopulation());
        tagField.addActionListener(e -> savePopulation());

        JPanel mainArea = new JPanel(new BorderLayout(0, 4));
        mainArea.add(split,  BorderLayout.CENTER);
        mainArea.add(tagRow, BorderLayout.SOUTH);

        // ── collapsible population-management table (hidden until first tag) ─
        JTable popTable = buildPopulationTable();
        JScrollPane tableScroll = new JScrollPane(popTable);
        JPanel tableWrap = new JPanel(new BorderLayout());
        tableWrap.setBorder(BorderFactory.createTitledBorder(
            "Populations  (click a colour to change it)"));
        tableWrap.add(tableScroll, BorderLayout.CENTER);
        tableWrap.setMinimumSize(new Dimension(0, 0));

        popSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainArea, tableWrap);
        popSplit.setOneTouchExpandable(true);
        popSplit.setResizeWeight(1.0);
        popSplit.setBorder(null);
        add(popSplit, BorderLayout.CENTER);

        // Start collapsed; expand automatically once a population exists.
        popSplit.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                    && popSplit.isShowing() && !popSplitInited) {
                popSplitInited = true;
                if (populations.isEmpty()) collapseTable(); else expandTable();
            }
        });

        if (experiments.stream().anyMatch(exp -> exp.hasD() && exp.hasAlpha()))
            scatterPanel.repaint();
    }

    /** Collapse the population table (divider to the very bottom). */
    private void collapseTable() {
        SwingUtilities.invokeLater(() -> popSplit.setDividerLocation(1.0));
    }

    /** Reveal the population table with a small fixed height. */
    private void expandTable() {
        SwingUtilities.invokeLater(() -> {
            int h = popSplit.getHeight();
            popSplit.setDividerLocation(Math.max(80, h - 150));
        });
    }

    // -----------------------------------------------------------------------
    // Population management table
    // -----------------------------------------------------------------------

    private JTable buildPopulationTable() {
        JTable table = new JTable(populationModel);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(120); // Tag
        table.getColumnModel().getColumn(1).setPreferredWidth(220); // Datasets
        table.getColumnModel().getColumn(2).setMaxWidth(60);        // Color
        table.getColumnModel().getColumn(2).setCellRenderer(new ColorSwatchRenderer());
        table.getColumnModel().getColumn(3).setMaxWidth(70);        // Save
        table.getColumnModel().getColumn(3).setCellRenderer(new SaveCellRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || row >= populations.size()) return;
                Population p = populations.get(row);
                if (col == 3) { savePopulationCsv(p); return; }
                if (col != 2) return;
                Color c = chooseSwatchColor(p.color, "Population colour for \"" + p.tag + "\"");
                if (c != null) {
                    p.color = c;
                    populationModel.fireTableRowsUpdated(row, row);
                    scatterPanel.repaint();
                }
            }
        });
        return table;
    }

    /**
     * A stripped-down colour picker: only the Swatches palette, no preview
     * panel and no RGB/HSB/CMYK tabs.
     */
    private Color chooseSwatchColor(Color initial, String title) {
        final JColorChooser cc = new JColorChooser(initial != null ? initial : Color.GRAY);
        // Keep only the Swatches palette
        for (AbstractColorChooserPanel panel : cc.getChooserPanels())
            if (!panel.getClass().getSimpleName().toLowerCase().contains("swatch"))
                cc.removeChooserPanel(panel);
        // Drop the live preview strip
        cc.setPreviewPanel(new JPanel());

        final Color[] result = {null};
        JDialog dlg = JColorChooser.createDialog(
            this, title, true, cc,
            e -> result[0] = cc.getColor(),   // OK
            null);                            // Cancel → leaves result null
        dlg.setVisible(true);
        return result[0];
    }

    /** Table model backed by the live {@link #populations} list. */
    private class PopulationTableModel extends AbstractTableModel {
        private final String[] COLS = {"Tag", "Datasets", "Colour", "Save"};
        @Override public int    getRowCount()        { return populations.size(); }
        @Override public int    getColumnCount()     { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 2 ? Color.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return false; }
        @Override public Object getValueAt(int r, int c) {
            Population p = populations.get(r);
            switch (c) {
                case 0:  return p.tag;
                case 1:  return p.datasetsLabel();
                case 2:  return p.color;
                case 3:  return "Save…";
                default: return null;
            }
        }
    }

    /** Renders a Color cell as a filled swatch. */
    private static class ColorSwatchRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean sel, boolean focus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(t, "", sel, focus, row, col);
            if (value instanceof Color) {
                lbl.setBackground((Color) value);
                lbl.setOpaque(true);
                lbl.setToolTipText("Click to change colour");
            }
            return lbl;
        }
    }

    /** Renders the Save cell as a link-like label. */
    private static class SaveCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean sel, boolean focus, int row, int col) {
            JLabel lbl = (JLabel) super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            if (!sel) lbl.setForeground(new Color(0, 90, 200));
            lbl.setToolTipText("Save this population's trajectories as a CSV");
            return lbl;
        }
    }

    // -----------------------------------------------------------------------
    // Population export
    // -----------------------------------------------------------------------

    private static File lastSaveDir = null;

    /**
     * Writes the population's trajectories as a TrackMate-style CSV that the Data
     * Manager loads again: a header row ({@code TRACK_ID, POSITION_X, POSITION_Y,
     * POSITION_T, FRAME, EXPERIMENT}), two label rows and a units row, then one row
     * per spot. With a calibration, positions are written in μm and {@code POSITION_T}
     * in seconds (units row {@code (micron)}, {@code (sec)}), so the file loads back
     * calibrated; without one, positions stay in their input unit {@code (pixel)} and
     * {@code POSITION_T} is the frame {@code (frame)}. {@code TRACK_ID} keeps the experiment's id when the population comes
     * from one experiment and is renumbered from 0 otherwise, so ids stay unique.
     * Commas in experiment names are written as semicolons.
     */
    private void savePopulationCsv(Population pop) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle("Save population \"" + pop.tag + "\"");
        if (lastSaveDir != null && lastSaveDir.isDirectory()) fc.setCurrentDirectory(lastSaveDir);
        fc.setSelectedFile(new File(pop.tag.replaceAll("[^A-Za-z0-9._-]+", "_") + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        lastSaveDir = f.getParentFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");

        try {
            writePopulationCsv(pop, f);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(),
                "Save population", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Writes {@code pop} to {@code f} in the format of {@link #savePopulationCsv}. */
    static void writePopulationCsv(Population pop, File f) throws IOException {
        boolean single = pop.members.size() == 1;
        int nextId = 0;
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            pw.println("TRACK_ID,POSITION_X,POSITION_Y,POSITION_T,FRAME,EXPERIMENT");
            pw.println("Track ID,X,Y,T,Frame,Experiment");
            pw.println("Track ID,X,Y,T,Frame,Experiment");
            ExperimentData first = pop.members.keySet().iterator().next();
            String xyUnit = first.spaceUm != null ? "(micron)" : "(pixel)";
            String tUnit  = first.frameUs != null ? "(sec)"    : "(frame)";
            pw.println("," + xyUnit + "," + xyUnit + "," + tUnit + ",,");
            for (Map.Entry<ExperimentData, Set<Integer>> e : pop.members.entrySet()) {
                ExperimentData exp = e.getKey();
                double xyScale = exp.spaceUm != null ? exp.spaceUm : 1.0;
                String expName = exp.name.replace(',', ';');
                for (Integer id : e.getValue()) {
                    List<double[]> tr = exp.tracks.get(id);
                    if (tr == null) continue;
                    int outId = single ? id : nextId++;
                    for (double[] s : tr) {
                        long frame = Math.round(s[0]);
                        double t = exp.frameUs != null ? frame * exp.frameUs * 1e-6 : frame;
                        pw.println(outId + "," + (s[1] * xyScale) + "," + (s[2] * xyScale) + ","
                                 + t + "," + frame + "," + expName);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Population tagging
    // -----------------------------------------------------------------------

    private void updateSelStatus() {
        int n = 0;
        for (Set<Integer> s : scatterPanel.getPendingSelection().values()) n += s.size();
        selStatus.setText(n == 0 ? " " : "(" + n + " trajectories selected — enter a tag and Save)");
    }

    private void savePopulation() {
        Map<ExperimentData, Set<Integer>> sel = scatterPanel.getPendingSelection();
        int n = 0;
        for (Set<Integer> s : sel.values()) n += s.size();
        if (n == 0) {
            JOptionPane.showMessageDialog(this,
                "Switch to Select mode and circle some points first.",
                "Save population", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String tag = tagField.getText().trim();
        if (tag.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Enter a tag name for this population.",
                "Tag population", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // One tag per trajectory: drop these ids from any earlier population
        for (Population p : populations)
            for (Map.Entry<ExperimentData, Set<Integer>> e : sel.entrySet())
                if (p.members.containsKey(e.getKey()))
                    p.members.get(e.getKey()).removeAll(e.getValue());

        Map<ExperimentData, Set<Integer>> members = new LinkedHashMap<>();
        for (Map.Entry<ExperimentData, Set<Integer>> e : sel.entrySet())
            if (!e.getValue().isEmpty())
                members.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        if (members.isEmpty()) return;

        // Initial colour: a hue-preserving shade of the first parent's colour
        ExperimentData firstParent = members.keySet().iterator().next();
        int k = popCountByParent.merge(firstParent, 1, Integer::sum) - 1;
        Population pop = new Population(tag, members, tint(firstParent.color, k));
        populations.add(pop);
        populationModel.fireTableDataChanged();
        expandTable();    // reveal the management table on first tag

        scatterPanel.clearPending();
        tagField.setText("");
        updateSelStatus();
        scatterPanel.repaint();

        if (populationSink != null)
            populationSink.accept(buildPopulationDataset(pop));
    }

    /** Builds a standalone ExperimentData subset for the Angles / Diffusion tabs. */
    private static ExperimentData buildPopulationDataset(Population pop) {
        Map<Integer, List<double[]>> tracks = new LinkedHashMap<>();
        Map<Integer, Integer>        diff   = new LinkedHashMap<>();
        boolean single = pop.members.size() == 1;
        int nextId = 0;
        ExperimentData firstParent = pop.members.keySet().iterator().next();

        for (Map.Entry<ExperimentData, Set<Integer>> e : pop.members.entrySet()) {
            ExperimentData parent = e.getKey();
            for (Integer id : e.getValue()) {
                List<double[]> tr = parent.tracks.get(id);
                if (tr == null) continue;
                int newId = single ? id : nextId++;
                tracks.put(newId, tr);   // shared reference – read-only downstream
                if (parent.diffModel != null && parent.diffModel.containsKey(id))
                    diff.put(newId, parent.diffModel.get(id));
            }
        }

        ExperimentData ds = new ExperimentData(pop.tag, tracks, pop.color,
                                               firstParent.spaceUm, firstParent.frameUs);
        if (!diff.isEmpty()) ds.diffModel = diff;
        return ds;
    }

    /** A lighter, hue-preserving shade of {@code base} for population index {@code k}. */
    static Color tint(Color base, int k) {
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float sat = Math.max(0f, hsb[1] * (1f - 0.20f * (k + 1)));
        float bri = Math.min(1f, hsb[2] + (1f - hsb[2]) * (0.35f + 0.12f * k));
        return Color.getHSBColor(hsb[0], sat, bri);
    }

    // -----------------------------------------------------------------------
    // Left panel
    // -----------------------------------------------------------------------

    private JPanel buildExperimentsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        JButton deselectAll = new JButton("Deselect all");
        deselectAll.setFont(deselectAll.getFont().deriveFont(Font.PLAIN, 10f));
        deselectAll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        deselectAll.setFocusPainted(false);
        deselectAll.addActionListener(e -> {
            for (ExperimentData exp : experiments) componentSelection.get(exp).clear();
            for (JToggleButton btn : allCompButtons) btn.setSelected(false);
            scatterPanel.resetViewport();
            scatterPanel.repaint();
        });
        panel.add(deselectAll);
        panel.add(Box.createVerticalStrut(6));

        for (ExperimentData exp : experiments) {
            panel.add(makeExpRow(exp));
            panel.add(Box.createVerticalStrut(6));
        }
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel makeExpRow(ExperimentData exp) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JPanel swatch  = new JPanel();
        swatch.setBackground(exp.color);
        swatch.setPreferredSize(new Dimension(12, 12));
        swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        nameRow.add(swatch);
        JLabel lbl = new JLabel(exp.name);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 11f));
        nameRow.add(lbl);
        row.add(nameRow);

        JToggleButton btn2D = new JToggleButton("2D", true);
        JToggleButton btnX  = new JToggleButton("X",  false);
        JToggleButton btnY  = new JToggleButton("Y",  false);
        styleToggle(btn2D); styleToggle(btnX); styleToggle(btnY);

        Set<Integer> sel = componentSelection.get(exp);
        btn2D.addActionListener(e -> { if (btn2D.isSelected()) sel.add(0); else sel.remove(0); scatterPanel.resetViewport(); scatterPanel.repaint(); });
        btnX .addActionListener(e -> { if (btnX .isSelected()) sel.add(1); else sel.remove(1); scatterPanel.resetViewport(); scatterPanel.repaint(); });
        btnY .addActionListener(e -> { if (btnY .isSelected()) sel.add(2); else sel.remove(2); scatterPanel.resetViewport(); scatterPanel.repaint(); });
        allCompButtons.add(btn2D); allCompButtons.add(btnX); allCompButtons.add(btnY);

        JPanel compRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        compRow.add(btn2D); compRow.add(btnX); compRow.add(btnY);
        row.add(compRow);
        return row;
    }

    private static void styleToggle(JToggleButton btn) {
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 10f));
        btn.setPreferredSize(new Dimension(38, 18));
        btn.setMargin(new Insets(1, 2, 1, 2));
        btn.setFocusPainted(false);
    }

    // =======================================================================
    // Population model
    // =======================================================================

    /** A user-tagged group of trajectories, possibly spanning several parents. */
    static class Population {
        final String                          tag;
        final Map<ExperimentData, Set<Integer>> members;  // parent → trackIds
        Color                                 color;      // display colour (editable)

        Population(String tag, Map<ExperimentData, Set<Integer>> members, Color color) {
            this.tag = tag; this.members = members; this.color = color;
        }

        /** Comma-joined names of the parent datasets this population draws from. */
        String datasetsLabel() {
            StringBuilder sb = new StringBuilder();
            for (ExperimentData e : members.keySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.name);
            }
            return sb.toString();
        }
    }

    // =======================================================================
    // Scatter-plot canvas
    // =======================================================================

    static class ScatterPanel extends JPanel {

        private final List<ExperimentData>              experiments;
        private final Map<ExperimentData, Set<Integer>> componentSel;
        private final List<Population>                  populations;

        private boolean logY = true;

        // Plot margins
        private static final int ML = 72;   // left  (Y-axis labels)
        private static final int MB = 50;   // bottom (X-axis labels + title)
        private static final int MT = 72;   // top   (alpha marginal strip)
        private static final int MR = 80;   // right (D marginal strip)

        private static final int MARG_H = 60; // alpha marginal height
        private static final int MARG_W = 62; // D marginal width

        // viewport
        private double[] viewport = null;

        // saved render bounds (mouse ↔ data transforms)
        private double renderXMin = 0, renderXMax = 2;
        private double renderYMin = 1e-4, renderYMax = 1;
        private int    renderPx = ML, renderPy = MT, renderPw = 400, renderPh = 300;

        // modes
        private boolean panMode    = false;
        private boolean selectMode = false;

        // zoom / pan
        private boolean dragging = false;
        private int     dragX0, dragY0, dragX1, dragY1;
        private boolean panning  = false;
        private int     panX0, panY0;
        private double  panInitXMin, panInitXMax, panInitYMin, panInitYMax;

        // lasso selection
        private boolean        selecting = false;
        private List<double[]> lassoPts  = null;   // pixel points
        private final Map<ExperimentData, Set<Integer>> pendingSel = new LinkedHashMap<>();
        private Runnable selectionListener;

        private List<Series> lastSeries = new ArrayList<>();

        private int hoverPx = -1, hoverPy = -1;
        private static final int PT_R = 4;

        // Movable legend: null = default (top-right); otherwise top-left corner in px.
        private Integer legendX = null, legendY = null;
        private int     legX, legY, legW, legH;      // bounds from last paint (hit-test)
        private boolean draggingLegend = false;
        private int     legGrabDX, legGrabDY;

        // ── One series ───────────────────────────────────────────────────
        private static class Series {
            final ExperimentData parentExp;  // underlying experiment (selection + lookup)
            final Object         groupKey;   // exp (untagged) or Population (tagged)
            final String         label;      // legend
            final int            comp;
            final Color          color;
            final List<double[]> pts = new ArrayList<>(); // {alpha, D, id}
            Series(ExperimentData parentExp, Object groupKey, String label, int comp, Color color) {
                this.parentExp = parentExp; this.groupKey = groupKey;
                this.label = label; this.comp = comp; this.color = color;
            }
        }

        /** Accumulates per-group values for the marginal KDEs. */
        private static class MarginGroup {
            final Color color;
            final List<Double> alphas = new ArrayList<>();
            final List<Double> ds     = new ArrayList<>();
            MarginGroup(Color c) { this.color = c; }
        }

        // ────────────────────────────────────────────────────────────────

        /** Unit of the x, y positions, for the D axis label. */
        private final Units units;

        ScatterPanel(List<ExperimentData> exps,
                     Map<ExperimentData, Set<Integer>> sel,
                     List<Population> populations, Units units) {
            this.experiments = exps;
            this.units       = units;
            this.componentSel = sel;
            this.populations  = populations;
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(600, 460));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseInteraction();
        }

        void setLogY(boolean on)   { if (logY != on) { logY = on; viewport = null; } }
        void resetViewport()       { viewport = null; }
        void setSelectionListener(Runnable r) { this.selectionListener = r; }

        Map<ExperimentData, Set<Integer>> getPendingSelection() { return pendingSel; }
        void clearPending() { pendingSel.clear(); lassoPts = null; }

        void setPanMode(boolean pan) {
            panMode = pan;
            if (pan) selectMode = false;
            updateCursor();
        }
        void setSelectMode(boolean sel) {
            selectMode = sel;
            if (sel) panMode = false;
            updateCursor();
        }
        /** True if (x,y) is inside the last-painted legend box. */
        private boolean overLegend(int x, int y) {
            return legW > 0 && x >= legX && x <= legX + legW
                            && y >= legY && y <= legY + legH;
        }

        private void updateCursor() {
            setCursor(Cursor.getPredefinedCursor(
                panMode ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR));
        }

        // ── Mouse ─────────────────────────────────────────────────────────

        private void addMouseInteraction() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (overLegend(e.getX(), e.getY())) {   // drag the legend (any mode)
                        draggingLegend = true;
                        legGrabDX = e.getX() - legX;
                        legGrabDY = e.getY() - legY;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    } else if (selectMode) {
                        selecting = true;
                        pendingSel.clear();
                        lassoPts = new ArrayList<>();
                        lassoPts.add(new double[]{e.getX(), e.getY()});
                    } else if (panMode) {
                        panning = true;
                        panX0 = e.getX(); panY0 = e.getY();
                        panInitXMin = renderXMin; panInitXMax = renderXMax;
                        panInitYMin = renderYMin; panInitYMax = renderYMax;
                    } else {
                        dragging = true;
                        dragX0 = dragX1 = clamp(e.getX(), renderPx, renderPx + renderPw);
                        dragY0 = dragY1 = clamp(e.getY(), renderPy, renderPy + renderPh);
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (draggingLegend) {
                        legendX = e.getX() - legGrabDX;
                        legendY = e.getY() - legGrabDY;
                        repaint();
                    } else if (selecting) {
                        lassoPts.add(new double[]{e.getX(), e.getY()});
                        repaint();
                    } else if (dragging) {
                        dragX1 = clamp(e.getX(), renderPx, renderPx + renderPw);
                        dragY1 = clamp(e.getY(), renderPy, renderPy + renderPh);
                        repaint();
                    } else if (panning && renderPw > 0 && renderPh > 0) {
                        double dx = (double)(e.getX() - panX0) / renderPw
                                    * (panInitXMax - panInitXMin);
                        double ny0, ny1;
                        if (logY) {
                            double lMin = safeLog(panInitYMin), lMax = safeLog(panInitYMax);
                            double dy = (double)(e.getY() - panY0) / renderPh * (lMax - lMin);
                            ny0 = Math.pow(10, lMin + dy); ny1 = Math.pow(10, lMax + dy);
                        } else {
                            double dy = (double)(e.getY() - panY0) / renderPh
                                        * (panInitYMax - panInitYMin);
                            ny0 = panInitYMin + dy; ny1 = panInitYMax + dy;
                        }
                        viewport = new double[]{panInitXMin - dx, panInitXMax - dx, ny0, ny1};
                        repaint();
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (draggingLegend) {
                        draggingLegend = false;
                        updateCursor();
                        return;
                    }
                    if (selecting) {
                        selecting = false;
                        computeLassoSelection();
                        if (selectionListener != null) selectionListener.run();
                        repaint();
                    } else if (dragging) {
                        dragging = false;
                        int x0 = Math.min(dragX0, dragX1), x1 = Math.max(dragX0, dragX1);
                        int y0 = Math.min(dragY0, dragY1), y1 = Math.max(dragY0, dragY1);
                        if (x1 - x0 > 5 && y1 - y0 > 5) {
                            double dxMin = pxToDataX(x0), dxMax = pxToDataX(x1);
                            double dyMax = pxToDataY(y0), dyMin = pxToDataY(y1);
                            if (dxMin < dxMax && dyMax > dyMin)
                                viewport = new double[]{dxMin, dxMax, dyMin, dyMax};
                        }
                        repaint();
                    } else if (panning) { panning = false; }
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && !selectMode) { viewport = null; repaint(); }
                }
                @Override public void mouseMoved(MouseEvent e) {
                    hoverPx = e.getX(); hoverPy = e.getY();
                    if (overLegend(e.getX(), e.getY()))
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    else
                        updateCursor();
                    repaint();
                }
                @Override public void mouseExited(MouseEvent e) {
                    hoverPx = -1; repaint();
                }
            };
            addMouseListener(ma); addMouseMotionListener(ma);
        }

        private void computeLassoSelection() {
            if (lassoPts == null || lassoPts.size() < 3) return;
            Path2D path = new Path2D.Double();
            path.moveTo(lassoPts.get(0)[0], lassoPts.get(0)[1]);
            for (int i = 1; i < lassoPts.size(); i++)
                path.lineTo(lassoPts.get(i)[0], lassoPts.get(i)[1]);
            path.closePath();

            for (Series s : lastSeries) {
                for (double[] pt : s.pts) {
                    int cx = mapX(pt[0], renderXMin, renderXMax, renderPx, renderPw);
                    int cy = mapY(pt[1], renderYMin, renderYMax, renderPy, renderPh);
                    if (path.contains(cx, cy))
                        pendingSel.computeIfAbsent(s.parentExp, k -> new LinkedHashSet<>())
                                  .add((int) pt[2]);
                }
            }
        }

        private double pxToDataX(int px) {
            if (renderPw <= 0) return renderXMin;
            return renderXMin + (double)(px - renderPx) / renderPw * (renderXMax - renderXMin);
        }
        private double pxToDataY(int py) {
            double frac = (double)(renderPy + renderPh - py) / renderPh;
            if (logY) {
                double lMin = safeLog(renderYMin), lMax = safeLog(renderYMax);
                return Math.pow(10, lMin + frac * (lMax - lMin));
            }
            return renderYMin + frac * (renderYMax - renderYMin);
        }
        private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

        // ── Series construction ────────────────────────────────────────────

        /** trackIds of {@code exp} that belong to any committed population. */
        private Set<Integer> taggedIds(ExperimentData exp) {
            Set<Integer> out = new HashSet<>();
            for (Population p : populations) {
                Set<Integer> m = p.members.get(exp);
                if (m != null) out.addAll(m);
            }
            return out;
        }

        private List<Series> buildSeries() {
            List<Series> seriesList = new ArrayList<>();

            // Parent experiments (untagged points only)
            for (ExperimentData exp : experiments) {
                if (!exp.hasD() || !exp.hasAlpha()) continue;
                Set<Integer> tagged = taggedIds(exp);
                for (int comp : componentSel.getOrDefault(exp, Collections.emptySet())) {
                    Series s = new Series(exp, exp, exp.name, comp, compColor(exp.color, comp));
                    for (Integer id : exp.dCoeffs.keySet()) {
                        if (tagged.contains(id)) continue;
                        addPoint(s, exp, id, comp);
                    }
                    if (!s.pts.isEmpty()) seriesList.add(s);
                }
            }

            // Tagged populations (shade of parent colour, following parent's components)
            for (Population pop : populations) {
                for (Map.Entry<ExperimentData, Set<Integer>> me : pop.members.entrySet()) {
                    ExperimentData exp = me.getKey();
                    if (!exp.hasD() || !exp.hasAlpha()) continue;
                    for (int comp : componentSel.getOrDefault(exp, Collections.emptySet())) {
                        Series s = new Series(exp, pop, pop.tag, comp, compColor(pop.color, comp));
                        for (Integer id : me.getValue())
                            addPoint(s, exp, id, comp);
                        if (!s.pts.isEmpty()) seriesList.add(s);
                    }
                }
            }
            return seriesList;
        }

        private void addPoint(Series s, ExperimentData exp, int id, int comp) {
            double[] d = exp.dCoeffs.get(id);
            double[] a = exp.alphaCoeffs.get(id);
            if (d == null || a == null) return;
            double dv = d[comp] * units.diffusion(exp), av = a[comp];
            if (Double.isFinite(dv) && Double.isFinite(av)
                    && dv >= 0 && av >= 0 && av <= 2 && (!logY || dv > 0))
                s.pts.add(new double[]{av, dv, id});
        }

        // ── Paint ─────────────────────────────────────────────────────────

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 20 || ph < 20) { g2.dispose(); return; }

            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            boolean anyD     = experiments.stream().anyMatch(ExperimentData::hasD);
            boolean anyAlpha = experiments.stream().anyMatch(ExperimentData::hasAlpha);
            if (!anyD || !anyAlpha) {
                drawMsg(g2, "No data — compute D and α in their respective tabs", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                drawAxisTitles(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            List<Series> seriesList = buildSeries();
            lastSeries = seriesList;

            if (seriesList.isEmpty()) {
                drawMsg(g2, "No components selected", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                drawAxisTitles(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            // ── Auto-bounds ───────────────────────────────────────────────
            double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE;
            double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
            for (Series s : seriesList)
                for (double[] pt : s.pts) {
                    xMin = Math.min(xMin, pt[0]); xMax = Math.max(xMax, pt[0]);
                    yMin = Math.min(yMin, pt[1]); yMax = Math.max(yMax, pt[1]);
                }
            if (xMin == xMax) { xMin -= 0.1; xMax += 0.1; }
            if (yMin == yMax) {
                if (logY) { yMin /= 10; yMax *= 10; }
                else { double d = Math.abs(yMin)*0.1+1e-12; yMin -= d; yMax += d; }
            }

            double dispXMin, dispXMax, dispYMin, dispYMax;
            if (viewport != null) {
                dispXMin = viewport[0]; dispXMax = viewport[1];
                dispYMin = viewport[2]; dispYMax = viewport[3];
            } else {
                double xPad = (xMax - xMin) * 0.05;
                dispXMin = xMin - xPad; dispXMax = xMax + xPad;
                if (logY) {
                    double lPad = (safeLog(yMax) - safeLog(yMin)) * 0.05;
                    dispYMin = Math.pow(10, safeLog(yMin) - lPad);
                    dispYMax = Math.pow(10, safeLog(yMax) + lPad);
                } else {
                    double yPad = (yMax - yMin) * 0.05;
                    dispYMin = yMin - yPad; dispYMax = yMax + yPad;
                }
            }

            renderXMin = dispXMin; renderXMax = dispXMax;
            renderYMin = dispYMin; renderYMax = dispYMax;
            renderPx = px; renderPy = py; renderPw = pw; renderPh = ph;

            // ── Grid ──────────────────────────────────────────────────────
            drawGrid(g2, dispXMin, dispXMax, dispYMin, dispYMax, px, py, pw, ph);

            // ── Scatter points (clipped) ──────────────────────────────────
            g2.setClip(px, py, pw, ph);
            for (Series s : seriesList) {
                Color fill   = new Color(s.color.getRed(), s.color.getGreen(),
                                         s.color.getBlue(), 100);
                Color border = s.color.darker();
                for (double[] pt : s.pts) {
                    int cx = mapX(pt[0], dispXMin, dispXMax, px, pw);
                    int cy = mapY(pt[1], dispYMin, dispYMax, py, ph);
                    g2.setColor(fill);   g2.fillOval(cx-PT_R, cy-PT_R, PT_R*2, PT_R*2);
                    g2.setColor(border); g2.setStroke(new BasicStroke(0.8f));
                    g2.drawOval(cx-PT_R, cy-PT_R, PT_R*2, PT_R*2);
                }
            }
            // pending-selection highlight rings
            if (!pendingSel.isEmpty()) {
                g2.setColor(new Color(20, 20, 20));
                g2.setStroke(new BasicStroke(1.6f));
                for (Series s : seriesList) {
                    Set<Integer> psel = pendingSel.get(s.parentExp);
                    if (psel == null || psel.isEmpty()) continue;
                    for (double[] pt : s.pts) {
                        if (!psel.contains((int) pt[2])) continue;
                        int cx = mapX(pt[0], dispXMin, dispXMax, px, pw);
                        int cy = mapY(pt[1], dispYMin, dispYMax, py, ph);
                        g2.drawOval(cx-PT_R-2, cy-PT_R-2, (PT_R+2)*2, (PT_R+2)*2);
                    }
                }
            }
            g2.setClip(null);
            g2.setStroke(new BasicStroke(1f));

            drawFrame(g2, px, py, pw, ph);
            drawXTicks(g2, dispXMin, dispXMax, px, py, pw, ph);
            drawYTicks(g2, dispYMin, dispYMax, px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            drawLegend(g2, seriesList, px, py, pw, ph);

            // ── Marginal KDE strips (grouped by series group key) ─────────
            List<MarginGroup> groups = buildMarginGroups(seriesList);
            drawAlphaMarginal(g2, groups, dispXMin, dispXMax, px, py, pw, ph);
            drawDMarginal    (g2, groups, dispYMin, dispYMax, px, py, pw, ph);

            // ── Rubber-band (zoom) ────────────────────────────────────────
            if (dragging && !panMode && !selectMode) {
                int rx = Math.min(dragX0, dragX1), ry = Math.min(dragY0, dragY1);
                int rw = Math.abs(dragX1 - dragX0), rh = Math.abs(dragY1 - dragY0);
                g2.setColor(new Color(0,100,255,40));   g2.fillRect(rx,ry,rw,rh);
                g2.setColor(new Color(0,100,255,200));  g2.setStroke(new BasicStroke(1f));
                g2.drawRect(rx, ry, rw, rh);
            }

            // ── Lasso outline ─────────────────────────────────────────────
            if (selecting && lassoPts != null && lassoPts.size() > 1) {
                Shape oldClip = g2.getClip();
                g2.setClip(px, py, pw, ph);
                Path2D path = new Path2D.Double();
                path.moveTo(lassoPts.get(0)[0], lassoPts.get(0)[1]);
                for (int i = 1; i < lassoPts.size(); i++)
                    path.lineTo(lassoPts.get(i)[0], lassoPts.get(i)[1]);
                g2.setColor(new Color(0, 110, 255, 30));
                g2.fill(path);
                g2.setColor(new Color(0, 90, 220, 220));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1f, new float[]{5f, 4f}, 0f));
                g2.draw(path);
                g2.setClip(oldClip);
                g2.setStroke(new BasicStroke(1f));
            }

            // ── Hover tooltip ─────────────────────────────────────────────
            if (hoverPx >= px && hoverPx <= px+pw && hoverPy >= py && hoverPy <= py+ph) {
                double dataX = pxToDataX(hoverPx);
                double dataY = pxToDataY(hoverPy);
                String tip = String.format("α = %.3f     D = %s", dataX, PlotUtils.fmtTick(dataY));
                drawHoverTooltip(g2, tip, hoverPx, hoverPy, W, H);
            }

            g2.dispose();
        }

        // ── Marginal KDE strips ───────────────────────────────────────────

        private List<MarginGroup> buildMarginGroups(List<Series> seriesList) {
            LinkedHashMap<Object, MarginGroup> m = new LinkedHashMap<>();
            for (Series s : seriesList) {
                MarginGroup g = m.get(s.groupKey);
                if (g == null) { g = new MarginGroup(s.color); m.put(s.groupKey, g); }
                for (double[] pt : s.pts) { g.alphas.add(pt[0]); g.ds.add(pt[1]); }
            }
            return new ArrayList<>(m.values());
        }

        private void drawAlphaMarginal(Graphics2D g2, List<MarginGroup> groups,
                                        double xMin, double xMax,
                                        int px, int py, int pw, int ph) {
            int stripY = MT - MARG_H;
            int stripH = MARG_H - 6;
            if (stripH < 8) return;

            g2.setColor(new Color(250, 250, 250));
            g2.fillRect(px, stripY, pw, stripH);
            g2.setColor(new Color(190, 190, 190));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRect(px, stripY, pw, stripH);

            int nEval = 100;
            double[] evalPts = linspace(xMin, xMax, nEval);
            Shape oldClip = g2.getClip();
            g2.setClip(px, stripY, pw, stripH);

            for (MarginGroup grp : groups) {
                double[] vals = toArray(grp.alphas);
                if (vals.length < 2) continue;
                double[] kde = Kde.gaussianKde1D(vals, evalPts, Kde.silvermanBw(vals));
                double kdeMax = arrayMax(kde);
                if (kdeMax < 1e-300) continue;

                int[] xs = new int[nEval + 2];
                int[] ys = new int[nEval + 2];
                for (int i = 0; i < nEval; i++) {
                    xs[i] = mapX(evalPts[i], xMin, xMax, px, pw);
                    ys[i] = stripY + stripH - (int)(kde[i] / kdeMax * (stripH - 2));
                }
                xs[nEval] = xs[nEval-1]; ys[nEval] = stripY + stripH;
                xs[nEval+1] = xs[0];     ys[nEval+1] = stripY + stripH;

                Color c = grp.color;
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
                g2.fillPolygon(xs, ys, nEval + 2);
                g2.setColor(c);
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawPolyline(xs, ys, nEval);
            }
            g2.setClip(oldClip);
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawDMarginal(Graphics2D g2, List<MarginGroup> groups,
                                    double yMin, double yMax,
                                    int px, int py, int pw, int ph) {
            int stripX = px + pw + 6;
            int stripW = MARG_W - 8;
            if (stripW < 8) return;

            g2.setColor(new Color(250, 250, 250));
            g2.fillRect(stripX, py, stripW, ph);
            g2.setColor(new Color(190, 190, 190));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRect(stripX, py, stripW, ph);

            double evalMin = logY ? safeLog(yMin) : yMin;
            double evalMax = logY ? safeLog(yMax) : yMax;
            double[] evalPts = linspace(evalMin, evalMax, 100);

            Shape oldClip = g2.getClip();
            g2.setClip(stripX, py, stripW, ph);

            for (MarginGroup grp : groups) {
                // transform D values to log space if needed
                List<Double> raw = grp.ds;
                double[] vals = new double[raw.size()];
                for (int i = 0; i < raw.size(); i++)
                    vals[i] = logY ? safeLog(raw.get(i)) : raw.get(i);
                if (vals.length < 2) continue;
                double[] kde = Kde.gaussianKde1D(vals, evalPts, Kde.silvermanBw(vals));
                double kdeMax = arrayMax(kde);
                if (kdeMax < 1e-300) continue;

                int nEval = evalPts.length;
                int[] xs = new int[nEval + 2];
                int[] ys = new int[nEval + 2];
                for (int i = 0; i < nEval; i++) {
                    double dForMap = logY ? Math.pow(10, evalPts[i]) : evalPts[i];
                    ys[i] = mapY(dForMap, yMin, yMax, py, ph);
                    xs[i] = stripX + (int)(kde[i] / kdeMax * (stripW - 2));
                }
                xs[nEval] = stripX; ys[nEval] = ys[nEval-1];
                xs[nEval+1] = stripX; ys[nEval+1] = ys[0];

                Color c = grp.color;
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
                g2.fillPolygon(xs, ys, nEval + 2);
                g2.setColor(c);
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawPolyline(xs, ys, nEval);
            }
            g2.setClip(oldClip);
            g2.setStroke(new BasicStroke(1f));
        }

        // ── KDE helpers ───────────────────────────────────────────────────



        // ── Coordinate transforms ─────────────────────────────────────────

        private static int mapX(double v, double xMin, double xMax, int ox, int w) {
            if (xMax == xMin) return ox;
            return ox + (int)((v - xMin) / (xMax - xMin) * w);
        }
        private int mapY(double v, double yMin, double yMax, int oy, int h) {
            if (logY) {
                double lMin = safeLog(yMin), lMax = safeLog(yMax);
                if (lMax == lMin) return oy + h;
                return oy + h - (int)((safeLog(v) - lMin) / (lMax - lMin) * h);
            }
            if (yMax == yMin) return oy + h;
            return oy + h - (int)((v - yMin) / (yMax - yMin) * h);
        }
        private static double safeLog(double v) { return Math.log10(Math.max(v, 1e-300)); }

        // ── Grid ──────────────────────────────────────────────────────────

        private void drawGrid(Graphics2D g2,
                               double xMin, double xMax, double yMin, double yMax,
                               int px, int py, int pw, int ph) {
            g2.setStroke(new BasicStroke(0.5f));
            g2.setColor(new Color(220, 220, 220));
            double xStep = PlotUtils.niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin/xStep)*xStep; t <= xMax+xStep*0.01; t += xStep)
                g2.drawLine(mapX(t,xMin,xMax,px,pw), py, mapX(t,xMin,xMax,px,pw), py+ph);

            if (logY) {
                drawLogGridY(g2, yMin, yMax, px, py, pw, ph);
            } else {
                double yStep = PlotUtils.niceStep(yMax - yMin, 5);
                for (double t = Math.ceil(yMin/yStep)*yStep; t <= yMax+yStep*0.01; t += yStep)
                    g2.drawLine(px, mapY(t,yMin,yMax,py,ph), px+pw, mapY(t,yMin,yMax,py,ph));
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawLogGridY(Graphics2D g2, double yMin, double yMax,
                                   int px, int py, int pw, int ph) {
            int e0 = (int) Math.floor(safeLog(yMin));
            int e1 = (int) Math.ceil (safeLog(yMax));
            for (int e = e0; e <= e1; e++) for (int m : new int[]{1,2,5}) {
                double val = m * Math.pow(10, e);
                if (val < yMin || val > yMax) continue;
                boolean major = m == 1;
                g2.setColor(major ? new Color(200,200,200) : new Color(230,230,230));
                g2.setStroke(new BasicStroke(major ? 0.6f : 0.4f));
                g2.drawLine(px, mapY(val,yMin,yMax,py,ph), px+pw, mapY(val,yMin,yMax,py,ph));
            }
        }

        // ── Tick marks ────────────────────────────────────────────────────

        private static void drawXTicks(Graphics2D g2,
                                        double xMin, double xMax,
                                        int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = PlotUtils.niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin/step)*step; t <= xMax+step*0.01; t += step) {
                int p = mapX(t, xMin, xMax, px, pw);
                g2.drawLine(p, py+ph, p, py+ph+4);
                String lbl = PlotUtils.fmtTick(t);
                g2.drawString(lbl, p - fm.stringWidth(lbl)/2, py+ph+15);
            }
        }

        private void drawYTicks(Graphics2D g2,
                                 double yMin, double yMax,
                                 int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            if (logY) {
                int e0 = (int) Math.floor(safeLog(yMin)), e1 = (int) Math.ceil(safeLog(yMax));
                for (int e = e0; e <= e1; e++) {
                    double v = Math.pow(10, e);
                    if (v < yMin*0.999 || v > yMax*1.001) continue;
                    int p = mapY(v, yMin, yMax, py, ph);
                    g2.drawLine(px-4, p, px, p);
                    String lbl = PlotUtils.fmtTick(v);
                    g2.drawString(lbl, px-6-fm.stringWidth(lbl), p+fm.getAscent()/2-1);
                    for (int m : new int[]{2,5}) {
                        double mv = m * v;
                        if (mv < yMin*0.999 || mv > yMax*1.001) continue;
                        g2.drawLine(px-3, mapY(mv,yMin,yMax,py,ph), px, mapY(mv,yMin,yMax,py,ph));
                    }
                }
            } else {
                double step = PlotUtils.niceStep(yMax - yMin, 5);
                for (double t = Math.ceil(yMin/step)*step; t <= yMax+step*0.01; t += step) {
                    int p = mapY(t, yMin, yMax, py, ph);
                    g2.drawLine(px-4, p, px, p);
                    String lbl = PlotUtils.fmtTick(t);
                    g2.drawString(lbl, px-6-fm.stringWidth(lbl), p+fm.getAscent()/2-1);
                }
            }
        }

        // ── Axis titles ───────────────────────────────────────────────────

        private void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();
            String xTitle = "α (anomalous exponent)";
            g2.drawString(xTitle, px + (pw - fm.stringWidth(xTitle))/2, py+ph+MB-8);
            String yTitle = "D  (" + units.diffusionLabel() + ")";
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph/2.0);
            g2.rotate(-Math.PI/2);
            g2.drawString(yTitle, -fm.stringWidth(yTitle)/2, fm.getAscent()/2);
            g2.setTransform(orig);
        }

        // ── Legend ────────────────────────────────────────────────────────

        private static final String[] COMP_LABELS = {"2D", "X", "Y"};

        /** One legend entry per (group, component) — so a multi-dataset population shows once. */
        private static class LegendEntry {
            final String label; final int comp; final Color color; int count;
            LegendEntry(String label, int comp, Color color) {
                this.label = label; this.comp = comp; this.color = color;
            }
        }

        private void drawLegend(Graphics2D g2, List<Series> seriesList,
                                 int px, int py, int pw, int ph) {
            if (seriesList.isEmpty()) { legW = 0; return; }

            // Aggregate series by (groupKey, comp): a population spanning several
            // datasets shares one groupKey, so it collapses to a single entry.
            LinkedHashMap<List<Object>, LegendEntry> agg = new LinkedHashMap<>();
            for (Series s : seriesList) {
                List<Object> key = Arrays.asList(s.groupKey, s.comp);
                LegendEntry le = agg.get(key);
                if (le == null) { le = new LegendEntry(s.label, s.comp, s.color); agg.put(key, le); }
                le.count += s.pts.size();
            }
            List<LegendEntry> entries = new ArrayList<>(agg.values());

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int lineH = 18, pad = 6;
            int maxW = 0;
            for (LegendEntry le : entries) {
                String lbl = le.label + " (" + COMP_LABELS[le.comp] + ", " + le.count + ")";
                maxW = Math.max(maxW, fm.stringWidth(lbl));
            }
            int boxW = maxW + 28 + pad*2, boxH = entries.size()*lineH + pad*2;
            // Default top-right, or the user-dragged position (clamped to the plot).
            int lx, ly;
            if (legendX == null) { lx = px + pw - boxW - 6; ly = py + 6; }
            else {
                lx = Math.max(px, Math.min(legendX, px + pw - boxW));
                ly = Math.max(py, Math.min(legendY, py + ph - boxH));
            }
            legX = lx; legY = ly; legW = boxW; legH = boxH;   // for hit-testing
            g2.setColor(new Color(255,255,255,210));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180,180,180)); g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);
            for (int i = 0; i < entries.size(); i++) {
                LegendEntry le = entries.get(i);
                int ry = ly + pad + (i+1)*lineH - 3;
                Color fill   = new Color(le.color.getRed(), le.color.getGreen(), le.color.getBlue(), 120);
                Color border = le.color.darker();
                g2.setColor(fill);   g2.fillOval(lx+pad, ry-9, 12, 12);
                g2.setColor(border); g2.setStroke(new BasicStroke(0.8f));
                g2.drawOval(lx+pad, ry-9, 12, 12);
                g2.setColor(Color.BLACK);
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g2.drawString(le.label + " (" + COMP_LABELS[le.comp] + ", " + le.count + ")",
                              lx+pad+18, ry);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // ── Utilities ─────────────────────────────────────────────────────

        private static void drawFrame(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);
        }
        private static void drawMsg(Graphics2D g2, String msg, int px, int py, int pw, int ph) {
            g2.setColor(new Color(160,160,160));
            g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, px+(pw-fm.stringWidth(msg))/2, py+ph/2+fm.getAscent()/2);
        }
        private static void drawHoverTooltip(Graphics2D g2, String text,
                                              int mx, int my, int W, int H) {
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(text), th = fm.getAscent(), pad = 4;
            int bw = tw+pad*2, bh = th+pad*2;
            int bx = mx+14, by = my-bh-4;
            if (bx+bw > W-4) bx = mx-bw-6;
            if (by < 4)      by = my+10;
            g2.setColor(new Color(255,255,220,230)); g2.fillRoundRect(bx,by,bw,bh,4,4);
            g2.setColor(new Color(160,160,100)); g2.setStroke(new BasicStroke(0.5f));
            g2.drawRoundRect(bx,by,bw,bh,4,4);
            g2.setColor(new Color(30,30,30));
            g2.drawString(text, bx+pad, by+pad+th-1);
        }
        static Color compColor(Color base, int comp) {
            if (comp==1) return new Color(Math.min(255,base.getRed()+55),
                                          Math.min(255,base.getGreen()+55),
                                          Math.min(255,base.getBlue()+55));
            if (comp==2) return base.darker();
            return base;
        }
        private static double[] linspace(double lo, double hi, int n) {
            double[] a = new double[n];
            for (int i=0; i<n; i++) a[i] = lo + (double)i/(n-1)*(hi-lo);
            return a;
        }
        private static double[] toArray(List<Double> list) {
            double[] a = new double[list.size()];
            for (int i=0; i<list.size(); i++) a[i] = list.get(i);
            return a;
        }
        private static double arrayMax(double[] a) {
            double m = 0; for (double v : a) if (v > m) m = v; return m;
        }
    }
}
