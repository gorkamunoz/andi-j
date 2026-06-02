package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * "D vs α" density tab for Trajectory Segmentation.
 *
 * <p>Displays a 2-D histogram (heatmap) of the per-frame STEP predictions:
 * <ul>
 *   <li>X axis – anomalous exponent α ∈ [0, 2]  (fixed range)</li>
 *   <li>Y axis – log₁₀(D), auto-ranged from data</li>
 *   <li>Color  – white (0 frames) → experiment color (maximum density)</li>
 * </ul>
 * The colour intensity is mapped from the bin count either linearly or in
 * log scale (toggleable), making both sparse and dense regions visible.
 *
 * <p>A vertical colour-scale bar on the right translates colour back to
 * approximate frame count.  The total frame count is printed inside the plot.
 *
 * <p>Controls: experiment selector (includes "All"), bin count field,
 * log-count toggle.
 */
public class SegDvsAlphaPanel extends JPanel {

    private static final String ALL_LABEL = "All experiments";

    private final List<ExperimentData> experiments;

    private final JComboBox<String> expCombo;
    private final JTextField        binsField;
    private final JCheckBox         logCountBox;
    private final DensityCanvas     canvas;

    // ═════════════════════════════════════════════════════════════════════
    // Constructor
    // ═════════════════════════════════════════════════════════════════════

    public SegDvsAlphaPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;

        String[] items = new String[experiments.size() + 1];
        items[0] = ALL_LABEL;
        for (int i = 0; i < experiments.size(); i++) items[i + 1] = experiments.get(i).name;
        expCombo = new JComboBox<>(items);

        binsField   = new JTextField("40", 5);
        logCountBox = new JCheckBox("Log count scale", true);
        canvas      = new DensityCanvas();

        add(buildTopBar(), BorderLayout.NORTH);
        add(canvas,        BorderLayout.CENTER);

        // Listeners
        expCombo.addActionListener(e -> rebuild());
        binsField.addActionListener(e -> rebuild());
        logCountBox.addActionListener(e -> {
            canvas.logScale = logCountBox.isSelected();
            canvas.repaint();
        });

