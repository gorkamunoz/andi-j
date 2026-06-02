package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Line2D;
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
    private boolean showAlpha = true;
    private boolean showD     = true;

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
        trajField = new JTextField(24);
        trajField.setToolTipText(
            "Comma-separated TRACK_IDs to display, e.g.  1, 5, 20, 37\n"
            + "Up to 16 values.  Press Enter or click Apply.");

        add(buildTopBar(), BorderLayout.NORTH);

        // ── plot grid ────────────────────────────────────────────────────
        JPanel grid = new JPanel(new GridLayout(GRID_ROWS, GRID_COLS, 2, 2));
        grid.setBackground(new Color(200, 200, 200));
        plots = new TrajectoryPlot[N_PLOTS];
        for (int i = 0; i < N_PLOTS; i++) {
            plots[i] = new TrajectoryPlot();
            grid.add(plots[i]);
        }
        add(grid, BorderLayout.CENTER);

        // ── initial display ───────────────────────────────────────────────
        if (currentExp != null) sampleRandom();
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

        JButton applyBtn = new JButton("Apply");
        applyBtn.setToolTipText("Display the trajectories listed in the text field");
        applyBtn.addActionListener(e -> showSpecified());
        trajField.addActionListener(e -> showSpecified());  // Enter key

        // α toggle
        JToggleButton alphaToggle = new JToggleButton("α", true);
        alphaToggle.setForeground(ALPHA_COLOR);
        alphaToggle.setFont(alphaToggle.getFont().deriveFont(Font.BOLD, 12f));
        alphaToggle.setToolTipText("Toggle α visibility in all plots");
        alphaToggle.addActionListener(e -> {
            showAlpha = alphaToggle.isSelected();
            for (TrajectoryPlot p : plots) { p.showAlpha = showAlpha; p.repaint(); }
        });

        // D toggle
        JToggleButton dToggle = new JToggleButton("D", true);
        dToggle.setForeground(D_COLOR);
        dToggle.setFont(dToggle.getFont().deriveFont(Font.BOLD, 12f));
        dToggle.setToolTipText("Toggle log₁₀(D) visibility in all plots");
        dToggle.addActionListener(e -> {
            showD = dToggle.isSelected();
            for (TrajectoryPlot p : plots) { p.showD = showD; p.repaint(); }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEtchedBorder());

        if (experiments.size() > 1) {
            bar.add(new JLabel("Experiment:"));
            bar.add(expCombo);
            bar.add(Box.createHorizontalStrut(6));
        }
        bar.add(sampleBtn);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(new JLabel("Show trajectories:"));
        bar.add(trajField);
        bar.add(applyBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(alphaToggle);
        bar.add(dToggle);

        return bar;
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
                    plots[i].setData(id, alpha, logD);
                }
            }
            plots[i].showAlpha = showAlpha;
            plots[i].showD     = showD;
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
        private boolean  notFound  = false; // track ID not in experiment

        boolean showAlpha = true;
        boolean showD     = true;

        TrajectoryPlot() {
            setBackground(BG_COLOR);
        }

        /** Show data for an existing track. */
        void setData(int id, double[] alpha, double[] logD) {
            this.trackId  = id;
            this.alpha    = alpha;
            this.logD     = logD;
            this.notFound = false;
            repaint();
        }

        /** Track ID was requested but not found in the experiment. */
        void setEmpty(int id) {
            this.trackId  = id;
            this.alpha    = null;
            this.logD     = null;
            this.notFound = true;
            repaint();
        }

        /** No track assigned to this slot. */
        void clear() {
            this.trackId  = -1;
            this.alpha    = null;
            this.logD     = null;
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

            // Title
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 9f));
            drawCentred(g2, "Track #" + trackId, W / 2, PAD_T - 3);

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

            // ── Auto-scale logD axis ───────────────────────────────────────
            double dMin = Double.MAX_VALUE, dMax = -Double.MAX_VALUE;
            if (showD && logD != null) {
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
            if (showD && logD != null) {
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
            if (showAlpha && alpha != null) {
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

            // ── Clip to plot area before drawing lines ────────────────────
            Shape oldClip = g2.getClip();
            g2.setClip(px, py, pw, ph);

            // ── logD line (blue) ──────────────────────────────────────────
            if (showD && logD != null) {
                g2.setColor(D_COLOR);
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND,
                                             BasicStroke.JOIN_ROUND));
                drawLine(g2, logD, seqLen, dMin, dMax, px, py, pw, ph);
            }

            // ── α line (red) ──────────────────────────────────────────────
            if (showAlpha && alpha != null) {
                g2.setColor(ALPHA_COLOR);
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND,
                                             BasicStroke.JOIN_ROUND));
                drawLine(g2, alpha, seqLen, aMin, aMax, px, py, pw, ph);
            }

            g2.setClip(oldClip);
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
    }
}
