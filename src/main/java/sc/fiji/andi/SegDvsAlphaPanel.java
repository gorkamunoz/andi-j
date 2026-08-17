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
 * "D vs α" density tab for Trajectory Segmentation.
 *
 * <p>Mirrors the Trajectory-Analysis "D vs α" tab (left experiment multi-select,
 * marginal KDE strips on the top/right edges) but, because the data are
 * per-frame STEP predictions (far too many to scatter), the centre shows a
 * 2-D density histogram instead of individual points.  There is no point
 * selection / tagging.</p>
 *
 * <ul>
 *   <li>X axis – anomalous exponent α ∈ [0, 2]  (fixed range)</li>
 *   <li>Y axis – log₁₀(D), auto-ranged from the selected data</li>
 *   <li>Centre – 2-D histogram heatmap (combined over the selected experiments;
 *       coloured by the experiment when only one is selected, neutral otherwise)</li>
 *   <li>Top / right – per-experiment marginal KDE of α and log₁₀(D)</li>
 *   <li>Far right – colour-scale bar mapping shade → frame count</li>
 * </ul>
 *
 * Controls: per-experiment toggles (left), bin count, log-count colour scale.
 */
public class SegDvsAlphaPanel extends JPanel {

    private final List<ExperimentData>         experiments;
    private final Map<ExperimentData, Boolean> expVisible = new LinkedHashMap<>();

    private final JSpinner       alphaBinsSpin, dBinsSpin;   // separate α / D bin counts
    private final JSpinner       alphaHSpin, dWSpin;         // marginal-strip sizes
    private final JCheckBox      logCountBox;
    private final DensityCanvas  canvas;

    // ═════════════════════════════════════════════════════════════════════
    // Constructor
    // ═════════════════════════════════════════════════════════════════════

    public SegDvsAlphaPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        alphaBinsSpin = new JSpinner(new SpinnerNumberModel(40, 5, 200, 1));
        dBinsSpin     = new JSpinner(new SpinnerNumberModel(40, 5, 200, 1));
        alphaHSpin    = new JSpinner(new SpinnerNumberModel(60, 30, 320, 10));
        dWSpin        = new JSpinner(new SpinnerNumberModel(62, 30, 320, 10));
        logCountBox   = new JCheckBox("Log count scale", true);
        canvas        = new DensityCanvas();

        add(buildTopBar(), BorderLayout.NORTH);