        // Auto-compute immediately if data are available
        if (experiments.stream().anyMatch(ExperimentData::hasStepPredictions))
            rebuild();
    }

    // ─────────────────────────────────────────────────────────────────────

    private JPanel buildTopBar() {
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Recompute the histogram");
        refreshBtn.addActionListener(e -> rebuild());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEtchedBorder());
        bar.add(new JLabel("Experiment:"));
        bar.add(expCombo);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(new JLabel("Bins:"));
        bar.add(binsField);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(logCountBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(refreshBtn);
        return bar;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Histogram computation
    // ═════════════════════════════════════════════════════════════════════

    private void rebuild() {
        int nBins = parseBins(binsField.getText());

        // Determine which experiments to include
        int                  sel    = expCombo.getSelectedIndex();
        List<ExperimentData> target = new ArrayList<>();
        if (sel <= 0) {
            target.addAll(experiments);
        } else {
            target.add(experiments.get(sel - 1));
        }

        // Gather all (α, logD) pairs and find the logD range
        List<double[]> pts = new ArrayList<>();
        for (ExperimentData exp : target) {
            if (!exp.hasStepPredictions()) continue;
            for (Map.Entry<Integer, double[]> e : exp.stepAlpha.entrySet()) {
                double[] alArr = e.getValue();
                double[] ldArr = exp.stepLogD != null ? exp.stepLogD.get(e.getKey()) : null;
                if (alArr == null || ldArr == null) continue;
                int len = Math.min(alArr.length, ldArr.length);
                for (int t = 0; t < len; t++) {
                    if (Double.isFinite(alArr[t]) && Double.isFinite(ldArr[t]))
                        pts.add(new double[]{alArr[t], ldArr[t]});
                }
            }
        }

        if (pts.isEmpty()) {
            canvas.setEmpty();
            return;
        }

        // Auto-range for log₁₀(D)
        double ldMin = Double.MAX_VALUE, ldMax = -Double.MAX_VALUE;
        for (double[] p : pts) {
            ldMin = Math.min(ldMin, p[1]);
            ldMax = Math.max(ldMax, p[1]);
        }
        double lRng = ldMax - ldMin;
        if (lRng < 1e-6) { ldMin -= 0.5; ldMax += 0.5; lRng = 1; }
        ldMin -= 0.03 * lRng;
        ldMax += 0.03 * lRng;

        // α is always [0, 2]
        final double aMin = 0.0, aMax = 2.0;

        // Fill 2-D histogram:  hist[αBin][logDBin]
        int[][] hist = new int[nBins][nBins];
        for (double[] p : pts) {
            int ai = bin(p[0], aMin, aMax, nBins);
            int li = bin(p[1], ldMin, ldMax, nBins);
            hist[ai][li]++;
        }

        // Base colour: experiment colour or a neutral dark-blue for "All"
        Color baseColor = (sel > 0 && !target.isEmpty())
                          ? target.get(0).color
                          : new Color(0, 50, 180);

        canvas.setData(hist, nBins, aMin, aMax, ldMin, ldMax, pts.size(), baseColor);
        canvas.logScale = logCountBox.isSelected();
        canvas.repaint();
    }

    /** Returns the bin index for value v in [vMin, vMax) clamped to [0, nBins-1]. */
    private static int bin(double v, double vMin, double vMax, int n) {
        int b = (int) ((v - vMin) / (vMax - vMin) * n);
        return Math.max(0, Math.min(n - 1, b));
    }

    private static int parseBins(String s) {
        try { return Math.max(5, Math.min(200, Integer.parseInt(s.trim()))); }
        catch (NumberFormatException e) { return 40; }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inner canvas
    // ═════════════════════════════════════════════════════════════════════

    static class DensityCanvas extends JPanel {

        // Margins (pixels)
        private static final int ML = 72, MR = 90, MT = 22, MB = 50;
        // Colour-bar width
        private static final int CBAR_W = 14;

        // ── Current dataset ───────────────────────────────────────────────
        private int[][]  hist;
        private int      nBins;
        private double   aMin, aMax;
        private double   ldMin, ldMax;
        private int      totalPts;
        private Color    baseColor = new Color(0, 50, 180);
        private boolean  hasData   = false;
        boolean          logScale  = true;

        DensityCanvas() {
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(640, 500));
        }

        void setData(int[][] hist, int nBins,
                     double aMin, double aMax,
                     double ldMin, double ldMax,
                     int totalPts, Color baseColor) {
            this.hist      = hist;
            this.nBins     = nBins;
            this.aMin      = aMin;  this.aMax  = aMax;
            this.ldMin     = ldMin; this.ldMax = ldMax;
            this.totalPts  = totalPts;
            this.baseColor = baseColor;
            this.hasData   = true;
        }

        void setEmpty() { hasData = false; repaint(); }

        // ── Paint ─────────────────────────────────────────────────────────

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
            if (pw < 20 || ph < 20) { g2.dispose(); return; }

            // White plot background
            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            if (!hasData) {
                drawMsg(g2, "No STEP predictions available — run Perform Analysis first",
                        px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose();
                return;
            }

            // Find max count
            int maxCount = 0;
            for (int[] col : hist)
                for (int v : col)
                    maxCount = Math.max(maxCount, v);

            if (maxCount == 0) {
                drawMsg(g2, "All bins empty", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose();
                return;
            }

            // ── Draw histogram cells ──────────────────────────────────────
            double cellW = (double) pw / nBins;
            double cellH = (double) ph / nBins;
            double logMax = Math.log1p(maxCount);

            Shape oldClip = g2.getClip();
            g2.setClip(px, py, pw, ph);

            for (int ai = 0; ai < nBins; ai++) {
                for (int li = 0; li < nBins; li++) {
                    int cnt = hist[ai][li];
                    if (cnt == 0) continue;

                    double intensity = logScale
                            ? Math.log1p(cnt) / logMax
                            : (double) cnt / maxCount;

                    int cx = px + (int) (ai * cellW);
                    // Y-axis: logD increases upward, so li=0 → bottom of plot
                    int cy = py + ph - (int) ((li + 1) * cellH);
                    int cw = (int) Math.ceil(cellW) + 1;
                    int ch = (int) Math.ceil(cellH) + 1;

                    g2.setColor(mapColor(intensity, baseColor));
                    g2.fillRect(cx, cy, cw, ch);
                }
            }

            g2.setClip(oldClip);

            // ── Overlay grid ──────────────────────────────────────────────
            drawGrid(g2, aMin, aMax, ldMin, ldMax, px, py, pw, ph);

            // ── Frame, axes, colour bar ───────────────────────────────────
            drawFrame(g2, px, py, pw, ph);
            drawXTicks(g2, aMin, aMax,   px, py, pw, ph);
            drawYTicks(g2, ldMin, ldMax, px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            drawColorBar(g2, maxCount, logMax, px, py, pw, ph);
            drawFrameCount(g2, totalPts, px, py, pw);

            g2.dispose();
        }

        // ── Colour mapping ────────────────────────────────────────────────

        /**
         * Maps intensity ∈ [0, 1] to a colour linearly from white (0) to
         * {@code base} (1).
         */
        static Color mapColor(double t, Color base) {
            t = Math.max(0, Math.min(1, t));
            int r = (int) (255 + (base.getRed()   - 255) * t);
            int grn = (int) (255 + (base.getGreen() - 255) * t);
            int b = (int) (255 + (base.getBlue()  - 255) * t);
            return new Color(clamp(r), clamp(grn), clamp(b));
        }

        private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

        // ── Colour-scale bar ──────────────────────────────────────────────

        /**
         * Draws a vertical gradient bar and tick labels showing how colour
         * maps back to approximate frame counts.
         */
        private void drawColorBar(Graphics2D g2, int maxCount, double logMax,
                                  int px, int py, int pw, int ph) {
            int bx = px + pw + 18;
            int by = py;
            int bh = ph;

            // Gradient: bottom = 0 (white), top = maxCount (full colour)
            for (int y = 0; y < bh; y++) {
                double frac = (double)(bh - 1 - y) / (bh - 1);  // 0=bottom, 1=top
                g2.setColor(mapColor(frac, baseColor));
                g2.fillRect(bx, by + y, CBAR_W, 1);
            }

            // Border
            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(0.8f));
            g2.drawRect(bx, by, CBAR_W, bh);

            // Tick marks + count labels
            // frac → colour → count:
            //   if logScale:  cnt = expm1(frac * logMax)
            //   if linear:    cnt = frac * maxCount
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            FontMetrics fm = g2.getFontMetrics();
            int nTicks = 5;
            for (int i = 0; i <= nTicks; i++) {
                double frac = (double) i / nTicks;
                int    yp   = by + bh - (int)(frac * bh);
                g2.setColor(Color.DARK_GRAY);
                g2.drawLine(bx + CBAR_W, yp, bx + CBAR_W + 3, yp);

                double cnt = logScale
                        ? Math.expm1(frac * logMax)
                        : frac * maxCount;
                String lbl = cnt < 1 ? "0"
                           : cnt < 10000 ? String.valueOf((int) Math.round(cnt))
                           : String.format("%.1e", cnt).replace("e+0","e").replace("e+","e");
                g2.drawString(lbl, bx + CBAR_W + 5, yp + fm.getAscent() / 2 - 1);
            }

            // "count" label (rotated, to the right of the bar)
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            String title = logScale ? "log(count)" : "count";
            AffineTransform orig = g2.getTransform();
            g2.translate(bx + CBAR_W + 52, by + bh / 2.0);
            g2.rotate(-Math.PI / 2);
            fm = g2.getFontMetrics();
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(title, -fm.stringWidth(title) / 2, fm.getAscent() / 2);
            g2.setTransform(orig);
        }

        // ── Grid lines ────────────────────────────────────────────────────

        private static void drawGrid(Graphics2D g2,
                                     double xMin, double xMax,
                                     double yMin, double yMax,
                                     int px, int py, int pw, int ph) {
            g2.setColor(new Color(0, 0, 0, 40));
            g2.setStroke(new BasicStroke(0.5f));

            double xStep = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / xStep) * xStep;
                 t <= xMax + xStep * 0.01; t += xStep) {
                int xp = mapXPx(t, xMin, xMax, px, pw);
                g2.drawLine(xp, py, xp, py + ph);
            }

            double yStep = niceStep(yMax - yMin, 5);
            for (double t = Math.ceil(yMin / yStep) * yStep;
                 t <= yMax + yStep * 0.01; t += yStep) {
                int yp = mapYPx(t, yMin, yMax, py, ph);
                g2.drawLine(px, yp, px + pw, yp);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // ── Axis ticks ────────────────────────────────────────────────────

        private static void drawXTicks(Graphics2D g2,
                                       double xMin, double xMax,
                                       int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / step) * step;
                 t <= xMax + step * 0.01; t += step) {
                int xp = mapXPx(t, xMin, xMax, px, pw);
                g2.drawLine(xp, py + ph, xp, py + ph + 4);
                String lbl = fmtTick(t);
                g2.drawString(lbl, xp - fm.stringWidth(lbl) / 2, py + ph + 15);
            }
        }

        private static void drawYTicks(Graphics2D g2,
                                       double yMin, double yMax,
                                       int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = niceStep(yMax - yMin, 5);
            for (double t = Math.ceil(yMin / step) * step;
                 t <= yMax + step * 0.01; t += step) {
                int yp = mapYPx(t, yMin, yMax, py, ph);
                g2.drawLine(px - 4, yp, px, yp);
                String lbl = fmtTick(t);
                g2.drawString(lbl,
                              px - 6 - fm.stringWidth(lbl),
                              yp + fm.getAscent() / 2 - 1);
            }
        }

        // ── Axis titles ───────────────────────────────────────────────────

        private static void drawAxisTitles(Graphics2D g2,
                                           int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();

            String xTitle = "α  (anomalous exponent)";  // α
            g2.drawString(xTitle,
                    px + (pw - fm.stringWidth(xTitle)) / 2,
                    py + ph + MB - 8);

            String yTitle = "log₁₀(D)";            // log₁₀(D)
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph / 2.0);
            g2.rotate(-Math.PI / 2);
            g2.drawString(yTitle, -fm.stringWidth(yTitle) / 2, fm.getAscent() / 2);
            g2.setTransform(orig);
        }

        // ── Frame count annotation ────────────────────────────────────────

        private static void drawFrameCount(Graphics2D g2, int n, int px, int py, int pw) {
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g2.setColor(new Color(80, 80, 80));
            String s = "N = " + String.format("%,d", n) + " frames";
            FontMetrics fm = g2.getFontMetrics();
            // top-right corner inside the plot
            g2.drawString(s, px + pw - fm.stringWidth(s) - 6, py + 14);
        }

        // ── Helpers ───────────────────────────────────────────────────────

        private static void drawFrame(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);
        }

        private static void drawMsg(Graphics2D g2, String msg,
                                    int px, int py, int pw, int ph) {
            g2.setColor(new Color(150, 150, 150));
            g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg,
                    px + (pw - fm.stringWidth(msg)) / 2,
                    py + ph / 2 + fm.getAscent() / 2);
        }

        /** Maps data x (linear α axis) → pixel. */
        private static int mapXPx(double v, double vMin, double vMax, int ox, int w) {
            if (vMax == vMin) return ox;
            return ox + (int) ((v - vMin) / (vMax - vMin) * w);
        }

        /** Maps data y (log₁₀(D), linear axis) → pixel (y increases upward). */
        private static int mapYPx(double v, double vMin, double vMax, int oy, int h) {
            if (vMax == vMin) return oy + h;
            return oy + h - (int) ((v - vMin) / (vMax - vMin) * h);
        }

        static double niceStep(double range, int target) {
            if (range <= 0) return 1;
            double rough = range / target;
            int    exp   = (int) Math.floor(Math.log10(rough));
            double base  = Math.pow(10, exp);
            for (double m : new double[]{1, 2, 2.5, 5, 10})
                if (m * base >= rough) return m * base;
            return base * 10;
        }

        static String fmtTick(double v) {
            if (v == 0) return "0";
            double abs = Math.abs(v);
            if (abs >= 0.01 && abs < 1000)
                return String.format("%.4g", v)
                             .replaceAll("0+$", "").replaceAll("\\.$", "");
            return String.format("%.2e", v)
                         .replace("e+0", "e").replace("e-0", "e-").replace("e+", "e");
        }
    }
}
