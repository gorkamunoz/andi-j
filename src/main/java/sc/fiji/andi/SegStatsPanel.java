package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * "Segment statistics" tab for Trajectory Segmentation.
 *
 * <p>Built from the change-point {@link Segment}s of the selected experiments,
 * it shows three plots:
 * <ul>
 *   <li><b>Segment-length histogram</b> – 1-D distribution of segment durations,
 *       one outlined curve per experiment.</li>
 *   <li><b>Time vs α</b> – 2-D density heatmap: for every frame covered by a
 *       segment, the point (frame, segment-α).</li>
 *   <li><b>Time vs D</b> – same, with the segment's log₁₀(D).</li>
 * </ul>
 * The two heatmaps follow the same style as the "D vs α" tab.
 *
 * <p>Segments come from {@link ExperimentData#segments} (computed in the
 * Visualization tab with the current parameters); "Refresh" re-reads them.</p>
 */
public class SegStatsPanel extends JPanel {

    private final List<ExperimentData>         experiments;
    private final Map<ExperimentData, Boolean> expVisible = new LinkedHashMap<>();

    private final JTextField binsField   = new JTextField("40", 5);
    private final JCheckBox  logCountBox  = new JCheckBox("Log count scale", true);

    private final Hist1D  lengthHist = new Hist1D();
    private final Heat2D  timeAlpha  = new Heat2D("Time (frame)", "α");
    private final Heat2D  timeD      = new Heat2D("Time (frame)", "log₁₀(D)");

    // ═════════════════════════════════════════════════════════════════════

    public SegStatsPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        add(buildTopBar(), BorderLayout.NORTH);