        JScrollPane leftScroll = new JScrollPane(buildExperimentsPanel());
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));
        leftScroll.setMinimumSize(new Dimension(80, 0));

        // Draggable divider between the Experiments column and the plot
        // (same as the Trajectory-Analysis tabs).
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, canvas);
        split.setDividerLocation(160);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        add(split, BorderLayout.CENTER);

        alphaBinsSpin.addChangeListener(e -> rebuild());
        dBinsSpin.addChangeListener(e -> rebuild());
        alphaHSpin.addChangeListener(e -> canvas.setAlphaStripH((Integer) alphaHSpin.getValue()));
        dWSpin.addChangeListener(e -> canvas.setDStripW((Integer) dWSpin.getValue()));
        logCountBox.addActionListener(e -> {
            canvas.logScale = logCountBox.isSelected();
            canvas.repaint();
        });

        if (experiments.stream().anyMatch(ExperimentData::hasStepPredictions))
            rebuild();
    }

    // ─────────────────────────────────────────────────────────────────────

    private JPanel buildTopBar() {
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Recompute the histogram");
        refreshBtn.addActionListener(e -> rebuild());

        // Zoom / pan / reset tools (same as the Trajectory-Analysis histograms)
        JToggleButton zoomBtn = new JToggleButton("⊕ Zoom", true);
        JToggleButton panBtn  = new JToggleButton("✥ Pan",  false);
        ButtonGroup   viewBG  = new ButtonGroup();
        viewBG.add(zoomBtn); viewBG.add(panBtn);
        JButton resetViewBtn = new JButton("↺ Reset view");
        for (AbstractButton b : new AbstractButton[]{zoomBtn, panBtn, resetViewBtn}) {
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            b.setFocusPainted(false);
        }
        zoomBtn.addActionListener(e -> canvas.setPanMode(false));
        panBtn .addActionListener(e -> canvas.setPanMode(true));
        resetViewBtn.addActionListener(e -> { canvas.resetViewport(); canvas.repaint(); });

        alphaBinsSpin.setToolTipText("Number of α bins (histogram + heatmap x-axis)");
        dBinsSpin.setToolTipText("Number of log₁₀(D) bins (histogram + heatmap y-axis)");
        alphaHSpin.setToolTipText("Height of the top α histogram (px)");
        dWSpin.setToolTipText("Width of the right log₁₀(D) histogram (px)");

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEtchedBorder());
        bar.add(new JLabel("α bins:"));
        bar.add(alphaBinsSpin);
        bar.add(new JLabel("D bins:"));
        bar.add(dBinsSpin);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(logCountBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(refreshBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(zoomBtn);
        bar.add(panBtn);
        bar.add(resetViewBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(new JLabel("α h:"));
        bar.add(alphaHSpin);
        bar.add(new JLabel("D w:"));
        bar.add(dWSpin);
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
    // Histogram + marginal computation
    // ═════════════════════════════════════════════════════════════════════

    private static final int NEVAL = 100;  // KDE evaluation grid resolution

    private void rebuild() {
        int alphaBins = (Integer) alphaBinsSpin.getValue();
        int dBins     = (Integer) dBinsSpin.getValue();

        List<ExperimentData> sel = new ArrayList<>();
        for (ExperimentData exp : experiments)
            if (expVisible.getOrDefault(exp, true) && exp.hasStepPredictions())
                sel.add(exp);

        // Gather per-experiment per-frame values and the global logD range
        Map<ExperimentData, double[]> alphaByExp = new LinkedHashMap<>();
        Map<ExperimentData, double[]> logdByExp  = new LinkedHashMap<>();
        double ldMin = Double.MAX_VALUE, ldMax = -Double.MAX_VALUE;
        int total = 0;

        for (ExperimentData exp : sel) {
            List<Double> av = new ArrayList<>();
            List<Double> dv = new ArrayList<>();
            for (Map.Entry<Integer, double[]> e : exp.stepAlpha.entrySet()) {
                double[] al = e.getValue();
                double[] ld = exp.stepLogD != null ? exp.stepLogD.get(e.getKey()) : null;
                if (al == null || ld == null) continue;
                int len = Math.min(al.length, ld.length);
                for (int t = 0; t < len; t++) {
                    if (Double.isFinite(al[t]) && Double.isFinite(ld[t])) {
                        av.add(al[t]); dv.add(ld[t]);
                        ldMin = Math.min(ldMin, ld[t]);
                        ldMax = Math.max(ldMax, ld[t]);
                    }
                }
            }
            double[] aArr = toArray(av), dArr = toArray(dv);
            alphaByExp.put(exp, aArr);
            logdByExp.put(exp, dArr);
            total += aArr.length;
        }

        if (total == 0) { canvas.setEmpty(); return; }

        double lRng = ldMax - ldMin;
        if (lRng < 1e-6) { ldMin -= 0.5; ldMax += 0.5; lRng = 1; }
        ldMin -= 0.03 * lRng;
        ldMax += 0.03 * lRng;
        final double aMin = 0.0, aMax = 2.0;

        // Combined 2-D histogram (α × D) and 1-D marginal histograms over all
        // selected experiments, each with its own bin count.
        int[][] hist   = new int[alphaBins][dBins];
        int[]   aBars  = new int[alphaBins];
        int[]   dBarsA = new int[dBins];
        for (ExperimentData exp : sel) {
            double[] a = alphaByExp.get(exp), d = logdByExp.get(exp);
            for (int i = 0; i < a.length; i++) {
                int ai = bin(a[i], aMin, aMax, alphaBins);
                int li = bin(d[i], ldMin, ldMax, dBins);
                hist[ai][li]++;
                aBars[ai]++;
                dBarsA[li]++;
            }
        }

        // Per-experiment marginal KDEs (precomputed over fixed eval grids)
        double[] evalA = linspace(aMin, aMax, NEVAL);
        double[] evalD = linspace(ldMin, ldMax, NEVAL);
        List<Marginal> margs = new ArrayList<>();
        for (ExperimentData exp : sel) {
            double[] a = alphaByExp.get(exp), d = logdByExp.get(exp);
            margs.add(new Marginal(exp.color, a.length,
                gaussianKde1D(a, evalA, silvermanBw(a)),
                gaussianKde1D(d, evalD, silvermanBw(d))));
        }

        // One experiment → its colour; the combined (additive) density over
        // several experiments is drawn in black ("sum of all colours").
        Color baseColor = sel.size() == 1 ? sel.get(0).color : Color.BLACK;
        canvas.setData(hist, alphaBins, dBins, aMin, aMax, ldMin, ldMax, total,
                       baseColor, margs, aBars, dBarsA);
        canvas.logScale = logCountBox.isSelected();
        canvas.repaint();
    }

    /** Returns the bin index for value v in [vMin, vMax) clamped to [0, nBins-1]. */
    private static int bin(double v, double vMin, double vMax, int n) {
        int b = (int) ((v - vMin) / (vMax - vMin) * n);
        return Math.max(0, Math.min(n - 1, b));
    }

    // ── KDE helpers ─────────────────────────────────────────────────────────

    private static double[] gaussianKde1D(double[] data, double[] eval, double bw) {
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

    private static double silvermanBw(double[] data) {
        if (data.length < 2) return 1.0;
        double mean = 0; for (double d : data) mean += d; mean /= data.length;
        double var  = 0; for (double d : data) { double dd = d - mean; var += dd * dd; }
        double std  = Math.sqrt(var / data.length);
        return std < 1e-10 ? 1.0 : 1.06 * std * Math.pow(data.length, -0.2);
    }

    private static double[] linspace(double lo, double hi, int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = lo + (double) i / (n - 1) * (hi - lo);
        return a;
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }

    /** One experiment's precomputed marginal KDE curves. */
    static class Marginal {
        final Color    color;
        final int      count;
        final double[] alphaKde;   // over [aMin, aMax]
        final double[] logdKde;    // over [ldMin, ldMax]
        Marginal(Color color, int count, double[] alphaKde, double[] logdKde) {
            this.color = color; this.count = count;
            this.alphaKde = alphaKde; this.logdKde = logdKde;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Inner canvas
    // ═════════════════════════════════════════════════════════════════════

    static class DensityCanvas extends JPanel {

        // Margins (pixels). ML/MB fixed; the top (alpha strip) and right
        // (D strip) margins grow with the user-adjustable strip sizes.
        private static final int ML = 72;   // left   (Y labels)
        private static final int MB = 50;   // bottom (X labels + title)
        private static final int CBAR_W = 14;

        // Marginal-strip sizes (user-adjustable via the toolbar)
        private int margH = 60;   // alpha (top) strip height
        private int margW = 62;   // D (right) strip width

        private int mt() { return margH + 12; }          // top margin
        private int mr() { return margW + 8 + CBAR_W + 66; } // right margin (strip + colour bar + labels)

        // ── Current dataset ───────────────────────────────────────────────
        private int[][]        hist;
        private int            nbX, nbY;   // α bins × log₁₀(D) bins
        private double         aMin, aMax, ldMin, ldMax;
        private int            totalPts;
        private Color          baseColor = new Color(0, 50, 180);
        private List<Marginal> marginals = java.util.Collections.emptyList();
        private int[]          alphaBars = new int[0];   // combined α histogram
        private int[]          dBars     = new int[0];   // combined log₁₀(D) histogram
        private boolean        hasData   = false;
        boolean                logScale  = true;

        // ── Viewport / interaction state ──────────────────────────────────
        private double[] viewport = null;   // null = auto; {xMin,xMax,yMin,yMax}
        private double renderXMin, renderXMax, renderYMin, renderYMax;
        private int    renderPx, renderPy, renderPw, renderPh;
        private boolean panMode = false;
        private boolean dragging = false;
        private int dragX0, dragY0, dragX1, dragY1;
        private boolean panning = false;
        private int     panX0, panY0;
        private double  panXMin, panXMax, panYMin, panYMax;

        DensityCanvas() {
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(680, 500));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseInteraction();
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
                    if (!hasData) return;
                    if (!panMode) {
                        dragging = true;
                        dragX0 = dragX1 = clampPx(e.getX(), renderPx, renderPx + renderPw);
                        dragY0 = dragY1 = clampPx(e.getY(), renderPy, renderPy + renderPh);
                    } else {
                        panning = true;
                        panX0 = e.getX(); panY0 = e.getY();
                        panXMin = renderXMin; panXMax = renderXMax;
                        panYMin = renderYMin; panYMax = renderYMax;
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
                            double xMin = pxToDataX(x0), xMax = pxToDataX(x1);
                            double yMax = pxToDataY(y0), yMin = pxToDataY(y1);
                            if (xMin < xMax && yMin < yMax)
                                viewport = new double[]{xMin, xMax, yMin, yMax};
                        }
                        repaint();
                    } else if (panning) {
                        panning = false;
                    }
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) { viewport = null; repaint(); }
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private double pxToDataX(int px) {
            if (renderPw <= 0) return renderXMin;
            return renderXMin + (double) (px - renderPx) / renderPw * (renderXMax - renderXMin);
        }
        private double pxToDataY(int py) {
            if (renderPh <= 0) return renderYMin;
            return renderYMin + (double) (renderPy + renderPh - py) / renderPh * (renderYMax - renderYMin);
        }
        private static int clampPx(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

        void setData(int[][] hist, int nbX, int nbY, double aMin, double aMax,
                     double ldMin, double ldMax, int totalPts, Color baseColor,
                     List<Marginal> marginals, int[] alphaBars, int[] dBars) {
            this.hist = hist; this.nbX = nbX; this.nbY = nbY;
            this.aMin = aMin; this.aMax = aMax;
            this.ldMin = ldMin; this.ldMax = ldMax;
            this.totalPts = totalPts; this.baseColor = baseColor;
            this.marginals = marginals;
            this.alphaBars = alphaBars; this.dBars = dBars;
            this.hasData = true;
        }

        void setEmpty() { hasData = false; repaint(); }

        /** Alpha (top) strip height in pixels; the central plot resizes to fit. */
        void setAlphaStripH(int h) { margH = Math.max(24, h); revalidate(); repaint(); }
        /** D (right) strip width in pixels; the central plot resizes to fit. */
        void setDStripW(int w)     { margW = Math.max(24, w); revalidate(); repaint(); }

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
            int MT = mt(), MR = mr();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 20 || ph < 20) { g2.dispose(); return; }

            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            if (!hasData) {
                drawMsg(g2, "No STEP predictions — run Perform Analysis, or select an experiment",
                        px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                drawAxisTitles(g2, px, py, pw, ph);
                g2.dispose();
                return;
            }

            int maxCount = 0;
            for (int[] col : hist) for (int v : col) maxCount = Math.max(maxCount, v);
            if (maxCount == 0) {
                drawMsg(g2, "All bins empty", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                drawAxisTitles(g2, px, py, pw, ph);
                g2.dispose();
                return;
            }

            // ── Display range (viewport or full), saved for mouse maths ───
            double dxMin = (viewport != null) ? viewport[0] : aMin;
            double dxMax = (viewport != null) ? viewport[1] : aMax;
            double dyMin = (viewport != null) ? viewport[2] : ldMin;
            double dyMax = (viewport != null) ? viewport[3] : ldMax;
            renderXMin = dxMin; renderXMax = dxMax; renderYMin = dyMin; renderYMax = dyMax;
            renderPx = px; renderPy = py; renderPw = pw; renderPh = ph;

            // ── Heatmap cells (mapped by data coords so zoom/pan work) ─────
            double binWa = (aMax - aMin) / nbX;
            double binWd = (ldMax - ldMin) / nbY;
            double logMax = Math.log1p(maxCount);

            Shape oldClip = g2.getClip();
            g2.setClip(px, py, pw, ph);
            for (int ai = 0; ai < nbX; ai++) {
                for (int li = 0; li < nbY; li++) {
                    int cnt = hist[ai][li];
                    if (cnt == 0) continue;
                    double intensity = logScale ? Math.log1p(cnt) / logMax
                                                : (double) cnt / maxCount;
                    int cx  = mapXPx(aMin + ai * binWa,       dxMin, dxMax, px, pw);
                    int cx2 = mapXPx(aMin + (ai + 1) * binWa, dxMin, dxMax, px, pw);
                    int cy  = mapYPx(ldMin + (li + 1) * binWd, dyMin, dyMax, py, ph);
                    int cy2 = mapYPx(ldMin + li * binWd,       dyMin, dyMax, py, ph);
                    g2.setColor(mapColor(intensity, baseColor));
                    g2.fillRect(cx, cy, Math.max(1, cx2 - cx) + 1, Math.max(1, cy2 - cy) + 1);
                }
            }
            g2.setClip(oldClip);

            drawGrid(g2, dxMin, dxMax, dyMin, dyMax, px, py, pw, ph);
            drawFrame(g2, px, py, pw, ph);
            drawXTicks(g2, dxMin, dxMax, px, py, pw, ph);
            drawYTicks(g2, dyMin, dyMax, px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            drawColorBar(g2, maxCount, logMax, px, py, pw, ph);

            // ── Marginal KDE strips (use the display range) ───────────────
            drawAlphaMarginal(g2, px, py, pw, ph, dxMin, dxMax);
            drawDMarginal(g2, px, py, pw, ph, dyMin, dyMax);

            drawLegend(g2, px, py, pw);

            // ── Rubber-band zoom rectangle ────────────────────────────────
            if (dragging) {
                int rx = Math.min(dragX0, dragX1), ry = Math.min(dragY0, dragY1);
                int rw = Math.abs(dragX1 - dragX0), rh = Math.abs(dragY1 - dragY0);
                g2.setColor(new Color(0, 90, 200, 40));
                g2.fillRect(rx, ry, rw, rh);
                g2.setColor(new Color(0, 90, 200));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRect(rx, ry, rw, rh);
            }

            g2.dispose();
        }

        // ── Marginal strips ─────────────────────────────────────────────────

        /** Light-grey fill for the marginal histogram bars. */
        private static final Color BAR_FILL   = new Color(150, 150, 150, 90);
        private static final Color BAR_BORDER = new Color(150, 150, 150, 160);

        private void drawAlphaMarginal(Graphics2D g2, int px, int py, int pw, int ph,
                                       double dxMin, double dxMax) {
            int stripY = mt() - margH, stripH = margH - 6;
            if (stripH < 8) return;
            g2.setColor(new Color(250, 250, 250));
            g2.fillRect(px, stripY, pw, stripH);
            g2.setColor(new Color(190, 190, 190));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRect(px, stripY, pw, stripH);

            Shape oldClip = g2.getClip();
            g2.setClip(px, stripY, pw, stripH);

            // Histogram bars (combined over experiments) under the KDEs.
            int barMax = arrayMax(alphaBars);
            if (barMax > 0) {
                double bw = (aMax - aMin) / alphaBars.length;
                for (int i = 0; i < alphaBars.length; i++) {
                    if (alphaBars[i] == 0) continue;
                    int x0 = mapXPx(aMin + i * bw,       dxMin, dxMax, px, pw);
                    int x1 = mapXPx(aMin + (i + 1) * bw, dxMin, dxMax, px, pw);
                    int h  = (int) ((double) alphaBars[i] / barMax * (stripH - 2));
                    g2.setColor(BAR_FILL);
                    g2.fillRect(x0, stripY + stripH - h, Math.max(1, x1 - x0), h);
                    g2.setColor(BAR_BORDER);
                    g2.drawRect(x0, stripY + stripH - h, Math.max(1, x1 - x0), h);
                }
            }

            double[] evalA = linspace(aMin, aMax, NEVAL);
            for (Marginal m : marginals) {
                double kmax = arrayMax(m.alphaKde);
                if (kmax < 1e-300) continue;
                int[] xs = new int[NEVAL + 2], ys = new int[NEVAL + 2];
                for (int i = 0; i < NEVAL; i++) {
                    xs[i] = mapXPx(evalA[i], dxMin, dxMax, px, pw);
                    ys[i] = stripY + stripH - (int) (m.alphaKde[i] / kmax * (stripH - 2));
                }
                xs[NEVAL] = xs[NEVAL - 1]; ys[NEVAL] = stripY + stripH;
                xs[NEVAL + 1] = xs[0];     ys[NEVAL + 1] = stripY + stripH;
                fillStroke(g2, xs, ys, m.color);
            }
            g2.setClip(oldClip);
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawDMarginal(Graphics2D g2, int px, int py, int pw, int ph,
                                   double dyMin, double dyMax) {
            int stripX = px + pw + 6, stripW = margW - 8;
            if (stripW < 8) return;
            g2.setColor(new Color(250, 250, 250));
            g2.fillRect(stripX, py, stripW, ph);
            g2.setColor(new Color(190, 190, 190));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRect(stripX, py, stripW, ph);

            Shape oldClip = g2.getClip();
            g2.setClip(stripX, py, stripW, ph);

            // Histogram bars (combined) growing rightward from the plot edge.
            int barMax = arrayMax(dBars);
            if (barMax > 0) {
                double bw = (ldMax - ldMin) / dBars.length;
                for (int i = 0; i < dBars.length; i++) {
                    if (dBars[i] == 0) continue;
                    int y0 = mapYPx(ldMin + (i + 1) * bw, dyMin, dyMax, py, ph);
                    int y1 = mapYPx(ldMin + i * bw,       dyMin, dyMax, py, ph);
                    int w  = (int) ((double) dBars[i] / barMax * (stripW - 2));
                    g2.setColor(BAR_FILL);
                    g2.fillRect(stripX, y0, w, Math.max(1, y1 - y0));
                    g2.setColor(BAR_BORDER);
                    g2.drawRect(stripX, y0, w, Math.max(1, y1 - y0));
                }
            }

            double[] evalD = linspace(ldMin, ldMax, NEVAL);
            for (Marginal m : marginals) {
                double kmax = arrayMax(m.logdKde);
                if (kmax < 1e-300) continue;
                int[] xs = new int[NEVAL + 2], ys = new int[NEVAL + 2];
                for (int i = 0; i < NEVAL; i++) {
                    ys[i] = mapYPx(evalD[i], dyMin, dyMax, py, ph);
                    xs[i] = stripX + (int) (m.logdKde[i] / kmax * (stripW - 2));
                }
                xs[NEVAL] = stripX; ys[NEVAL] = ys[NEVAL - 1];
                xs[NEVAL + 1] = stripX; ys[NEVAL + 1] = ys[0];
                fillStroke(g2, xs, ys, m.color);
            }
            g2.setClip(oldClip);
            g2.setStroke(new BasicStroke(1f));
        }

        private static void fillStroke(Graphics2D g2, int[] xs, int[] ys, Color c) {
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
            g2.fillPolygon(xs, ys, xs.length);
            g2.setColor(c);
            g2.setStroke(new BasicStroke(1.3f));
            g2.drawPolyline(xs, ys, xs.length - 2);
        }

        // ── Legend (top-left inside plot) ───────────────────────────────────

        private void drawLegend(Graphics2D g2, int px, int py, int pw) {
            if (marginals.isEmpty()) return;
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            String total = "N = " + String.format("%,d", totalPts) + " frames";
            int lineH = 16, pad = 6;
            int maxW = fm.stringWidth(total);
            int rows = marginals.size() + 1;
            int boxW = maxW + 22 + pad * 2, boxH = rows * lineH + pad * 2;
            int lx = px + 6, ly = py + 6;

            g2.setColor(new Color(255, 255, 255, 215));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);

            int ry = ly + pad + lineH - 4;
            g2.setColor(new Color(70, 70, 70));
            g2.drawString(total, lx + pad, ry);
            for (Marginal m : marginals) {
                ry += lineH;
                g2.setColor(m.color);
                g2.fillRect(lx + pad, ry - 9, 12, 10);
                g2.setColor(new Color(90, 90, 90));
                g2.drawRect(lx + pad, ry - 9, 12, 10);
                g2.setColor(Color.BLACK);
                g2.drawString("(" + m.count + " frames)", lx + pad + 18, ry);
            }
        }

        // ── Colour mapping ────────────────────────────────────────────────

        static Color mapColor(double t, Color base) {
            t = Math.max(0, Math.min(1, t));
            int r = (int) (255 + (base.getRed()   - 255) * t);
            int grn = (int) (255 + (base.getGreen() - 255) * t);
            int b = (int) (255 + (base.getBlue()  - 255) * t);
            return new Color(clamp(r), clamp(grn), clamp(b));
        }
        private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

        // ── Colour-scale bar (far right, past the D marginal strip) ─────────

        private void drawColorBar(Graphics2D g2, int maxCount, double logMax,
                                  int px, int py, int pw, int ph) {
            int bx = px + pw + 6 + (margW - 8) + 12;
            int by = py, bh = ph;

            for (int y = 0; y < bh; y++) {
                double frac = (double) (bh - 1 - y) / (bh - 1);
                g2.setColor(mapColor(frac, baseColor));
                g2.fillRect(bx, by + y, CBAR_W, 1);
            }
            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(0.8f));
            g2.drawRect(bx, by, CBAR_W, bh);

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            FontMetrics fm = g2.getFontMetrics();
            int nTicks = 5;
            for (int i = 0; i <= nTicks; i++) {
                double frac = (double) i / nTicks;
                int    yp   = by + bh - (int) (frac * bh);
                g2.setColor(Color.DARK_GRAY);
                g2.drawLine(bx + CBAR_W, yp, bx + CBAR_W + 3, yp);
                double cnt = logScale ? Math.expm1(frac * logMax) : frac * maxCount;
                String lbl = cnt < 1 ? "0"
                           : cnt < 10000 ? String.valueOf((int) Math.round(cnt))
                           : String.format("%.1e", cnt).replace("e+0", "e").replace("e+", "e");
                g2.drawString(lbl, bx + CBAR_W + 5, yp + fm.getAscent() / 2 - 1);
            }

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            String title = logScale ? "log(count)" : "count";
            AffineTransform orig = g2.getTransform();
            g2.translate(bx + CBAR_W + 50, by + bh / 2.0);
            g2.rotate(-Math.PI / 2);
            fm = g2.getFontMetrics();
            g2.setColor(Color.DARK_GRAY);
            g2.drawString(title, -fm.stringWidth(title) / 2, fm.getAscent() / 2);
            g2.setTransform(orig);
        }

        // ── Grid / ticks / titles ───────────────────────────────────────────

        private static void drawGrid(Graphics2D g2, double xMin, double xMax,
                                     double yMin, double yMax,
                                     int px, int py, int pw, int ph) {
            g2.setColor(new Color(0, 0, 0, 40));
            g2.setStroke(new BasicStroke(0.5f));
            double xStep = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / xStep) * xStep; t <= xMax + xStep * 0.01; t += xStep) {
                int xp = mapXPx(t, xMin, xMax, px, pw);
                g2.drawLine(xp, py, xp, py + ph);
            }
            double yStep = niceStep(yMax - yMin, 5);
            for (double t = Math.ceil(yMin / yStep) * yStep; t <= yMax + yStep * 0.01; t += yStep) {
                int yp = mapYPx(t, yMin, yMax, py, ph);
                g2.drawLine(px, yp, px + pw, yp);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private static void drawXTicks(Graphics2D g2, double xMin, double xMax,
                                       int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / step) * step; t <= xMax + step * 0.01; t += step) {
                int xp = mapXPx(t, xMin, xMax, px, pw);
                g2.drawLine(xp, py + ph, xp, py + ph + 4);
                String lbl = fmtTick(t);
                g2.drawString(lbl, xp - fm.stringWidth(lbl) / 2, py + ph + 15);
            }
        }

        private static void drawYTicks(Graphics2D g2, double yMin, double yMax,
                                       int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = niceStep(yMax - yMin, 5);
            for (double t = Math.ceil(yMin / step) * step; t <= yMax + step * 0.01; t += step) {
                int yp = mapYPx(t, yMin, yMax, py, ph);
                g2.drawLine(px - 4, yp, px, yp);
                String lbl = fmtTick(t);
                g2.drawString(lbl, px - 6 - fm.stringWidth(lbl), yp + fm.getAscent() / 2 - 1);
            }
        }

        private static void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();
            String xTitle = "α  (anomalous exponent)";
            g2.drawString(xTitle, px + (pw - fm.stringWidth(xTitle)) / 2, py + ph + MB - 8);
            String yTitle = "log₁₀(D)";
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph / 2.0);
            g2.rotate(-Math.PI / 2);
            g2.drawString(yTitle, -fm.stringWidth(yTitle) / 2, fm.getAscent() / 2);
            g2.setTransform(orig);
        }

        // ── Helpers ─────────────────────────────────────────────────────────

        private static void drawFrame(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);
        }

        private static void drawMsg(Graphics2D g2, String msg, int px, int py, int pw, int ph) {
            g2.setColor(new Color(150, 150, 150));
            g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, px + (pw - fm.stringWidth(msg)) / 2, py + ph / 2 + fm.getAscent() / 2);
        }

        private static int mapXPx(double v, double vMin, double vMax, int ox, int w) {
            if (vMax == vMin) return ox;
            return ox + (int) ((v - vMin) / (vMax - vMin) * w);
        }
        private static int mapYPx(double v, double vMin, double vMax, int oy, int h) {
            if (vMax == vMin) return oy + h;
            return oy + h - (int) ((v - vMin) / (vMax - vMin) * h);
        }
        private static int arrayMax(int[] a) {
            int m = 0; for (int v : a) if (v > m) m = v; return m;
        }
        private static double arrayMax(double[] a) {
            double m = 0; for (double v : a) if (v > m) m = v; return m;
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
                return String.format("%.4g", v).replaceAll("0+$", "").replaceAll("\\.$", "");
            return String.format("%.2e", v).replace("e+0", "e").replace("e-0", "e-").replace("e+", "e");
        }
    }
}
