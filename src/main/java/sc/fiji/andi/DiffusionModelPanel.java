package sc.fiji.andi;

import ai.onnxruntime.OrtException;
import ij.IJ;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * "Diffusion Model" tab.
 *
 * Classifies each 2-D trajectory into one of the five canonical anomalous
 * diffusion models and shows, per experiment, the percentage of trajectories
 * predicted as each model in a grouped bar chart.
 *
 * <p>Predictions come from the V2 single-trajectory model
 * ({@link SingleTrajPredictor}); "Compute models (ML)" runs the classifier
 * (with a progress bar) and fills {@link ExperimentData#diffModel}.  Running
 * the ML α prediction in the Anomalous-exponent tab fills the same map, so the
 * bars appear here automatically in that case.</p>
 *
 * Experiment selection (right-side column of colour-coded toggles + "Deselect
 * all") mirrors the Turning Angles tab.  "Save models…" exports one row per
 * trajectory, matching the prediction files produced by the other tabs.
 */
public class DiffusionModelPanel extends JPanel {

    /**
     * The five anomalous-diffusion model classes, in the classifier's output
     * (logit) order.  Must match the training label order of the V2 model.
     */
    public static final String[] MODEL_NAMES = {"ATTM", "CTRW", "FBM", "LW", "SBM"};

    private final List<ExperimentData>         experiments;
    /** Working list = original experiments plus any tagged populations added later. */
    private final List<ExperimentData>         shown      = new ArrayList<>();
    private final Map<ExperimentData, Boolean> expVisible = new LinkedHashMap<>();

    /** Live predictor (null until first compute). */
    private SingleTrajPredictor predictor = null;

    private final BarPanel barPanel;
    private JComponent     sideColumn;

    // -----------------------------------------------------------------------

    DiffusionModelPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        this.shown.addAll(experiments);
        setBorder(new EmptyBorder(6, 6, 6, 6));

        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        // ── top bar (info) ───────────────────────────────────────────────
        JLabel infoLabel = new JLabel(
            "<html><i>Percentage of trajectories classified into each diffusion model " +
            "(2D). Click \"Compute models (ML)\" to run the classifier.</i></html>");
        infoLabel.setBorder(new EmptyBorder(2, 4, 4, 4));
        infoLabel.setForeground(new Color(80, 80, 80));
        add(infoLabel, BorderLayout.NORTH);

        // ── center: bar chart ────────────────────────────────────────────
        barPanel = new BarPanel();
        add(barPanel, BorderLayout.CENTER);

        // ── right column: experiment toggles + compute + save ─────────────
        sideColumn = buildSideColumn();
        add(sideColumn, BorderLayout.EAST);
    }

    /**
     * Adds a tagged population (from the D vs α tab) as a new dataset:
     * it gets its own toggle in the right column and its own grouped bars.
     */
    void addPopulation(ExperimentData exp) {
        if (shown.contains(exp)) return;
        shown.add(exp);
        expVisible.put(exp, true);
        remove(sideColumn);
        sideColumn = buildSideColumn();
        add(sideColumn, BorderLayout.EAST);
        revalidate();
        barPanel.repaint();
    }

    // -----------------------------------------------------------------------
    // Right-side experiment selector column
    // -----------------------------------------------------------------------

    private JComponent buildSideColumn() {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<JToggleButton> allBtns = new ArrayList<>();
        for (ExperimentData exp : shown) {
            JToggleButton btn = new JToggleButton(exp.name, expVisible.getOrDefault(exp, true));
            btn.setForeground(exp.color);
            btn.setFont(btn.getFont().deriveFont(Font.BOLD));
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            btn.setFocusPainted(false);
            btn.addActionListener(e -> {
                expVisible.put(exp, btn.isSelected());
                barPanel.repaint();
            });
            col.add(btn);
            col.add(Box.createVerticalStrut(3));
            allBtns.add(btn);
        }

        JButton deselectAll = new JButton("Deselect all");
        deselectAll.setFont(deselectAll.getFont().deriveFont(Font.PLAIN, 10f));
        deselectAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        deselectAll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        deselectAll.setFocusPainted(false);
        deselectAll.addActionListener(e -> {
            for (ExperimentData exp : shown) expVisible.put(exp, false);
            allBtns.forEach(b -> b.setSelected(false));
            barPanel.repaint();
        });
        col.add(Box.createVerticalStrut(6));
        col.add(deselectAll);

        col.add(Box.createVerticalGlue());

        JButton computeBtn = new JButton("Compute models (ML)");
        computeBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        computeBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        computeBtn.setFocusPainted(false);
        computeBtn.addActionListener(e -> computeModels());
        col.add(computeBtn);
        col.add(Box.createVerticalStrut(4));

        JButton saveBtn = new JButton("Save models…");
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        saveBtn.setFocusPainted(false);
        saveBtn.addActionListener(e -> saveModels());
        col.add(saveBtn);

        JScrollPane scroll = new JScrollPane(col,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(160, 0));
        scroll.setBorder(BorderFactory.createTitledBorder("Experiments"));
        return scroll;
    }

    // -----------------------------------------------------------------------
    // ML prediction (V2 single-trajectory model)
    // -----------------------------------------------------------------------

    private void computeModels() {
        // Lazily load the bundled model (also lets the user pick a file on failure)
        if (predictor == null) {
            try {
                predictor = SingleTrajPredictor.fromResource();
            } catch (Exception ex) {
                int choice = JOptionPane.showConfirmDialog(this,
                    "<html>No bundled model was found.<br>"
                  + "Run <tt>convert_single_traj_v2_to_onnx.py</tt> and rebuild, or load an "
                  + ".onnx file now?<br><br>Details: " + ex.getMessage() + "</html>",
                    "Diffusion-model classifier", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.OK_OPTION) return;
                JFileChooser fc = new JFileChooser();
                fc.setFileFilter(new FileNameExtensionFilter("ONNX models (*.onnx)", "onnx"));
                if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
                try {
                    predictor = new SingleTrajPredictor(fc.getSelectedFile());
                } catch (Exception ex2) {
                    JOptionPane.showMessageDialog(this,
                        "Could not load model:\n" + ex2.getMessage(),
                        "Diffusion-model classifier", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        }

        // ── progress dialog over number of trajectories ──────────────────
        final int nTracks     = shown.stream().mapToInt(e -> e.tracks.size()).sum();
        final int totalTracks = Math.max(1, nTracks);

        JDialog prog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "AnDi – Classifying diffusion models", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel pp = new JPanel(new BorderLayout(12, 12));
        pp.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        pp.add(new JLabel("<html>Running the classifier on <b>" + nTracks
                + "</b> track(s)…</html>"), BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar(0, totalTracks);
        bar.setStringPainted(true);
        bar.setString("0 / " + nTracks);
        bar.setPreferredSize(new Dimension(320, 20));
        pp.add(bar, BorderLayout.CENTER);
        prog.setContentPane(pp);
        prog.setResizable(false);
        prog.pack();
        prog.setLocationRelativeTo(this);

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            private Exception error;
            private final int[] done = {0};

            @Override
            protected Void doInBackground() {
                try {
                    SingleTrajPredictor.ProgressListener tick = () -> {
                        int d = ++done[0];
                        SwingUtilities.invokeLater(() -> {
                            bar.setValue(d);
                            bar.setString(d + " / " + nTracks);
                        });
                    };
                    for (ExperimentData exp : shown)
                        predictor.computeForExperiment(exp, tick);
                } catch (OrtException ex) {
                    error = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                prog.dispose();
                if (error != null) {
                    JOptionPane.showMessageDialog(DiffusionModelPanel.this,
                        "Classification failed:\n" + error.getMessage(),
                        "Diffusion-model classifier", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                barPanel.repaint();
            }
        };
        worker.execute();
        prog.setVisible(true);
    }

    // -----------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------

    private void saveModels() {
        if (shown.stream().noneMatch(ExperimentData::hasDiffModel)) {
            JOptionPane.showMessageDialog(this,
                "No diffusion-model predictions available.",
                "Save models", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle("Save diffusion-model predictions");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");

        boolean multiExp = shown.stream().filter(ExperimentData::hasDiffModel).count() > 1;

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            if (multiExp) pw.println("experiment,traj_idx,model");
            else          pw.println("traj_idx,model");

            for (ExperimentData exp : shown) {
                if (!exp.hasDiffModel()) continue;
                for (Map.Entry<Integer, Integer> entry : exp.diffModel.entrySet()) {
                    int    id    = entry.getKey();
                    String model = MODEL_NAMES[entry.getValue()];
                    if (multiExp) pw.printf("%s,%d,%s%n", exp.name, id, model);
                    else          pw.printf("%d,%s%n", id, model);
                }
            }
            IJ.log("AnDi: diffusion-model predictions saved → " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not save file:\n" + ex.getMessage(),
                "Save models – error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =======================================================================
    // Grouped bar chart
    // =======================================================================

    private class BarPanel extends JPanel {

        private static final int ML = 56, MR = 22, MT = 46, MB = 42;

        BarPanel() {
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 40 || ph < 40) { g2.dispose(); return; }

            // ── collect visible experiments with predictions ──────────────
            List<ExperimentData> visible = new ArrayList<>();
            for (ExperimentData exp : shown)
                if (expVisible.getOrDefault(exp, true) && exp.hasDiffModel())
                    visible.add(exp);

            // ── compute per-experiment percentages [exp][model] ───────────
            int nModels = MODEL_NAMES.length;
            double[][] pct = new double[visible.size()][nModels];
            for (int ei = 0; ei < visible.size(); ei++) {
                int[] counts = new int[nModels];
                int total = 0;
                for (int m : visible.get(ei).diffModel.values()) {
                    counts[m]++; total++;
                }
                if (total > 0)
                    for (int m = 0; m < nModels; m++)
                        pct[ei][m] = 100.0 * counts[m] / total;
            }

            // ── y-axis scale (nice ceiling of the largest bar) ────────────
            double maxPct = 0;
            for (double[] row : pct) for (double v : row) maxPct = Math.max(maxPct, v);
            double yMax = niceCeil(Math.max(maxPct, 1));

            // ── plot frame ────────────────────────────────────────────────
            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            if (visible.isEmpty()) {
                boolean anyComputed = shown.stream().anyMatch(ExperimentData::hasDiffModel);
                String msg = anyComputed ? "No experiments selected"
                                         : "Click \"Compute models (ML)\" to run the classifier";
                g2.setColor(new Color(160, 160, 160));
                g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, px + (pw - fm.stringWidth(msg)) / 2,
                              py + ph / 2 + fm.getAscent() / 2);
                drawFrameAndModelLabels(g2, px, py, pw, ph, nModels);
                g2.dispose();
                return;
            }

            // ── horizontal grid + y ticks ─────────────────────────────────
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double yStep = niceStep(yMax, 5);
            for (double t = 0; t <= yMax + yStep * 0.01; t += yStep) {
                int yp = py + ph - (int) (t / yMax * ph);
                g2.setColor(new Color(225, 225, 225));
                g2.setStroke(new BasicStroke(0.5f));
                g2.drawLine(px, yp, px + pw, yp);
                g2.setColor(Color.BLACK);
                g2.drawLine(px - 4, yp, px, yp);
                String lbl = fmtPct(t);
                g2.drawString(lbl, px - 6 - fm.stringWidth(lbl), yp + fm.getAscent() / 2 - 1);
            }

            // ── grouped bars ──────────────────────────────────────────────
            int nExp = visible.size();
            double groupW = (double) pw / nModels;
            double barsW  = groupW * 0.78;            // 78% of group used by bars
            double barW   = barsW / nExp;
            double gap0   = (groupW - barsW) / 2.0;   // left padding inside each group

            for (int mi = 0; mi < nModels; mi++) {
                double groupX = px + mi * groupW;
                for (int ei = 0; ei < nExp; ei++) {
                    double v = pct[ei][mi];
                    int barH = (int) (v / yMax * ph);
                    int bx   = (int) (groupX + gap0 + ei * barW);
                    int by   = py + ph - barH;
                    int bw   = Math.max(1, (int) barW - 1);

                    Color base = visible.get(ei).color;
                    g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 200));
                    g2.fillRect(bx, by, bw, barH);
                    g2.setColor(base.darker());
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.drawRect(bx, by, bw, barH);
                }
            }

            drawFrameAndModelLabels(g2, px, py, pw, ph, nModels);

            // ── y-axis title ──────────────────────────────────────────────
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            java.awt.geom.AffineTransform orig = g2.getTransform();
            String yTitle = "% of trajectories";
            FontMetrics fm2 = g2.getFontMetrics();
            g2.translate(13, py + ph / 2.0);
            g2.rotate(-Math.PI / 2);
            g2.drawString(yTitle, -fm2.stringWidth(yTitle) / 2, fm2.getAscent() / 2 - 6);
            g2.setTransform(orig);

            // ── legend (top) ──────────────────────────────────────────────
            drawLegend(g2, visible, px, pw);

            g2.dispose();
        }

        private void drawFrameAndModelLabels(Graphics2D g2, int px, int py,
                                             int pw, int ph, int nModels) {
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);

            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            FontMetrics fm = g2.getFontMetrics();
            double groupW = (double) pw / nModels;
            for (int mi = 0; mi < nModels; mi++) {
                String lbl = MODEL_NAMES[mi];
                int cx = (int) (px + (mi + 0.5) * groupW);
                g2.drawString(lbl, cx - fm.stringWidth(lbl) / 2, py + ph + 16);
            }
        }

        private void drawLegend(Graphics2D g2, List<ExperimentData> visible,
                                int px, int pw) {
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int x = px, y = 14;
            for (ExperimentData exp : visible) {
                g2.setColor(exp.color);
                g2.fillRect(x, y - 9, 12, 12);
                g2.setColor(new Color(80, 80, 80));
                g2.drawRect(x, y - 9, 12, 12);
                g2.setColor(Color.BLACK);
                g2.drawString(exp.name, x + 16, y + 1);
                x += 16 + fm.stringWidth(exp.name) + 18;
            }
        }

        // ── helpers ───────────────────────────────────────────────────────

        private double niceCeil(double v) {
            double step = niceStep(v, 5);
            return Math.ceil(v / step) * step;
        }

        private double niceStep(double range, int target) {
            if (range <= 0) return 1;
            double rough = range / target;
            double base  = Math.pow(10, Math.floor(Math.log10(rough)));
            for (double m : new double[]{1, 2, 2.5, 5, 10})
                if (m * base >= rough) return m * base;
            return base * 10;
        }

        private String fmtPct(double v) {
            if (Math.abs(v - Math.rint(v)) < 1e-9) return String.format("%d%%", (int) Math.rint(v));
            return String.format("%.1f%%", v);
        }
    }
}