        JScrollPane leftScroll = new JScrollPane(buildExperimentsPanel());
        leftScroll.setPreferredSize(new Dimension(160, 0));
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));
        add(leftScroll, BorderLayout.WEST);

        JPanel heatRow = new JPanel(new GridLayout(1, 2, 4, 0));
        heatRow.add(timeAlpha);
        heatRow.add(timeD);

        JPanel center = new JPanel(new BorderLayout(0, 4));
        lengthHist.setPreferredSize(new Dimension(0, 220));
        center.add(lengthHist, BorderLayout.NORTH);
        center.add(heatRow,    BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        binsField.addActionListener(e -> rebuild());
        logCountBox.addActionListener(e -> {
            timeAlpha.logScale = logCountBox.isSelected();
            timeD.logScale     = logCountBox.isSelected();
            timeAlpha.repaint(); timeD.repaint();
        });

        rebuild();
    }

    private JPanel buildTopBar() {
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Recompute from the current segments");
        refreshBtn.addActionListener(e -> rebuild());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEtchedBorder());
        bar.add(new JLabel("Bins:"));
        bar.add(binsField);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(logCountBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(refreshBtn);
        return bar;
    }

    private JPanel buildExperimentsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<JToggleButton> allBtns = new ArrayList<>();
        for (ExperimentData exp : experiments) {
            JToggleButton btn = new JToggleButton(exp.name, true);
            btn.setForeground(exp.color);
            btn.setFont(btn.getFont().deriveFont(Font.BOLD));
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            btn.setFocusPainted(false);
            btn.addActionListener(e -> { expVisible.put(exp, btn.isSelected()); rebuild(); });
            panel.add(btn);
            panel.add(Box.createVerticalStrut(3));
            allBtns.add(btn);
        }

        JButton deselectAll = new JButton("Deselect all");
        deselectAll.setFont(deselectAll.getFont().deriveFont(Font.PLAIN, 10f));
        deselectAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        deselectAll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        deselectAll.setFocusPainted(false);
        deselectAll.addActionListener(e -> {
            for (ExperimentData exp : experiments) expVisible.put(exp, false);
            allBtns.forEach(b -> b.setSelected(false));
            rebuild();
        });
        panel.add(Box.createVerticalStrut(6));
        panel.add(deselectAll);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Compute
    // ═════════════════════════════════════════════════════════════════════

    private void rebuild() {
        int nBins = parseBins(binsField.getText());

        // Make sure segments exist (compute with defaults if the viz tab hasn't yet)
        for (ExperimentData exp : experiments)
            if (exp.hasStepPredictions() && !exp.hasSegments())
                ChangePointDetector.computeForExperiment(exp, new SegmentationParams());

        List<ExperimentData> sel = new ArrayList<>();
        for (ExperimentData exp : experiments)
            if (expVisible.getOrDefault(exp, true) && exp.hasSegments())
                sel.add(exp);

        // ── gather ──────────────────────────────────────────────────────
        int maxLen = 1, maxTime = 1;
        double ldMin = Double.MAX_VALUE, ldMax = -Double.MAX_VALUE;
        for (ExperimentData exp : sel) {
            for (List<Segment> segs : exp.segments.values()) {
                for (Segment s : segs) {
                    maxLen  = Math.max(maxLen, s.length);
                    maxTime = Math.max(maxTime, s.start + s.length - 1);
                    if (Double.isFinite(s.logD)) {
                        ldMin = Math.min(ldMin, s.logD);
                        ldMax = Math.max(ldMax, s.logD);
                    }
                }
            }
        }
        if (ldMin > ldMax) { ldMin = -2; ldMax = 2; }
        double lRng = ldMax - ldMin;
        if (lRng < 1e-6) { ldMin -= 0.5; ldMax += 0.5; lRng = 1; }
        ldMin -= 0.03 * lRng; ldMax += 0.03 * lRng;

        final double aMin = 0.0, aMax = 2.0;
        final double tMin = 0.0, tMax = maxTime + 1.0;

        // ── segment-length histogram (per experiment) ─────────────────────
        int lenBins = Math.max(1, Math.min(nBins, maxLen));
        double lenMin = 0.5, lenMax = maxLen + 0.5;     // integer-centred bins
        List<int[]> lenCounts = new ArrayList<>();
        List<Color> lenColors = new ArrayList<>();
        List<String> lenNames = new ArrayList<>();
        for (ExperimentData exp : sel) {
            int[] counts = new int[lenBins];
            for (List<Segment> segs : exp.segments.values())
                for (Segment s : segs)
                    counts[bin(s.length, lenMin, lenMax, lenBins)]++;
            lenCounts.add(counts);
            lenColors.add(exp.color);
            lenNames.add(exp.name);
        }
        lengthHist.setData(lenMin, lenMax, lenBins, lenCounts, lenColors, lenNames);

        // ── time-vs-α and time-vs-D 2-D histograms (combined) ─────────────
        int[][] histA = new int[nBins][nBins];
        int[][] histD = new int[nBins][nBins];
        int totA = 0, totD = 0;
        for (ExperimentData exp : sel) {
            for (List<Segment> segs : exp.segments.values()) {
                for (Segment s : segs) {
                    for (int t = s.start; t < s.start + s.length; t++) {
                        int xi = bin(t, tMin, tMax, nBins);
                        histA[xi][bin(s.alpha, aMin, aMax, nBins)]++;
                        totA++;
                        if (Double.isFinite(s.logD)) {
                            histD[xi][bin(s.logD, ldMin, ldMax, nBins)]++;
                            totD++;
                        }
                    }
                }
            }
        }

        Color base = sel.size() == 1 ? sel.get(0).color : new Color(0, 50, 180);
        boolean log = logCountBox.isSelected();
        if (totA > 0) timeAlpha.setHist(histA, nBins, tMin, tMax, aMin, aMax, totA, base);
        else          timeAlpha.setEmpty();
        if (totD > 0) timeD.setHist(histD, nBins, tMin, tMax, ldMin, ldMax, totD, base);
        else          timeD.setEmpty();
        timeAlpha.logScale = log; timeD.logScale = log;

        lengthHist.repaint(); timeAlpha.repaint(); timeD.repaint();
    }

    private static int bin(double v, double vMin, double vMax, int n) {
        int b = (int) ((v - vMin) / (vMax - vMin) * n);
        return Math.max(0, Math.min(n - 1, b));
    }

    private static int parseBins(String s) {
        try { return Math.max(5, Math.min(200, Integer.parseInt(s.trim()))); }
        catch (NumberFormatException e) { return 40; }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Shared drawing helpers
    // ═════════════════════════════════════════════════════════════════════

    static double niceStep(double range, int target) {
        if (range <= 0) return 1;
        double rough = range / target;
        int exp = (int) Math.floor(Math.log10(rough));
        double base = Math.pow(10, exp);
        for (double m : new double[]{1, 2, 2.5, 5, 10}) if (m * base >= rough) return m * base;
        return base * 10;
    }

    static String fmtTick(double v) {
        if (v == 0) return "0";
        double abs = Math.abs(v);
        if (abs >= 0.01 && abs < 10000)
            return String.format("%.4g", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return String.format("%.2e", v).replace("e+0", "e").replace("e-0", "e-").replace("e+", "e");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1-D segment-length histogram
    // ═════════════════════════════════════════════════════════════════════

    static class Hist1D extends JPanel {
        private static final int ML = 56, MR = 16, MT = 26, MB = 40;

        private double xMin, xMax;
        private int    nb;
        private List<int[]>  counts = java.util.Collections.emptyList();
        private List<Color>  colors = java.util.Collections.emptyList();
        private List<String> names  = java.util.Collections.emptyList();

        Hist1D() { setBackground(Color.WHITE); }

        void setData(double xMin, double xMax, int nb,
                     List<int[]> counts, List<Color> colors, List<String> names) {
            this.xMin = xMin; this.xMax = xMax; this.nb = nb;
            this.counts = counts; this.colors = colors; this.names = names;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 20 || ph < 20) { g2.dispose(); return; }
            g2.setColor(Color.WHITE); g2.fillRect(px, py, pw, ph);

            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g2.drawString("Segment-length distribution", px, py - 10);

            int maxCount = 0;
            for (int[] c : counts) for (int v : c) maxCount = Math.max(maxCount, v);
            if (counts.isEmpty() || maxCount == 0) {
                g2.setColor(new Color(150, 150, 150));
                g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
                String m = "No segments";
                g2.drawString(m, px + (pw - g2.getFontMetrics().stringWidth(m)) / 2, py + ph / 2);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose(); return;
            }
            double yMax = niceCeil(maxCount);

            // grid + y ticks
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double yStep = niceStep(yMax, 4);
            for (double t = 0; t <= yMax + yStep * 0.01; t += yStep) {
                int yp = py + ph - (int) (t / yMax * ph);
                g2.setColor(new Color(228, 228, 228)); g2.drawLine(px, yp, px + pw, yp);
                g2.setColor(Color.BLACK); g2.drawLine(px - 4, yp, px, yp);
                String lbl = fmtTick(t);
                g2.drawString(lbl, px - 6 - fm.stringWidth(lbl), yp + fm.getAscent() / 2 - 1);
            }

            // x ticks (length)
            double xStep = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / xStep) * xStep; t <= xMax + xStep * 0.01; t += xStep) {
                int xp = px + (int) ((t - xMin) / (xMax - xMin) * pw);
                g2.setColor(Color.BLACK);
                g2.drawLine(xp, py + ph, xp, py + ph + 4);
                String lbl = fmtTick(t);
                g2.drawString(lbl, xp - fm.stringWidth(lbl) / 2, py + ph + 16);
            }

            // per-experiment step outlines
            Shape oldClip = g2.getClip();
            g2.setClip(px, py, pw, ph);
            for (int s = 0; s < counts.size(); s++) {
                int[] c = counts.get(s);
                Color col = colors.get(s);
                int[] xs = new int[nb * 2 + 2];
                int[] ys = new int[nb * 2 + 2];
                int k = 0;
                for (int b = 0; b < nb; b++) {
                    int xL = px + (int) ((double) b / nb * pw);
                    int xR = px + (int) ((double) (b + 1) / nb * pw);
                    int yv = py + ph - (int) ((double) c[b] / yMax * ph);
                    xs[k] = xL; ys[k++] = yv;
                    xs[k] = xR; ys[k++] = yv;
                }
                xs[k] = xs[k - 1]; ys[k++] = py + ph;
                xs[k] = px;        ys[k++] = py + ph;
                g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 45));
                g2.fillPolygon(xs, ys, k);
                g2.setColor(col);
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawPolyline(xs, ys, nb * 2);
            }
            g2.setClip(oldClip);

            drawFrame(g2, px, py, pw, ph);

            // axis labels
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            String xt = "Segment length (frames)";
            g2.drawString(xt, px + (pw - g2.getFontMetrics().stringWidth(xt)) / 2, py + ph + MB - 4);
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph / 2.0); g2.rotate(-Math.PI / 2);
            g2.drawString("Count", -g2.getFontMetrics().stringWidth("Count") / 2, g2.getFontMetrics().getAscent() / 2);
            g2.setTransform(orig);

            // legend
            int lx = px + pw - 6, ly = py + 4;
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            fm = g2.getFontMetrics();
            for (int s = 0; s < names.size(); s++) {
                String n = names.get(s);
                int w = fm.stringWidth(n) + 18;
                g2.setColor(colors.get(s));
                g2.fillRect(lx - w, ly, 12, 10);
                g2.setColor(Color.BLACK);
                g2.drawString(n, lx - w + 16, ly + 9);
                ly += 14;
            }
            g2.dispose();
        }

        private static double niceCeil(double v) {
            double step = niceStep(Math.max(v, 1), 4);
            return Math.ceil(v / step) * step;
        }
        private static void drawFrame(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setStroke(new BasicStroke(1f)); g2.drawRect(px, py, pw, ph);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2-D density heatmap (time vs α  /  time vs D)
    // ═════════════════════════════════════════════════════════════════════

    static class Heat2D extends JPanel {
        private static final int ML = 60, MR = 70, MT = 26, MB = 42, CBAR_W = 12;

        private final String xLabel, yLabel;
        private int[][] hist;
        private int     nb;
        private double  xMin, xMax, yMin, yMax;
        private int     total;
        private Color   base = new Color(0, 50, 180);
        private boolean hasData = false;
        boolean         logScale = true;

        Heat2D(String xLabel, String yLabel) {
            this.xLabel = xLabel; this.yLabel = yLabel;
            setBackground(Color.WHITE);
        }

        void setHist(int[][] hist, int nb, double xMin, double xMax,
                     double yMin, double yMax, int total, Color base) {
            this.hist = hist; this.nb = nb;
            this.xMin = xMin; this.xMax = xMax; this.yMin = yMin; this.yMax = yMax;
            this.total = total; this.base = base; this.hasData = true;
        }
        void setEmpty() { hasData = false; }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 20 || ph < 20) { g2.dispose(); return; }
            g2.setColor(Color.WHITE); g2.fillRect(px, py, pw, ph);

            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g2.drawString(xLabel + " vs " + yLabel, px, py - 10);

            if (!hasData) {
                g2.setColor(new Color(150, 150, 150));
                g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
                String m = "No segments";
                g2.drawString(m, px + (pw - g2.getFontMetrics().stringWidth(m)) / 2, py + ph / 2);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            int maxCount = 0;
            for (int[] col : hist) for (int v : col) maxCount = Math.max(maxCount, v);
            if (maxCount == 0) { drawFrame(g2, px, py, pw, ph); g2.dispose(); return; }
            double logMax = Math.log1p(maxCount);

            double cellW = (double) pw / nb, cellH = (double) ph / nb;
            Shape oldClip = g2.getClip();
            g2.setClip(px, py, pw, ph);
            for (int xi = 0; xi < nb; xi++)
                for (int yi = 0; yi < nb; yi++) {
                    int cnt = hist[xi][yi];
                    if (cnt == 0) continue;
                    double intensity = logScale ? Math.log1p(cnt) / logMax : (double) cnt / maxCount;
                    int cx = px + (int) (xi * cellW);
                    int cy = py + ph - (int) ((yi + 1) * cellH);
                    g2.setColor(mapColor(intensity, base));
                    g2.fillRect(cx, cy, (int) Math.ceil(cellW) + 1, (int) Math.ceil(cellH) + 1);
                }
            g2.setClip(oldClip);

            drawGrid(g2, px, py, pw, ph);
            drawFrame(g2, px, py, pw, ph);
            drawTicks(g2, px, py, pw, ph);
            drawTitles(g2, px, py, pw, ph);
            drawColorBar(g2, maxCount, logMax, px, py, pw, ph);

            g2.setColor(new Color(80, 80, 80));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            String nlab = "N = " + String.format("%,d", total);
            g2.drawString(nlab, px + pw - g2.getFontMetrics().stringWidth(nlab) - 5, py + 13);

            g2.dispose();
        }

        private static Color mapColor(double t, Color b) {
            t = Math.max(0, Math.min(1, t));
            int r = (int) (255 + (b.getRed() - 255) * t);
            int g = (int) (255 + (b.getGreen() - 255) * t);
            int bl = (int) (255 + (b.getBlue() - 255) * t);
            return new Color(clamp(r), clamp(g), clamp(bl));
        }
        private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

        private void drawColorBar(Graphics2D g2, int maxCount, double logMax,
                                  int px, int py, int pw, int ph) {
            int bx = px + pw + 16, by = py, bh = ph;
            for (int y = 0; y < bh; y++) {
                double frac = (double) (bh - 1 - y) / (bh - 1);
                g2.setColor(mapColor(frac, base));
                g2.fillRect(bx, by + y, CBAR_W, 1);
            }
            g2.setColor(Color.DARK_GRAY); g2.setStroke(new BasicStroke(0.8f));
            g2.drawRect(bx, by, CBAR_W, bh);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i <= 4; i++) {
                double frac = (double) i / 4;
                int yp = by + bh - (int) (frac * bh);
                g2.setColor(Color.DARK_GRAY); g2.drawLine(bx + CBAR_W, yp, bx + CBAR_W + 3, yp);
                double cnt = logScale ? Math.expm1(frac * logMax) : frac * maxCount;
                String lbl = cnt < 1 ? "0" : cnt < 10000 ? String.valueOf((int) Math.round(cnt))
                           : String.format("%.0e", cnt).replace("e+0", "e").replace("e+", "e");
                g2.drawString(lbl, bx + CBAR_W + 5, yp + fm.getAscent() / 2 - 1);
            }
        }

        private void drawGrid(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(new Color(0, 0, 0, 35)); g2.setStroke(new BasicStroke(0.5f));
            double xStep = niceStep(xMax - xMin, 5);
            for (double t = Math.ceil(xMin / xStep) * xStep; t <= xMax + xStep * 0.01; t += xStep) {
                int xp = px + (int) ((t - xMin) / (xMax - xMin) * pw);
                g2.drawLine(xp, py, xp, py + ph);
            }
            double yStep = niceStep(yMax - yMin, 5);
            for (double t = Math.ceil(yMin / yStep) * yStep; t <= yMax + yStep * 0.01; t += yStep) {
                int yp = py + ph - (int) ((t - yMin) / (yMax - yMin) * ph);
                g2.drawLine(px, yp, px + pw, yp);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawTicks(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double xStep = niceStep(xMax - xMin, 5);
            for (double t = Math.ceil(xMin / xStep) * xStep; t <= xMax + xStep * 0.01; t += xStep) {
                int xp = px + (int) ((t - xMin) / (xMax - xMin) * pw);
                g2.drawLine(xp, py + ph, xp, py + ph + 4);
                String lbl = fmtTick(t);
                g2.drawString(lbl, xp - fm.stringWidth(lbl) / 2, py + ph + 15);
            }
            double yStep = niceStep(yMax - yMin, 5);
            for (double t = Math.ceil(yMin / yStep) * yStep; t <= yMax + yStep * 0.01; t += yStep) {
                int yp = py + ph - (int) ((t - yMin) / (yMax - yMin) * ph);
                g2.drawLine(px - 4, yp, px, yp);
                String lbl = fmtTick(t);
                g2.drawString(lbl, px - 6 - fm.stringWidth(lbl), yp + fm.getAscent() / 2 - 1);
            }
        }

        private void drawTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(xLabel, px + (pw - fm.stringWidth(xLabel)) / 2, py + ph + MB - 3);
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph / 2.0); g2.rotate(-Math.PI / 2);
            g2.drawString(yLabel, -fm.stringWidth(yLabel) / 2, fm.getAscent() / 2);
            g2.setTransform(orig);
        }

        private static void drawFrame(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setStroke(new BasicStroke(1f)); g2.drawRect(px, py, pw, ph);
        }
    }
}
