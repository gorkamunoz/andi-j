package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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

    private final JSpinner   lenBinsSpin   = new JSpinner(new SpinnerNumberModel(40, 2, 400, 1));
    private final JSpinner   alphaBinsSpin = new JSpinner(new SpinnerNumberModel(40, 5, 200, 1));
    private final JSpinner   dBinsSpin     = new JSpinner(new SpinnerNumberModel(40, 5, 200, 1));
    private final JCheckBox  logCountBox   = new JCheckBox("Log count scale", true);

    private static final int NEVAL = 100;   // marginal-KDE grid resolution

    private final Hist1D  lengthHist = new Hist1D();
    private final Heat2D  timeAlpha  = new Heat2D("Segment length", "α",         true);
    private final Heat2D  timeD      = new Heat2D("Segment length", "log₁₀(D)",  false);
    /** The three plots together — what "Save plot" exports. */
    private JComponent plotArea;

    // ═════════════════════════════════════════════════════════════════════

    public SegStatsPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        add(Toolbars.makeScrollable(buildTopBar()), BorderLayout.NORTH);

        JScrollPane leftScroll = new JScrollPane(buildExperimentsPanel());
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));
        leftScroll.setMinimumSize(new Dimension(80, 0));

        // Draggable dividers between the three plots (and the Experiments column).
        JSplitPane heatSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, timeAlpha, timeD);
        heatSplit.setResizeWeight(0.5);
        heatSplit.setContinuousLayout(true);

        lengthHist.setMinimumSize(new Dimension(0, 60));
        lengthHist.setPreferredSize(new Dimension(0, 220));
        JSplitPane vSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, lengthHist, heatSplit);
        vSplit.setResizeWeight(0.32);
        vSplit.setContinuousLayout(true);
        vSplit.setDividerLocation(220);

        plotArea = vSplit;

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, vSplit);
        mainSplit.setDividerLocation(160);
        mainSplit.setContinuousLayout(true);
        mainSplit.setOneTouchExpandable(true);
        add(mainSplit, BorderLayout.CENTER);

        lenBinsSpin.addChangeListener(e -> rebuild());
        alphaBinsSpin.addChangeListener(e -> rebuild());
        dBinsSpin.addChangeListener(e -> rebuild());
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

        // Zoom / pan / reset tools — applied to all three plots at once.
        JToggleButton zoomBtn = new JToggleButton("⊕ Zoom", true);
        JToggleButton panBtn  = new JToggleButton("✥ Pan",  false);
        ButtonGroup   viewBG  = new ButtonGroup();
        viewBG.add(zoomBtn); viewBG.add(panBtn);
        JButton resetViewBtn = new JButton("↺ Reset view");
        for (AbstractButton b : new AbstractButton[]{zoomBtn, panBtn, resetViewBtn}) {
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            b.setFocusPainted(false);
        }
        zoomBtn.addActionListener(e -> setAllPanMode(false));
        panBtn .addActionListener(e -> setAllPanMode(true));
        resetViewBtn.addActionListener(e -> resetAllViews());

        lenBinsSpin.setToolTipText("Number of segment-length bins (length histogram + heatmap x-axis)");
        alphaBinsSpin.setToolTipText("Number of α bins (α heatmap y-axis)");
        dBinsSpin.setToolTipText("Number of log₁₀(D) bins (D heatmap y-axis)");

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEtchedBorder());
        bar.add(new JLabel("Length bins:"));
        bar.add(lenBinsSpin);
        bar.add(new JLabel("α bins:"));
        bar.add(alphaBinsSpin);
        bar.add(new JLabel("D bins:"));
        bar.add(dBinsSpin);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(logCountBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(refreshBtn);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(zoomBtn);
        bar.add(panBtn);
        bar.add(resetViewBtn);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(SvgExport.button(() -> plotArea, "segment_stats"));
        return bar;
    }

    private void setAllPanMode(boolean pan) {
        lengthHist.setPanMode(pan); timeAlpha.setPanMode(pan); timeD.setPanMode(pan);
    }
    private void resetAllViews() {
        lengthHist.resetViewport(); timeAlpha.resetViewport(); timeD.resetViewport();
        lengthHist.repaint(); timeAlpha.repaint(); timeD.repaint();
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
        int alphaBins = (Integer) alphaBinsSpin.getValue();
        int dBins     = (Integer) dBinsSpin.getValue();
        int lenBinsChosen = (Integer) lenBinsSpin.getValue();

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

        // ── segment-length histogram (per experiment) ─────────────────────
        int lenBins = Math.max(1, Math.min(lenBinsChosen, maxLen));
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

        // ── length-vs-α and length-vs-D 2-D histograms (overlaid per exp) ──
        // One point per segment: x = segment length, y = segment mean.
        // Shares the x-axis (segment length) with the histogram above.
        int totA = 0, totD = 0;
        List<ExpHist2D> densA = new ArrayList<>(), densD = new ArrayList<>();
        // Per-experiment y-marginal KDEs (α for the left plot, D for the right)
        List<double[]> kdeA = new ArrayList<>(), kdeD = new ArrayList<>();
        List<Color>    kdeColors = new ArrayList<>();
        double[] evalA = linspace(aMin, aMax, NEVAL);
        double[] evalD = linspace(ldMin, ldMax, NEVAL);
        for (ExperimentData exp : sel) {
            int[][] hA = new int[lenBins][alphaBins];
            int[][] hD = new int[lenBins][dBins];
            int maxA = 0, maxD = 0;
            List<Double> aVals = new ArrayList<>(), dVals = new ArrayList<>();
            for (List<Segment> segs : exp.segments.values()) {
                for (Segment s : segs) {
                    int xi = bin(s.length, lenMin, lenMax, lenBins);
                    if (Double.isFinite(s.alpha)) {
                        int ai = bin(s.alpha, aMin, aMax, alphaBins);
                        if (++hA[xi][ai] > maxA) maxA = hA[xi][ai];
                        totA++; aVals.add(s.alpha);
                    }
                    if (Double.isFinite(s.logD)) {
                        int li = bin(s.logD, ldMin, ldMax, dBins);
                        if (++hD[xi][li] > maxD) maxD = hD[xi][li];
                        totD++; dVals.add(s.logD);
                    }
                }
            }
            double[] a = toArray(aVals), d = toArray(dVals);
            kdeA.add(gaussianKde1D(a, evalA, silvermanBw(a)));
            kdeD.add(gaussianKde1D(d, evalD, silvermanBw(d)));
            kdeColors.add(exp.color);
            densA.add(new ExpHist2D(exp.color, hA, maxA));
            densD.add(new ExpHist2D(exp.color, hD, maxD));
        }

        boolean log = logCountBox.isSelected();
        if (totA > 0) timeAlpha.setHist(densA, lenBins, alphaBins, lenMin, lenMax, aMin, aMax, totA, kdeA, kdeColors);
        else          timeAlpha.setEmpty();
        if (totD > 0) timeD.setHist(densD, lenBins, dBins, lenMin, lenMax, ldMin, ldMax, totD, kdeD, kdeColors);
        else          timeD.setEmpty();
        timeAlpha.logScale = log; timeD.logScale = log;

        lengthHist.repaint(); timeAlpha.repaint(); timeD.repaint();
    }

    private static int bin(double v, double vMin, double vMax, int n) {
        int b = (int) ((v - vMin) / (vMax - vMin) * n);
        return Math.max(0, Math.min(n - 1, b));
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

    static double[] linspace(double lo, double hi, int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = lo + (double) i / (n - 1) * (hi - lo);
        return a;
    }

    static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }

    static double[] gaussianKde1D(double[] data, double[] eval, double bw) {
        double[] result = new double[eval.length];
        if (data.length == 0 || bw <= 0) return result;
        double c = 1.0 / (data.length * bw * Math.sqrt(2.0 * Math.PI));
        for (int i = 0; i < eval.length; i++) {
            double s = 0;
            for (double d : data) { double u = (eval[i] - d) / bw; s += Math.exp(-0.5 * u * u); }
            result[i] = s * c;
        }
        return result;
    }

    static double silvermanBw(double[] data) {
        if (data.length < 2) return 1.0;
        double mean = 0; for (double d : data) mean += d; mean /= data.length;
        double var  = 0; for (double d : data) { double dd = d - mean; var += dd * dd; }
        double std  = Math.sqrt(var / data.length);
        return std < 1e-10 ? 1.0 : 1.06 * std * Math.pow(data.length, -0.2);
    }

    static double arrayMax(double[] a) {
        double m = 0; for (double v : a) if (v > m) m = v; return m;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1-D segment-length histogram
    // ═════════════════════════════════════════════════════════════════════

    static class Hist1D extends JPanel {
        private static final int ML = 56, MR = 16, MT = 26, MB = 40;

        private double xMin, xMax;
        private int    nb;
        private String title = "Segment-length distribution";
        private boolean normalize = false;   // show each series as % of its own total
        private List<int[]>  counts = java.util.Collections.emptyList();
        private List<Color>  colors = java.util.Collections.emptyList();
        private List<String> names  = java.util.Collections.emptyList();

        void setTitle(String t) { this.title = t; }
        void setNormalize(boolean n) { if (normalize != n) { normalize = n; viewport = null; } }

        /** Bar value for series {@code c}, bin {@code b}: raw count or % of the series total. */
        private double barVal(int[] c, int b) {
            if (!normalize) return c[b];
            int total = 0; for (int v : c) total += v;
            return total > 0 ? 100.0 * c[b] / total : 0;
        }

        // ── Viewport / interaction state ──────────────────────────────────
        private double[] viewport = null;   // null = auto; {xMin,xMax,yMin,yMax}
        private double dvXMin, dvXMax, dvYMin, dvYMax;
        private int    renderPx, renderPy, renderPw, renderPh;
        private boolean panMode, dragging, panning;
        private int dragX0, dragY0, dragX1, dragY1, panX0, panY0;
        private double panXMin, panXMax, panYMin, panYMax;
        private final MovableLegend legend = new MovableLegend();

        Hist1D() {
            setBackground(Color.WHITE);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseInteraction();
        }

        void setData(double xMin, double xMax, int nb,
                     List<int[]> counts, List<Color> colors, List<String> names) {
            this.xMin = xMin; this.xMax = xMax; this.nb = nb;
            this.counts = counts; this.colors = colors; this.names = names;
        }

        void setPanMode(boolean pan) {
            this.panMode = pan;
            setCursor(Cursor.getPredefinedCursor(
                pan ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR));
        }
        void resetViewport() { this.viewport = null; }

        private void addMouseInteraction() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (legend.press(e.getX(), e.getY())) return;
                    if (counts.isEmpty()) return;
                    if (!panMode) {
                        dragging = true;
                        dragX0 = dragX1 = clampPx(e.getX(), renderPx, renderPx + renderPw);
                        dragY0 = dragY1 = clampPx(e.getY(), renderPy, renderPy + renderPh);
                    } else {
                        panning = true; panX0 = e.getX(); panY0 = e.getY();
                        panXMin = dvXMin; panXMax = dvXMax; panYMin = dvYMin; panYMax = dvYMax;
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (legend.dragTo(e.getX(), e.getY())) { repaint(); return; }
                    if (dragging) {
                        dragX1 = clampPx(e.getX(), renderPx, renderPx + renderPw);
                        dragY1 = clampPx(e.getY(), renderPy, renderPy + renderPh);
                        repaint();
                    } else if (panning && renderPw > 0 && renderPh > 0) {
                        double dx = (double) (e.getX() - panX0) / renderPw * (panXMax - panXMin);
                        double dy = (double) (e.getY() - panY0) / renderPh * (panYMax - panYMin);
                        viewport = new double[]{panXMin - dx, panXMax - dx,
                                                panYMin + dy, panYMax + dy};
                        repaint();
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (legend.isDragging()) { legend.release(); return; }
                    if (dragging) {
                        dragging = false;
                        int x0 = Math.min(dragX0, dragX1), x1 = Math.max(dragX0, dragX1);
                        int y0 = Math.min(dragY0, dragY1), y1 = Math.max(dragY0, dragY1);
                        if (x1 - x0 > 5 && y1 - y0 > 5) {
                            double xMinV = pxToDataX(x0), xMaxV = pxToDataX(x1);
                            double yMaxV = pxToDataY(y0), yMinV = pxToDataY(y1);
                            if (xMinV < xMaxV && yMinV < yMaxV)
                                viewport = new double[]{xMinV, xMaxV, yMinV, yMaxV};
                        }
                        repaint();
                    } else if (panning) { panning = false; }
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) { viewport = null; repaint(); }
                }
                @Override public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(
                        legend.over(e.getX(), e.getY()) ? Cursor.MOVE_CURSOR
                            : (panMode ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR)));
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private double pxToDataX(int p) {
            if (renderPw <= 0) return dvXMin;
            return dvXMin + (double) (p - renderPx) / renderPw * (dvXMax - dvXMin);
        }
        private double pxToDataY(int p) {
            if (renderPh <= 0) return dvYMin;
            return dvYMin + (double) (renderPy + renderPh - p) / renderPh * (dvYMax - dvYMin);
        }
        private static int clampPx(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
        private int mapXpix(double v, int px, int pw) {
            return px + (int) ((v - dvXMin) / (dvXMax - dvXMin) * pw);
        }
        private int mapYpix(double v, int py, int ph) {
            return py + ph - (int) ((v - dvYMin) / (dvYMax - dvYMin) * ph);
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
            g2.drawString(title, px, py - 10);

            double maxVal = 0;
            for (int[] c : counts) for (int b = 0; b < c.length; b++) maxVal = Math.max(maxVal, barVal(c, b));
            if (counts.isEmpty() || maxVal <= 0) {
                g2.setColor(new Color(150, 150, 150));
                g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
                String m = "No segments";
                g2.drawString(m, px + (pw - g2.getFontMetrics().stringWidth(m)) / 2, py + ph / 2);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose(); return;
            }
            double yMax = niceCeil(maxVal);

            // Display range (viewport or full), saved for mouse maths.
            dvXMin = (viewport != null) ? viewport[0] : xMin;
            dvXMax = (viewport != null) ? viewport[1] : xMax;
            dvYMin = (viewport != null) ? viewport[2] : 0;
            dvYMax = (viewport != null) ? viewport[3] : yMax;
            renderPx = px; renderPy = py; renderPw = pw; renderPh = ph;

            // grid + y ticks
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double yStep = niceStep(dvYMax - dvYMin, 4);
            for (double t = Math.ceil(dvYMin / yStep) * yStep; t <= dvYMax + yStep * 0.01; t += yStep) {
                int yp = mapYpix(t, py, ph);
                g2.setColor(new Color(228, 228, 228)); g2.drawLine(px, yp, px + pw, yp);
                g2.setColor(Color.BLACK); g2.drawLine(px - 4, yp, px, yp);
                String lbl = fmtTick(t);
                g2.drawString(lbl, px - 6 - fm.stringWidth(lbl), yp + fm.getAscent() / 2 - 1);
            }

            // x ticks (length)
            double xStep = niceStep(dvXMax - dvXMin, 6);
            for (double t = Math.ceil(dvXMin / xStep) * xStep; t <= dvXMax + xStep * 0.01; t += xStep) {
                int xp = mapXpix(t, px, pw);
                g2.setColor(Color.BLACK);
                g2.drawLine(xp, py + ph, xp, py + ph + 4);
                String lbl = fmtTick(t);
                g2.drawString(lbl, xp - fm.stringWidth(lbl) / 2, py + ph + 16);
            }

            // per-experiment step outlines (mapped by data coords for zoom/pan)
            double binW = (xMax - xMin) / nb;
            Shape oldClip = g2.getClip();
            g2.setClip(px, py, pw, ph);
            for (int s = 0; s < counts.size(); s++) {
                int[] c = counts.get(s);
                Color col = colors.get(s);
                int[] xs = new int[nb * 2 + 2];
                int[] ys = new int[nb * 2 + 2];
                int k = 0;
                for (int b = 0; b < nb; b++) {
                    int xL = mapXpix(xMin + b * binW,       px, pw);
                    int xR = mapXpix(xMin + (b + 1) * binW, px, pw);
                    int yv = mapYpix(barVal(c, b), py, ph);
                    xs[k] = xL; ys[k++] = yv;
                    xs[k] = xR; ys[k++] = yv;
                }
                xs[k] = xs[k - 1]; ys[k++] = py + ph;
                xs[k] = xs[0];     ys[k++] = py + ph;
                g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 45));
                g2.fillPolygon(xs, ys, k);
                g2.setColor(col);
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawPolyline(xs, ys, nb * 2);
            }
            if (dragging) {
                int rx = Math.min(dragX0, dragX1), ry = Math.min(dragY0, dragY1);
                g2.setColor(new Color(0, 90, 200, 40));
                g2.fillRect(rx, ry, Math.abs(dragX1 - dragX0), Math.abs(dragY1 - dragY0));
                g2.setColor(new Color(0, 90, 200));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRect(rx, ry, Math.abs(dragX1 - dragX0), Math.abs(dragY1 - dragY0));
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
            String yt = normalize ? "% of segments" : "Count";
            g2.drawString(yt, -g2.getFontMetrics().stringWidth(yt) / 2, g2.getFontMetrics().getAscent() / 2);
            g2.setTransform(orig);

            // legend (movable block)
            if (names.isEmpty()) {
                legend.clear();
            } else {
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                fm = g2.getFontMetrics();
                int maxW = 0;
                for (String n : names) maxW = Math.max(maxW, fm.stringWidth(n) + 18);
                int boxW = maxW + 4, boxH = names.size() * 14 + 2;
                Point lp = legend.layout(px + pw - boxW - 6, py + 4, boxW, boxH, px, py, pw, ph);
                int bx = lp.x, by = lp.y;
                for (int s = 0; s < names.size(); s++) {
                    int rowY = by + s * 14;
                    g2.setColor(colors.get(s));
                    g2.fillRect(bx, rowY, 12, 10);
                    g2.setColor(Color.BLACK);
                    g2.drawString(names.get(s), bx + 16, rowY + 9);
                }
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

    /** One experiment's 2-D histogram, for the overlaid heatmap. */
    static class ExpHist2D {
        final Color   color;
        final int[][] hist;
        final int     max;
        ExpHist2D(Color color, int[][] hist, int max) {
            this.color = color; this.hist = hist; this.max = max;
        }
    }

    static class Heat2D extends JPanel {
        private static final int MT = 26, MB = 42;
        private static final int YLBL = 50;     // y tick-label area
        private static final int MARGW = 46;    // y-marginal KDE strip width
        private static final int CBAR_W = 12, CBAR_LBL = 42, GAP = 8;

        private final String  xLabel, yLabel;
        private final boolean leftMarginal;     // KDE strip on the left (else right)

        private List<ExpHist2D> densities = java.util.Collections.emptyList();
        private int     nbX, nbY;   // x bins (length) × y bins (α or D)
        private double  xMin, xMax, yMin, yMax;
        private int     total;
        private List<double[]> kde   = java.util.Collections.emptyList();
        private List<Color>    kdeCol = java.util.Collections.emptyList();
        private boolean hasData = false;
        boolean         logScale = true;

        // ── Viewport / interaction state ──────────────────────────────────
        private double[] viewport = null;   // null = auto; {xMin,xMax,yMin,yMax}
        private double dvXMin, dvXMax, dvYMin, dvYMax;   // current display range
        private int    renderPx, renderPy, renderPw, renderPh;
        private boolean panMode, dragging, panning;
        private int dragX0, dragY0, dragX1, dragY1, panX0, panY0;
        private double panXMin, panXMax, panYMin, panYMax;
        private int hoverPx = -1, hoverPy = -1;

        Heat2D(String xLabel, String yLabel, boolean leftMarginal) {
            this.xLabel = xLabel; this.yLabel = yLabel; this.leftMarginal = leftMarginal;
            setBackground(Color.WHITE);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseInteraction();
        }

        void setHist(List<ExpHist2D> densities, int nbX, int nbY, double xMin, double xMax,
                     double yMin, double yMax, int total,
                     List<double[]> kde, List<Color> kdeCol) {
            this.densities = densities; this.nbX = nbX; this.nbY = nbY;
            this.xMin = xMin; this.xMax = xMax; this.yMin = yMin; this.yMax = yMax;
            this.total = total;
            this.kde = kde; this.kdeCol = kdeCol;
            this.hasData = true;
        }
        void setEmpty() { hasData = false; }

        void setPanMode(boolean pan) {
            this.panMode = pan;
            setCursor(Cursor.getPredefinedCursor(
                pan ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR));
        }
        void resetViewport() { this.viewport = null; }

        private void addMouseInteraction() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!hasData) return;
                    if (!panMode) {
                        dragging = true;
                        dragX0 = dragX1 = clampPx(e.getX(), renderPx, renderPx + renderPw);
                        dragY0 = dragY1 = clampPx(e.getY(), renderPy, renderPy + renderPh);
                    } else {
                        panning = true; panX0 = e.getX(); panY0 = e.getY();
                        panXMin = dvXMin; panXMax = dvXMax; panYMin = dvYMin; panYMax = dvYMax;
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragging) {
                        dragX1 = clampPx(e.getX(), renderPx, renderPx + renderPw);
                        dragY1 = clampPx(e.getY(), renderPy, renderPy + renderPh);
                        repaint();
                    } else if (panning && renderPw > 0 && renderPh > 0) {
                        double dx = (double) (e.getX() - panX0) / renderPw * (panXMax - panXMin);
                        double dy = (double) (e.getY() - panY0) / renderPh * (panYMax - panYMin);
                        viewport = new double[]{panXMin - dx, panXMax - dx,
                                                panYMin + dy, panYMax + dy};
                        repaint();
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (dragging) {
                        dragging = false;
                        int x0 = Math.min(dragX0, dragX1), x1 = Math.max(dragX0, dragX1);
                        int y0 = Math.min(dragY0, dragY1), y1 = Math.max(dragY0, dragY1);
                        if (x1 - x0 > 5 && y1 - y0 > 5) {
                            double xMinV = pxToDataX(x0), xMaxV = pxToDataX(x1);
                            double yMaxV = pxToDataY(y0), yMinV = pxToDataY(y1);
                            if (xMinV < xMaxV && yMinV < yMaxV)
                                viewport = new double[]{xMinV, xMaxV, yMinV, yMaxV};
                        }
                        repaint();
                    } else if (panning) { panning = false; }
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) { viewport = null; repaint(); }
                }
                @Override public void mouseMoved(MouseEvent e) {
                    hoverPx = e.getX(); hoverPy = e.getY(); repaint();
                }
                @Override public void mouseExited(MouseEvent e) { hoverPx = -1; repaint(); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private double pxToDataX(int p) {
            if (renderPw <= 0) return dvXMin;
            return dvXMin + (double) (p - renderPx) / renderPw * (dvXMax - dvXMin);
        }
        private double pxToDataY(int p) {
            if (renderPh <= 0) return dvYMin;
            return dvYMin + (double) (renderPy + renderPh - p) / renderPh * (dvYMax - dvYMin);
        }
        private static int clampPx(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

        private int mapYpix(double v, int py, int ph) {
            return py + ph - (int) ((v - dvYMin) / (dvYMax - dvYMin) * ph);
        }
        private int mapXpix(double v, int px, int pw) {
            return px + (int) ((v - dvXMin) / (dvXMax - dvXMin) * pw);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int leftPad  = leftMarginal ? (MARGW + GAP + YLBL) : YLBL;
            int rightPad = leftMarginal ? (GAP + CBAR_W + CBAR_LBL)
                                        : (GAP + MARGW + GAP + CBAR_W + CBAR_LBL);
            int px = leftPad, py = MT, pw = W - leftPad - rightPad, ph = H - MT - MB;
            if (pw < 20 || ph < 20) { g2.dispose(); return; }
            g2.setColor(Color.WHITE); g2.fillRect(px, py, pw, ph);

            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g2.drawString(yLabel + " vs " + xLabel, px, py - 10);

            // strip + colour-bar x positions
            int stripX = leftMarginal ? 2 : (px + pw + GAP);
            int cbarX  = leftMarginal ? (px + pw + GAP)
                                      : (px + pw + GAP + MARGW + GAP);

            if (!hasData) {
                g2.setColor(new Color(150, 150, 150));
                g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
                String m = "No segments";
                g2.drawString(m, px + (pw - g2.getFontMetrics().stringWidth(m)) / 2, py + ph / 2);
                drawFrame(g2, px, py, pw, ph);
                drawTitles(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            // Display range (viewport or full), saved for mouse maths.
            dvXMin = (viewport != null) ? viewport[0] : xMin;
            dvXMax = (viewport != null) ? viewport[1] : xMax;
            dvYMin = (viewport != null) ? viewport[2] : yMin;
            dvYMax = (viewport != null) ? viewport[3] : yMax;
            renderPx = px; renderPy = py; renderPw = pw; renderPh = ph;

            int maxCount = 0;
            for (ExpHist2D d : densities) maxCount = Math.max(maxCount, d.max);

            // Overlaid per-experiment heatmaps: each in its own colour, with
            // per-cell opacity encoding its own-max-normalised density, so
            // experiments blend instead of being summed.
            if (maxCount > 0) {
                double bwx = (xMax - xMin) / nbX, bwy = (yMax - yMin) / nbY;
                Shape oldClip = g2.getClip();
                g2.setClip(px, py, pw, ph);
                for (ExpHist2D dns : densities) {
                    if (dns.max <= 0) continue;
                    double lmax = Math.log1p(dns.max);
                    int cr = dns.color.getRed(), cg = dns.color.getGreen(), cb = dns.color.getBlue();
                    for (int xi = 0; xi < nbX; xi++)
                        for (int yi = 0; yi < nbY; yi++) {
                            int cnt = dns.hist[xi][yi];
                            if (cnt == 0) continue;
                            double intensity = logScale ? Math.log1p(cnt) / lmax : (double) cnt / dns.max;
                            int alpha = (int) (Math.max(0, Math.min(1, intensity)) * 210);
                            if (alpha <= 0) continue;
                            int cx  = mapXpix(xMin + xi * bwx,       px, pw);
                            int cx2 = mapXpix(xMin + (xi + 1) * bwx, px, pw);
                            int cy  = mapYpix(yMin + (yi + 1) * bwy, py, ph);
                            int cy2 = mapYpix(yMin + yi * bwy,       py, ph);
                            g2.setColor(new Color(cr, cg, cb, alpha));
                            g2.fillRect(cx, cy, Math.max(1, cx2 - cx) + 1, Math.max(1, cy2 - cy) + 1);
                        }
                }
                g2.setClip(oldClip);
            }

            drawGrid(g2, px, py, pw, ph);
            drawFrame(g2, px, py, pw, ph);
            drawTicks(g2, px, py, pw, ph);
            drawTitles(g2, px, py, pw, ph);
            // Colour-scale bar only makes sense for a single experiment.
            if (densities.size() == 1)
                drawColorBar(g2, cbarX, densities.get(0).color, densities.get(0).max,
                             Math.log1p(densities.get(0).max), py, ph);
            drawYMarginal(g2, stripX, py, ph);

            g2.setColor(new Color(80, 80, 80));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            String nlab = "N = " + String.format("%,d", total);
            g2.drawString(nlab, px + pw - g2.getFontMetrics().stringWidth(nlab) - 5, py + 13);

            if (dragging) {
                int rx = Math.min(dragX0, dragX1), ry = Math.min(dragY0, dragY1);
                g2.setColor(new Color(0, 90, 200, 40));
                g2.fillRect(rx, ry, Math.abs(dragX1 - dragX0), Math.abs(dragY1 - dragY0));
                g2.setColor(new Color(0, 90, 200));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRect(rx, ry, Math.abs(dragX1 - dragX0), Math.abs(dragY1 - dragY0));
            }

            // ── Hover readout ─────────────────────────────────────────────
            if (!dragging && !panning && hoverPx >= px && hoverPx <= px + pw
                    && hoverPy >= py && hoverPy <= py + ph) {
                double dx = pxToDataX(hoverPx), dy = pxToDataY(hoverPy);
                String tip = String.format("len = %d   %s = %.3f", Math.round(dx), yLabel, dy);
                drawHoverTip(g2, tip, hoverPx, hoverPy, W, H);
            }

            g2.dispose();
        }

        private static void drawHoverTip(Graphics2D g2, String text, int mx, int my, int W, int H) {
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(text), th = fm.getAscent(), pad = 4;
            int bw = tw + pad * 2, bh = th + pad * 2;
            int bx = mx + 14, by = my - bh - 4;
            if (bx + bw > W - 4) bx = mx - bw - 6;
            if (by < 4)          by = my + 10;
            g2.setColor(new Color(255, 255, 220, 230)); g2.fillRoundRect(bx, by, bw, bh, 4, 4);
            g2.setColor(new Color(160, 160, 100)); g2.setStroke(new BasicStroke(0.5f));
            g2.drawRoundRect(bx, by, bw, bh, 4, 4);
            g2.setColor(new Color(30, 30, 30));
            g2.drawString(text, bx + pad, by + pad + th - 1);
        }

        /** Y-axis marginal KDE strip (α or D), one curve per experiment. */
        private void drawYMarginal(Graphics2D g2, int stripX, int py, int ph) {
            g2.setColor(new Color(250, 250, 250));
            g2.fillRect(stripX, py, MARGW, ph);
            g2.setColor(new Color(190, 190, 190));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRect(stripX, py, MARGW, ph);
            if (kde == null || kde.isEmpty()) return;

            Shape oldClip = g2.getClip();
            g2.setClip(stripX, py, MARGW, ph);
            double baseX = leftMarginal ? (stripX + MARGW) : stripX;  // baseline near plot
            for (int s = 0; s < kde.size(); s++) {
                double[] curve = kde.get(s);
                int n = curve.length;
                double kmax = arrayMax(curve);
                if (kmax < 1e-300) continue;
                double[] eval = linspace(yMin, yMax, n);
                int[] xs = new int[n + 2], ys = new int[n + 2];
                for (int i = 0; i < n; i++) {
                    int reach = (int) (curve[i] / kmax * (MARGW - 2));
                    xs[i] = leftMarginal ? (stripX + MARGW - reach) : (stripX + reach);
                    ys[i] = mapYpix(eval[i], py, ph);
                }
                xs[n]     = (int) baseX; ys[n]     = ys[n - 1];
                xs[n + 1] = (int) baseX; ys[n + 1] = ys[0];
                Color c = kdeCol.get(s);
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
                g2.fillPolygon(xs, ys, n + 2);
                g2.setColor(c);
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawPolyline(xs, ys, n);
            }
            g2.setClip(oldClip);
            g2.setStroke(new BasicStroke(1f));
        }

        private static Color mapColor(double t, Color b) {
            t = Math.max(0, Math.min(1, t));
            int r = (int) (255 + (b.getRed() - 255) * t);
            int g = (int) (255 + (b.getGreen() - 255) * t);
            int bl = (int) (255 + (b.getBlue() - 255) * t);
            return new Color(clamp(r), clamp(g), clamp(bl));
        }
        private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

        private void drawColorBar(Graphics2D g2, int bx, Color base, int maxCount, double logMax,
                                  int py, int ph) {
            int by = py, bh = ph;
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
            double xStep = niceStep(dvXMax - dvXMin, 5);
            for (double t = Math.ceil(dvXMin / xStep) * xStep; t <= dvXMax + xStep * 0.01; t += xStep) {
                int xp = mapXpix(t, px, pw);
                g2.drawLine(xp, py, xp, py + ph);
            }
            double yStep = niceStep(dvYMax - dvYMin, 5);
            for (double t = Math.ceil(dvYMin / yStep) * yStep; t <= dvYMax + yStep * 0.01; t += yStep) {
                int yp = mapYpix(t, py, ph);
                g2.drawLine(px, yp, px + pw, yp);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawTicks(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double xStep = niceStep(dvXMax - dvXMin, 5);
            for (double t = Math.ceil(dvXMin / xStep) * xStep; t <= dvXMax + xStep * 0.01; t += xStep) {
                int xp = mapXpix(t, px, pw);
                g2.drawLine(xp, py + ph, xp, py + ph + 4);
                String lbl = fmtTick(t);
                g2.drawString(lbl, xp - fm.stringWidth(lbl) / 2, py + ph + 15);
            }
            double yStep = niceStep(dvYMax - dvYMin, 5);
            for (double t = Math.ceil(dvYMin / yStep) * yStep; t <= dvYMax + yStep * 0.01; t += yStep) {
                int yp = mapYpix(t, py, ph);
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
            int yTitleX = leftMarginal ? (MARGW + GAP + 12) : 12;
            g2.translate(yTitleX, py + ph / 2.0); g2.rotate(-Math.PI / 2);
            g2.drawString(yLabel, -fm.stringWidth(yLabel) / 2, fm.getAscent() / 2);
            g2.setTransform(orig);
        }

        private static void drawFrame(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setStroke(new BasicStroke(1f)); g2.drawRect(px, py, pw, ph);
        }
    }
}
