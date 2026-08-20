package sc.fiji.andi;

import ij.IJ;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * Visualization tab for Trajectory Segmentation.
 *
 * <p>Displays a 4 × 4 grid of small per-trajectory plots.  Each plot shows
 * the per-frame predictions of the STEP models as two time series:
 * <ul>
 *   <li><b style="color:#C80000">red</b>   – anomalous exponent α  (right y-axis, range [0, 2])</li>
 *   <li><b style="color:#0050C8">blue</b>  – log₁₀(D)              (left  y-axis, auto-scaled)</li>
 * </ul>
 * The x-axis is the displacement index (0 … T−2 for a track of T frames).
 *
 * <p>Top bar controls:
 * <ul>
 *   <li>Experiment drop-down (hidden when only one experiment is loaded)</li>
 *   <li>"Sample random 16" button</li>
 *   <li>"Show trajectories:" text field – comma-separated track IDs</li>
 *   <li>Toggle buttons for α and D visibility (affect all 16 plots at once)</li>
 * </ul>
 */
public class SegVisualizationPanel extends JPanel {

    private static final int GRID_COLS = 4;
    private static final int GRID_ROWS = 4;
    private static final int N_PLOTS   = GRID_COLS * GRID_ROWS;

    // ── colours (shared with TrajectoryPlot) ──────────────────────────────
    static final Color ALPHA_COLOR = new Color(200,  0,   0);
    static final Color D_COLOR     = new Color(  0, 80, 200);

    // ── state ─────────────────────────────────────────────────────────────
    private final List<ExperimentData> experiments;
    private ExperimentData currentExp;

    private final JComboBox<String> expCombo;
    private final JTextField        trajField;
    private final TrajectoryPlot[]  plots;
    private int     rawOpacity   = 76;     // opacity (0–255) of the raw α / D lines (default 30%)
    private boolean showSegAlpha = true;
    private boolean showSegD     = true;
    private boolean showCP       = true;   // vertical change-point markers
    private boolean showState    = true;   // top diffusive-state strip

    /** Current change-point parameters; edited via the parameters dialog. */
    private final SegmentationParams segParams = new SegmentationParams();
    private JDialog paramsDialog;

    private final Random rng = new Random();

    // ═════════════════════════════════════════════════════════════════════
    // Constructor
    // ═════════════════════════════════════════════════════════════════════

    public SegVisualizationPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 2));
        this.experiments = experiments;
        this.currentExp  = experiments.isEmpty() ? null : experiments.get(0);

        // ── top bar ──────────────────────────────────────────────────────
        String[] names = new String[experiments.size()];
        for (int i = 0; i < experiments.size(); i++) names[i] = experiments.get(i).name;
        expCombo  = new JComboBox<>(names);
        // Colour each entry's TEXT with its experiment colour (keep default bg).
        expCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                int idx = (index >= 0) ? index : expCombo.getSelectedIndex();
                if (idx >= 0 && idx < experiments.size() && !isSelected) {
                    c.setForeground(experiments.get(idx).color);
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
                return c;
            }
        });
        trajField = new JTextField(18);
        trajField.setToolTipText(
            "Comma-separated TRACK_IDs to display, e.g.  1, 5, 20, 37\n"
            + "Up to 16 values.  Press Enter or click Apply.");

        add(Toolbars.makeScrollable(buildTopBar()), BorderLayout.NORTH);

        // ── plot grid ────────────────────────────────────────────────────
        JPanel grid = new JPanel(new GridLayout(GRID_ROWS, GRID_COLS, 2, 2));
        grid.setBackground(new Color(200, 200, 200));
        plots = new TrajectoryPlot[N_PLOTS];
        for (int i = 0; i < N_PLOTS; i++) {
            plots[i] = new TrajectoryPlot();
            int row = i / GRID_COLS, col = i % GRID_COLS;
            plots[i].leftCol   = (col == 0);                 // blue log₁₀(D) label
            plots[i].rightCol  = (col == GRID_COLS - 1);     // red α label
            plots[i].bottomRow = (row == GRID_ROWS - 1);     // "Frame" label
            plots[i].topLeft   = (row == 0 && col == 0);     // diffusive-state legend
            grid.add(plots[i]);
        }
        add(grid, BorderLayout.CENTER);

        // ── initial segmentation + display ────────────────────────────────
        recomputeSegments();
        if (currentExp != null) sampleRandom();
    }

    /**
     * Ensures every experiment has segments. Change points now come from the
     * segmentation model (filled by {@link UclStepPredictor}); the kernel-based
     * {@link ChangePointDetector} is kept only as a fallback for experiments
     * that somehow arrive without model segments (and is otherwise hidden).
     */
    private void recomputeSegments() {
        for (ExperimentData exp : experiments)
            if (exp.hasStepPredictions() && !exp.hasSegments())
                ChangePointDetector.computeForExperiment(exp, segParams);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Top bar
    // ═════════════════════════════════════════════════════════════════════

    private JPanel buildTopBar() {
        // Experiment selector
        expCombo.addActionListener(e -> {
            int idx = expCombo.getSelectedIndex();
            if (idx >= 0) {
                currentExp = experiments.get(idx);
                sampleRandom();
            }
        });

        JButton sampleBtn = new JButton("Sample random 16");
        sampleBtn.setToolTipText("Pick 16 random trajectories from the selected experiment");
        sampleBtn.addActionListener(e -> sampleRandom());

        // "Choose trajectories" opens a small popup with the ID field (saves space)
        JButton showTrajBtn = new JButton("Choose trajectories");
        showTrajBtn.setToolTipText("Enter specific track IDs to display");
        final JPopupMenu trajPopup = new JPopupMenu();
        JPanel pp = new JPanel(new BorderLayout(4, 4));
        pp.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        pp.add(new JLabel("Track IDs (comma-separated):"), BorderLayout.NORTH);
        pp.add(trajField, BorderLayout.CENTER);
        JButton applyBtn = new JButton("Apply");
        JPanel applyRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        applyRow.add(applyBtn);
        pp.add(applyRow, BorderLayout.SOUTH);
        trajPopup.add(pp);

        Runnable apply = () -> { showSpecified(); trajPopup.setVisible(false); };
        applyBtn.addActionListener(e -> apply.run());
        trajField.addActionListener(e -> apply.run());   // Enter key
        showTrajBtn.addActionListener(e -> {
            trajPopup.show(showTrajBtn, 0, showTrajBtn.getHeight());
            SwingUtilities.invokeLater(trajField::requestFocusInWindow);
        });

        // Raw α / D opacity slider (replaces the old on/off toggles)
        JLabel rawLabel = new JLabel(String.format(
            "<html>Raw <font color='#%06X'>α</font>/<font color='#%06X'>D</font>:</html>",
            ALPHA_COLOR.getRGB() & 0xFFFFFF, D_COLOR.getRGB() & 0xFFFFFF));
        JSlider rawSlider = new JSlider(0, 100, 30);
        rawSlider.setToolTipText("Opacity of the raw α / D lines (0 = hidden)");
        rawSlider.setPreferredSize(new Dimension(90,
            rawSlider.getPreferredSize().height));
        rawSlider.addChangeListener(e -> {
            rawOpacity = rawSlider.getValue() * 255 / 100;
            for (TrajectoryPlot p : plots) { p.rawOpacity = rawOpacity; p.repaint(); }
        });

        // Segment-overlay toggles (independent of the raw α / D lines)
        JToggleButton segAlphaToggle = new JToggleButton("α_seg", true);
        segAlphaToggle.setForeground(ALPHA_COLOR.darker());
        segAlphaToggle.setFont(segAlphaToggle.getFont().deriveFont(Font.BOLD, 11f));
        segAlphaToggle.setToolTipText("Toggle the α segment step-lines in all plots");
        segAlphaToggle.addActionListener(e -> {
            showSegAlpha = segAlphaToggle.isSelected();
            for (TrajectoryPlot p : plots) { p.showSegAlpha = showSegAlpha; p.repaint(); }
        });

        JToggleButton segDToggle = new JToggleButton("D_seg", true);
        segDToggle.setForeground(D_COLOR.darker());
        segDToggle.setFont(segDToggle.getFont().deriveFont(Font.BOLD, 11f));
        segDToggle.setToolTipText("Toggle the log₁₀(D) segment step-lines in all plots");
        segDToggle.addActionListener(e -> {
            showSegD = segDToggle.isSelected();
            for (TrajectoryPlot p : plots) { p.showSegD = showSegD; p.repaint(); }
        });

        // Change-point vertical markers toggle
        JToggleButton cpToggle = new JToggleButton("CP", true);
        cpToggle.setFont(cpToggle.getFont().deriveFont(Font.BOLD, 11f));
        cpToggle.setToolTipText("Show/hide the vertical change-point lines in all plots");
        cpToggle.addActionListener(e -> {
            showCP = cpToggle.isSelected();
            for (TrajectoryPlot p : plots) { p.showCP = showCP; p.repaint(); }
        });

        // Diffusive-state strip toggle
        JToggleButton stateToggle = new JToggleButton("State", true);
        stateToggle.setFont(stateToggle.getFont().deriveFont(Font.BOLD, 11f));
        stateToggle.setToolTipText("Show/hide the diffusive-state strip at the top of each plot");
        stateToggle.addActionListener(e -> {
            showState = stateToggle.isSelected();
            for (TrajectoryPlot p : plots) { p.showState = showState; p.repaint(); }
        });

        // Kernel-CP parameter dialog — hidden for now (change points come from
        // the segmentation model). Kept for later use.
        JButton paramsBtn = new JButton("Segmentation Parameters");
        paramsBtn.setToolTipText("Change the change-point detection parameters and recalculate");
        paramsBtn.addActionListener(e -> openParamsDialog());
        paramsBtn.setVisible(false);

        JButton saveRawBtn = new JButton("Save raw preds.");
        saveRawBtn.setToolTipText("Export the per-frame α / D / state predictions to CSV "
            + "(experiment, traj_idx, frame, alpha, D, state)");
        saveRawBtn.addActionListener(e -> saveRawPredictions());

        JButton saveSegBtn = new JButton("Save segments");
        saveSegBtn.setToolTipText("Export all segments (length, α, D) to CSV");
        saveSegBtn.addActionListener(e -> saveSegments());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEtchedBorder());

        if (experiments.size() > 1) {
            bar.add(new JLabel("Experiment:"));
            bar.add(expCombo);
            bar.add(Box.createHorizontalStrut(6));
        }
        bar.add(sampleBtn);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(showTrajBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(rawLabel);
        bar.add(rawSlider);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(segAlphaToggle);
        bar.add(segDToggle);
        bar.add(cpToggle);
        bar.add(stateToggle);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(paramsBtn);
        bar.add(saveRawBtn);
        bar.add(saveSegBtn);

        return bar;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Segmentation parameters dialog
    // ═════════════════════════════════════════════════════════════════════

    private void openParamsDialog() {
        if (paramsDialog != null && paramsDialog.isDisplayable()) {
            paramsDialog.toFront();
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        paramsDialog = new JDialog(owner, "Segmentation Parameters");
        paramsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        final JTextField penField  = new JTextField(String.valueOf(segParams.penalty), 6);
        final JTextField minField  = new JTextField(String.valueOf(segParams.minSize), 6);
        final JTextField jumpField = new JTextField(String.valueOf(segParams.jump),    6);
        final JCheckBox  rescaleBox = new JCheckBox(
            "Rescale log₁₀(D) to [0,1] before detection", segParams.rescaleD);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addFormRow(form, c, row++, "Penalty (fewer ↔ more segments):", penField);
        addFormRow(form, c, row++, "Min. segment length (frames):",    minField);
        addFormRow(form, c, row++, "Jump (candidate grid step):",       jumpField);
        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        form.add(rescaleBox, c);
        c.gridwidth = 1;

        JLabel hint = new JLabel("<html><i>Linear-kernel change-point detection on (D, α). "
            + "Penalty is the cost per change point.</i></html>");
        hint.setForeground(new Color(110, 110, 110));
        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        form.add(hint, c);

        JButton recalcBtn = new JButton("Recalculate");
        recalcBtn.addActionListener(ev -> {
            segParams.penalty  = parseDoubleOr(penField.getText(),  segParams.penalty);
            segParams.minSize  = parseIntOr(minField.getText(),     segParams.minSize);
            segParams.jump     = parseIntOr(jumpField.getText(),    segParams.jump);
            segParams.rescaleD = rescaleBox.isSelected();
            // reflect any clamping back into the fields
            penField.setText(String.valueOf(segParams.penalty));
            minField.setText(String.valueOf(segParams.minSize));
            jumpField.setText(String.valueOf(segParams.jump));
            recomputeSegments();
            showSpecified();   // refresh the currently displayed trajectories
        });
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(ev -> paramsDialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        buttons.add(recalcBtn);
        buttons.add(closeBtn);

        JPanel content = new JPanel(new BorderLayout());
        content.add(form,    BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        paramsDialog.setContentPane(content);
        paramsDialog.pack();
        paramsDialog.setLocationRelativeTo(this);
        paramsDialog.setVisible(true);
    }

    private static void addFormRow(JPanel form, GridBagConstraints c, int row,
                                   String label, JComponent field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.gridy = row; c.weightx = 1;
        form.add(field, c);
    }

    private static int parseIntOr(String s, int d) {
        try { return Math.max(1, Integer.parseInt(s.trim())); } catch (Exception e) { return d; }
    }
    private static double parseDoubleOr(String s, double d) {
        try { return Math.max(0, Double.parseDouble(s.trim())); } catch (Exception e) { return d; }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Save segments
    // ═════════════════════════════════════════════════════════════════════

    private void saveSegments() {
        if (experiments.stream().noneMatch(ExperimentData::hasSegments)) {
            JOptionPane.showMessageDialog(this,
                "No segments available.",
                "Save segments", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle("Save change-point segments");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");

        boolean multiExp = experiments.stream().filter(ExperimentData::hasSegments).count() > 1;

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            if (multiExp) pw.println("experiment,track_id,segment,start_frame,length,alpha,D,state");
            else          pw.println("track_id,segment,start_frame,length,alpha,D,state");

            for (ExperimentData exp : experiments) {
                if (!exp.hasSegments()) continue;
                for (Map.Entry<Integer, List<Segment>> e : exp.segments.entrySet()) {
                    int id = e.getKey();
                    List<Segment> segs = e.getValue();
                    for (int k = 0; k < segs.size(); k++) {
                        Segment s = segs.get(k);
                        int startFrame = s.start + 1;   // 1-based
                        if (multiExp)
                            pw.printf("%s,%d,%d,%d,%d,%.6f,%.6e,%s%n",
                                exp.name, id, k + 1, startFrame, s.length, s.alpha, s.d(), stateLabel(s.state));
                        else
                            pw.printf("%d,%d,%d,%d,%.6f,%.6e,%s%n",
                                id, k + 1, startFrame, s.length, s.alpha, s.d(), stateLabel(s.state));
                    }
                }
            }
            IJ.log("AnDi-J: segments saved → " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not save file:\n" + ex.getMessage(),
                "Save segments – error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Diffusive-state class as an integer (0=immobile, 1=confined, 2=free, 3=directed), or "" when unknown. */
    private static String stateLabel(int st) {
        return (st >= 0 && st < SegPhenomPanel.PHENOM_NAMES.length) ? String.valueOf(st) : "";
    }

    /** Per-frame diffusive state (from the segment covering each frame; -1 if none). */
    private static int[] perFrameStates(List<Segment> segs, int n) {
        int[] st = new int[n];
        Arrays.fill(st, -1);
        if (segs != null)
            for (Segment s : segs)
                for (int t = s.start; t < s.start + s.length && t < n; t++)
                    if (t >= 0) st[t] = s.state;
        return st;
    }

    /**
     * Exports the per-frame predictions to CSV with columns
     * {@code experiment, traj_idx, frame, alpha, D, state}. α is the per-frame
     * anomalous exponent; D = 10^log₁₀(D) (blank where D is undefined); state is
     * the diffusive state of the segment covering the frame.
     */
    private void saveRawPredictions() {
        if (experiments.stream().noneMatch(ExperimentData::hasStepPredictions)) {
            JOptionPane.showMessageDialog(this,
                "No predictions available.",
                "Save raw predictions", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle("Save raw per-frame predictions");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            pw.println("experiment,traj_idx,frame,alpha,D,state");

            for (ExperimentData exp : experiments) {
                if (!exp.hasStepPredictions()) continue;
                for (Map.Entry<Integer, double[]> e : exp.stepAlpha.entrySet()) {
                    int      id    = e.getKey();
                    double[] a     = e.getValue();
                    double[] ld    = exp.stepLogD != null ? exp.stepLogD.get(id) : null;
                    List<double[]> spots = exp.tracks.get(id);
                    if (a == null) continue;
                    int n = (spots != null) ? Math.min(a.length, spots.size()) : a.length;
                    int[] states = perFrameStates(exp.segments != null ? exp.segments.get(id) : null, n);
                    for (int t = 0; t < n; t++) {
                        long   frame = (spots != null) ? Math.round(spots.get(t)[0]) : t;
                        String dStr  = "";
                        if (ld != null && t < ld.length && Double.isFinite(ld[t]))
                            dStr = String.format("%.6e", Math.pow(10, ld[t]));
                        pw.printf("%s,%d,%d,%.6f,%s,%s%n",
                            exp.name, id, frame, a[t], dStr, stateLabel(states[t]));
                    }
                }
            }
            IJ.log("AnDi-J: raw predictions saved → " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not save file:\n" + ex.getMessage(),
                "Save raw predictions – error", JOptionPane.ERROR_MESSAGE);
        }
    }


    // ═════════════════════════════════════════════════════════════════════
    // Sampling and display logic
    // ═════════════════════════════════════════════════════════════════════

    /** Picks up to 16 random track IDs and refreshes the grid. */
    private void sampleRandom() {
        if (currentExp == null || !currentExp.hasStepPredictions()) {
            trajField.setText("");
            clearAllPlots();
            return;
        }

        List<Integer> all = new ArrayList<>(currentExp.stepAlpha.keySet());
        Collections.shuffle(all, rng);
        List<Integer> chosen = all.subList(0, Math.min(N_PLOTS, all.size()));

        // Update the text field so the user can see (and copy) the selection
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chosen.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(chosen.get(i));
        }
        trajField.setText(sb.toString());

        updatePlots(chosen);
    }

    /**
     * Parses the "Show trajectories:" text field and displays those tracks.
     * Accepts formats like {@code 1, 5, 20, 37} or {@code [1, 5, 20, 37]}.
     */
    private void showSpecified() {
        if (currentExp == null) return;
        String raw = trajField.getText().trim()
                              .replaceAll("[\\[\\]]", "");   // strip brackets
        String[] parts = raw.split("[,\\s]+");
        List<Integer> ids = new ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try { ids.add(Integer.parseInt(p)); }
            catch (NumberFormatException ignored) {}
        }
        updatePlots(ids.subList(0, Math.min(N_PLOTS, ids.size())));
    }

    /** Pushes data (or a "not found" marker) into each of the 16 plot panels. */
    private void updatePlots(List<Integer> ids) {
        for (int i = 0; i < N_PLOTS; i++) {
            if (currentExp == null || i >= ids.size()) {
                plots[i].clear();
            } else {
                int      id    = ids.get(i);
                double[] alpha = currentExp.stepAlpha  != null
                                 ? currentExp.stepAlpha.get(id) : null;
                double[] logD  = currentExp.stepLogD   != null
                                 ? currentExp.stepLogD.get(id)  : null;

                if (alpha == null && logD == null) {
                    plots[i].setEmpty(id);
                } else {
                    List<Segment> segs = (currentExp.segments != null)
                            ? currentExp.segments.get(id) : null;
                    plots[i].setData(id, alpha, logD, segs);
                }
            }
            plots[i].rawOpacity   = rawOpacity;
            plots[i].showSegAlpha = showSegAlpha;
            plots[i].showSegD     = showSegD;
            plots[i].showCP       = showCP;
            plots[i].showState    = showState;
        }
    }

    private void clearAllPlots() {
        for (TrajectoryPlot p : plots) p.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inner class: one mini-plot
    // ═════════════════════════════════════════════════════════════════════

    /**
     * A small custom-painted panel that shows α (red, right axis) and
     * log₁₀(D) (blue, left axis) as time series for a single trajectory.
     */
    static class TrajectoryPlot extends JPanel {

        // Padding around the plot area (pixels)
        private static final int PAD_L = 40;
        private static final int PAD_R = 34;
        private static final int PAD_T = 17;
        private static final int PAD_B = 22;

        private static final Color BG_COLOR   = Color.WHITE;
        private static final Color GRID_COLOR = new Color(230, 230, 230);

        // ── data ──────────────────────────────────────────────────────────
        private int      trackId   = -1;    // -1 = empty slot
        private double[] alpha;             // T-1 α  values  (may be null)
        private double[] logD;              // T-1 logD values (may be null)
        private List<Segment> segments;     // change-point segments (may be null)
        private boolean  notFound  = false; // track ID not in experiment

        int     rawOpacity   = 76;    // opacity of the raw α / D lines (0 = hidden; default 30%)
        boolean showSegAlpha = true;
        boolean showSegD     = true;
        boolean showCP       = true;
        boolean showState    = true;   // top diffusive-state strip
        boolean leftCol, rightCol, bottomRow;   // border-plot axis-title flags
        boolean topLeft;                        // shows the diffusive-state legend

        private static final int STRIP_H = 6;   // diffusive-state strip height (px)
        private final MovableLegend legend = new MovableLegend();

        TrajectoryPlot() {
            setBackground(BG_COLOR);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { legend.press(e.getX(), e.getY()); }
                @Override public void mouseDragged(MouseEvent e) {
                    if (legend.dragTo(e.getX(), e.getY())) repaint();
                }
                @Override public void mouseReleased(MouseEvent e) { legend.release(); }
                @Override public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(
                        legend.over(e.getX(), e.getY()) ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        /** Show data for an existing track. */
        void setData(int id, double[] alpha, double[] logD, List<Segment> segments) {
            this.trackId  = id;
            this.alpha    = alpha;
            this.logD     = logD;
            this.segments = segments;
            this.notFound = false;
            repaint();
        }

        /** Track ID was requested but not found in the experiment. */
        void setEmpty(int id) {
            this.trackId  = id;
            this.alpha    = null;
            this.logD     = null;
            this.segments = null;
            this.notFound = true;
            repaint();
        }

        /** No track assigned to this slot. */
        void clear() {
            this.trackId  = -1;
            this.alpha    = null;
            this.logD     = null;
            this.segments = null;
            this.notFound = false;
            repaint();
        }

        // ── painting ──────────────────────────────────────────────────────

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();

            // Background
            g2.setColor(BG_COLOR);
            g2.fillRect(0, 0, W, H);

            // Empty slot
            if (trackId < 0) {
                g2.setColor(new Color(210, 210, 210));
                g2.fillRect(0, 0, W, H);
                g2.setColor(Color.GRAY);
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
                drawCentred(g2, "—", W / 2, H / 2 + 4);
                return;
            }

            // Title (top-left corner, kept short so the state strip stays wide)
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 9f));
            g2.drawString("#" + trackId, 2, 8);

            // Track not found in experiment
            if (notFound) {
                g2.setColor(Color.GRAY);
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 9f));
                drawCentred(g2, "not found", W / 2, H / 2 + 4);
                return;
            }

            // Determine sequence length
            int seqLen = alpha != null ? alpha.length
                       : logD  != null ? logD.length : 0;
            if (seqLen == 0) {
                g2.setColor(Color.GRAY);
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 9f));
                drawCentred(g2, "empty", W / 2, H / 2 + 4);
                return;
            }

            // Plot area in pixels
            int px = PAD_L, py = PAD_T;
            int pw = W - PAD_L - PAD_R;
            int ph = H - PAD_T - PAD_B;
            if (pw < 10 || ph < 10) return;

            // ── Diffusive-state strip (sits right above the plot, aligned with
            //    the frame axis) ────────────────────────────────────────────
            if (showState && segments != null && !segments.isEmpty()) {
                int stripY = py - STRIP_H;
                for (Segment s : segments) {
                    if (s.state < 0 || s.state >= SegPhenomPanel.STATE_COLORS.length) continue;
                    int x0 = px + xToPixel(s.start, seqLen, pw);
                    int x1 = px + xToPixel(Math.min(seqLen, s.start + s.length), seqLen, pw);
                    g2.setColor(SegPhenomPanel.STATE_COLORS[s.state]);
                    g2.fillRect(x0, stripY, Math.max(1, x1 - x0), STRIP_H);
                }
            }

            // ── Auto-scale logD axis ───────────────────────────────────────
            boolean dAxis = (rawOpacity > 0 || showSegD) && logD != null;
            boolean aAxis = (rawOpacity > 0 || showSegAlpha) && alpha != null;
            double dMin = Double.MAX_VALUE, dMax = -Double.MAX_VALUE;
            if (dAxis) {
                for (double v : logD) {
                    if (Double.isFinite(v)) {
                        dMin = Math.min(dMin, v);
                        dMax = Math.max(dMax, v);
                    }
                }
                if (dMin == Double.MAX_VALUE) { dMin = -2; dMax = 2; }
                double rng = dMax - dMin;
                if (rng < 1e-6) { dMin -= 0.5; dMax += 0.5; rng = 1; }
                dMin -= 0.05 * rng;
                dMax += 0.05 * rng;
            } else {
                dMin = -2; dMax = 2;
            }

            // α axis: fixed [0, 2]
            final double aMin = 0.0, aMax = 2.0;

            // ── Light grid lines ──────────────────────────────────────────
            g2.setColor(GRID_COLOR);
            g2.setStroke(new BasicStroke(0.5f));
            for (int gi = 0; gi <= 4; gi++) {
                int y = py + ph - (int)((double) gi / 4 * ph);
                g2.drawLine(px, y, px + pw, y);
            }

            // ── Axis borders ──────────────────────────────────────────────
            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);

            // ── X-axis tick labels (frame index) ──────────────────────────
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 7.5f));
            FontMetrics fm = g2.getFontMetrics();
            int nXTicks = Math.min(4, seqLen);
            for (int i = 0; i <= nXTicks; i++) {
                int frame = (int) Math.round((double)(seqLen - 1) * i / nXTicks);
                int xPix  = px + xToPixel(frame, seqLen, pw);
                g2.setColor(Color.DARK_GRAY);
                g2.drawLine(xPix, py + ph, xPix, py + ph + 2);
                String lbl = String.valueOf(frame);
                g2.drawString(lbl, xPix - fm.stringWidth(lbl) / 2, py + ph + 11);
            }

            // ── Left (D) y-axis ticks ─────────────────────────────────────
            if (dAxis) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 7.5f));
                fm = g2.getFontMetrics();
                g2.setColor(D_COLOR);
                int nY = 3;
                for (int i = 0; i <= nY; i++) {
                    double val  = dMin + (dMax - dMin) * i / nY;
                    int    yPix = py + ph - yToPixel(val, dMin, dMax, ph);
                    g2.drawLine(px - 3, yPix, px, yPix);
                    String lbl = String.format("%.1f", val);
                    g2.drawString(lbl, px - fm.stringWidth(lbl) - 4,
                                  yPix + fm.getAscent() / 2 - 1);
                }
            }

            // ── Right (α) y-axis ticks ────────────────────────────────────
            if (aAxis) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 7.5f));
                fm = g2.getFontMetrics();
                g2.setColor(ALPHA_COLOR);
                for (int v = 0; v <= 2; v++) {
                    int yPix = py + ph - yToPixel(v, aMin, aMax, ph);
                    g2.drawLine(px + pw, yPix, px + pw + 3, yPix);
                    String lbl = String.valueOf(v);
                    g2.drawString(lbl, px + pw + 5,
                                  yPix + fm.getAscent() / 2 - 1);
                }
            }

            // ── Border axis titles (blue D left, red α right, Frame bottom) ─
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 8.5f));
            if (bottomRow) {
                g2.setColor(Color.DARK_GRAY);
                FontMetrics tf = g2.getFontMetrics();
                g2.drawString("Frame", px + (pw - tf.stringWidth("Frame")) / 2, py + ph + PAD_B - 1);
            }
            if (leftCol) {
                g2.setColor(D_COLOR);
                drawVTitle(g2, "log₁₀(D)", 9, py + ph / 2);
            }
            if (rightCol) {
                g2.setColor(ALPHA_COLOR);
                drawVTitle(g2, "α", getWidth() - 5, py + ph / 2);
            }

            // ── Clip to plot area before drawing lines ────────────────────
            Shape oldClip = g2.getClip();
            g2.setClip(px, py, pw, ph);

            // ── logD line (blue) ──────────────────────────────────────────
            if (rawOpacity > 0 && logD != null) {
                g2.setColor(withAlpha(D_COLOR, rawOpacity));
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND,
                                             BasicStroke.JOIN_ROUND));
                drawLine(g2, logD, seqLen, dMin, dMax, px, py, pw, ph);
            }

            // ── α line (red) ──────────────────────────────────────────────
            if (rawOpacity > 0 && alpha != null) {
                g2.setColor(withAlpha(ALPHA_COLOR, rawOpacity));
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND,
                                             BasicStroke.JOIN_ROUND));
                drawLine(g2, alpha, seqLen, aMin, aMax, px, py, pw, ph);
            }

            // ── Change-point segment overlay ──────────────────────────────
            if ((showSegAlpha || showSegD || showCP) && segments != null && !segments.isEmpty()) {
                // Vertical change-point markers (segment starts, except the first)
                if (showCP) {
                    g2.setColor(new Color(110, 110, 110));
                    g2.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_MITER, 1f, new float[]{3f, 3f}, 0f));
                    for (Segment s : segments) {
                        if (s.start <= 0) continue;
                        int xp = px + xToPixel(s.start, seqLen, pw);
                        g2.drawLine(xp, py, xp, py + ph);
                    }
                }
                // Per-segment mean step lines (independent of the raw α / D lines)
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER));
                for (Segment s : segments) {
                    int x0 = px + xToPixel(s.start, seqLen, pw);
                    int x1 = px + xToPixel(Math.min(seqLen - 1, s.start + s.length - 1),
                                           seqLen, pw);
                    if (showSegD && logD != null && Double.isFinite(s.logD)) {
                        int yp = py + ph - yToPixel(s.logD, dMin, dMax, ph);
                        g2.setColor(D_COLOR.darker());
                        g2.drawLine(x0, yp, x1, yp);
                    }
                    if (showSegAlpha && alpha != null && Double.isFinite(s.alpha)) {
                        int yp = py + ph - yToPixel(s.alpha, aMin, aMax, ph);
                        g2.setColor(ALPHA_COLOR.darker());
                        g2.drawLine(x0, yp, x1, yp);
                    }
                }
            }

            g2.setClip(oldClip);

            // ── Diffusive-state legend (top-left plot of the grid only) ─────
            if (topLeft && showState) drawStateLegend(g2, px, py, pw, ph, W, H);
            else legend.clear();
        }

        /** Draggable legend for the diffusive-state strip colours (top-left plot only). */
        private void drawStateLegend(Graphics2D g2, int px, int py, int pw, int ph, int W, int H) {
            String[] names = SegPhenomPanel.PHENOM_NAMES;
            Color[]  cols  = SegPhenomPanel.STATE_COLORS;

            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 8f));
            FontMetrics fmBold = g2.getFontMetrics();
            String title = "Diffusive state";
            int titleW = fmBold.stringWidth(title);

            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 8f));
            FontMetrics fm = g2.getFontMetrics();
            int maxW = titleW;
            for (String n : names) maxW = Math.max(maxW, fm.stringWidth(n) + 12);

            int pad = 4, lineH = 10;
            int boxW = maxW + pad * 2;
            int boxH = pad * 2 + lineH + names.length * lineH;

            Point lp = legend.layout(px + pw - boxW - 3, py + STRIP_H + 3, boxW, boxH,
                                     0, 0, W, H);
            int lx = lp.x, ly = lp.y;

            g2.setColor(new Color(255, 255, 255, 225));
            g2.fillRoundRect(lx, ly, boxW, boxH, 5, 5);
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 5, 5);

            g2.setColor(Color.DARK_GRAY);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 8f));
            g2.drawString(title, lx + pad, ly + pad + fmBold.getAscent() - 1);

            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 8f));
            fm = g2.getFontMetrics();
            for (int i = 0; i < names.length; i++) {
                int ry = ly + pad + lineH + i * lineH;
                g2.setColor(cols[i]);
                g2.fillRect(lx + pad, ry, 8, 8);
                g2.setColor(Color.BLACK);
                g2.drawString(names[i], lx + pad + 11, ry + fm.getAscent() - 1);
            }
        }

        // ── Helpers ───────────────────────────────────────────────────────

        /** Draws a series as a connected poly-line within the plot area. */
        private static void drawLine(Graphics2D g2, double[] data, int len,
                                     double yMin, double yMax,
                                     int px, int py, int pw, int ph) {
            double yRange = yMax - yMin;
            if (yRange < 1e-12) return;

            int prevX = Integer.MIN_VALUE, prevY = Integer.MIN_VALUE;
            for (int t = 0; t < len && t < data.length; t++) {
                if (!Double.isFinite(data[t])) { prevX = Integer.MIN_VALUE; continue; }
                int xp = px + xToPixel(t, len, pw);
                int yp = py + ph - yToPixel(data[t], yMin, yMax, ph);
                if (prevX != Integer.MIN_VALUE)
                    g2.draw(new Line2D.Float(prevX, prevY, xp, yp));
                prevX = xp;
                prevY = yp;
            }
        }

        /** Maps data x index → pixel offset from plot-area left edge. */
        private static int xToPixel(int t, int seqLen, int pw) {
            if (seqLen <= 1) return pw / 2;
            return (int) Math.round((double) t / (seqLen - 1) * pw);
        }

        /** Maps data value → pixel offset from plot-area bottom (i.e. subtract from py+ph). */
        private static int yToPixel(double val, double yMin, double yMax, int ph) {
            double range = yMax - yMin;
            if (range < 1e-12) return ph / 2;
            double frac = (val - yMin) / range;
            frac = Math.max(0, Math.min(1, frac));
            return (int) Math.round(frac * ph);
        }

        /** Draws a string centred on (cx, cy). */
        private static void drawCentred(Graphics2D g2, String s, int cx, int cy) {
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, cx - fm.stringWidth(s) / 2, cy);
        }

        /** Draws a vertical (rotated −90°) title centred at pixel column x, row cy. */
        private static void drawVTitle(Graphics2D g2, String s, int x, int cy) {
            FontMetrics fm = g2.getFontMetrics();
            AffineTransform orig = g2.getTransform();
            g2.translate(x, cy);
            g2.rotate(-Math.PI / 2);
            g2.drawString(s, -fm.stringWidth(s) / 2, 0);
            g2.setTransform(orig);
        }

        private static Color withAlpha(Color c, int a) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
        }
    }
}
