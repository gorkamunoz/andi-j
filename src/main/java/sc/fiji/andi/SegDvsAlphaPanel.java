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
 * per-frame or per-segment predictions (far too many to scatter), the centre
 * shows a 2-D density histogram instead of individual points.  There is no
 * point selection / tagging.</p>
 *
 * <ul>
 *   <li>X axis – anomalous exponent α ∈ [0, 2]  (fixed range)</li>
 *   <li>Y axis – log₁₀(D), auto-ranged from the selected data</li>
 *   <li>Centre – 2-D histogram heatmaps, one per selected experiment, overlaid
 *       in the experiment's colour with opacity encoding its own density</li>
 *   <li>Top / right – per-experiment marginal KDE of α and log₁₀(D)</li>
 *   <li>Far right – colour-scale bar mapping shade → frame or segment count
 *       (only when a single experiment is selected)</li>
 * </ul>
 * Immobile segments, and in frame mode the frames of immobile segments, are left
 * out unless "Show immobile" is ticked. The legend gives the number of frames or
 * segments and, below, how many are immobile.
 *
 * Controls: frames or segments, per-experiment toggles (left), bin count,
 * log-count colour scale.
 */
public class SegDvsAlphaPanel extends JPanel {

    private final List<ExperimentData>         experiments;
    private final Map<ExperimentData, Boolean> expVisible = new LinkedHashMap<>();

    /** What one count of the histogram is: a frame or a segment. */
    private static final String[] LEVELS = {"Frames", "Segments"};

    private final JComboBox<String> levelBox;
    private final JSpinner       alphaBinsSpin, dBinsSpin;   // separate α / D bin counts
    private final JCheckBox      logCountBox;
    private final ImmobileBox    immobileBox = new ImmobileBox();
    private final DensityCanvas  canvas;

    // ═════════════════════════════════════════════════════════════════════
    // Constructor
    // ═════════════════════════════════════════════════════════════════════

    /** Display units shared by the window's tabs. */
    private final Units units;

    public SegDvsAlphaPanel(List<ExperimentData> experiments, Units units) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        this.units       = units;
        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        levelBox      = new JComboBox<>(LEVELS);
        alphaBinsSpin = new JSpinner(new SpinnerNumberModel(40, 5, 200, 1));
        dBinsSpin     = new JSpinner(new SpinnerNumberModel(40, 5, 200, 1));
        logCountBox   = new JCheckBox("Log count scale", true);
        canvas        = new DensityCanvas();

        add(Toolbars.makeScrollable(buildTopBar()), BorderLayout.NORTH);

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

        levelBox.addActionListener(e -> rebuild());
        immobileBox.addActionListener(e -> rebuild());
        alphaBinsSpin.addChangeListener(e -> rebuild());
        dBinsSpin.addChangeListener(e -> rebuild());
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

        levelBox.setToolTipText("Plot the α and D of every frame, or of every segment");
        alphaBinsSpin.setToolTipText("Number of α bins (histogram + heatmap x-axis)");
        dBinsSpin.setToolTipText("Number of log₁₀(D) bins (histogram + heatmap y-axis)");

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEtchedBorder());
        bar.add(levelBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(new JLabel("α bins:"));
        bar.add(alphaBinsSpin);
        bar.add(new JLabel("D bins:"));
        bar.add(dBinsSpin);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(logCountBox);
        bar.add(immobileBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(refreshBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(zoomBtn);
        bar.add(panBtn);
        bar.add(resetViewBtn);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(SvgExport.button(() -> canvas, "seg_d_vs_alpha"));
        bar.add(units.button());
        units.addListener(this::rebuild);
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
        boolean bySegment = levelBox.getSelectedIndex() == 1;

        List<ExperimentData> sel = new ArrayList<>();
        for (ExperimentData exp : experiments)
            if (expVisible.getOrDefault(exp, true)
                    && (bySegment ? exp.hasSegments() : exp.hasStepPredictions()))
                sel.add(exp);

        // Gather per-experiment per-frame (or per-segment) values and the global logD range
        Map<ExperimentData, double[]> alphaByExp = new LinkedHashMap<>();
        Map<ExperimentData, double[]> logdByExp  = new LinkedHashMap<>();
        double ldMin = Double.MAX_VALUE, ldMax = -Double.MAX_VALUE;
        int total = 0, immobileCount = 0;   // plotted values; immobile values (shown or not)
        Map<ExperimentData, Integer> allByExp = new LinkedHashMap<>();
        boolean showImmobile = immobileBox.isSelected();

        for (ExperimentData exp : sel) {
            List<Double> av = new ArrayList<>();
            List<Double> dv = new ArrayList<>();
            int immobileExp = 0;
            double off = Math.log10(units.diffusion(exp));   // log₁₀(D) in display units
            if (bySegment) {
                for (List<Segment> segs : exp.segments.values())
                    for (Segment s : segs)
                        if (Double.isFinite(s.alpha) && Double.isFinite(s.logD)) {
                            if (s.state == 0) {
                                immobileExp++;
                                if (!showImmobile) continue;
                            }
                            double d = s.logD + off;
                            av.add(s.alpha); dv.add(d);
                            ldMin = Math.min(ldMin, d);
                            ldMax = Math.max(ldMax, d);
                        }
            } else for (Map.Entry<Integer, double[]> e : exp.stepAlpha.entrySet()) {
                double[] al = e.getValue();
                double[] ld = exp.stepLogD != null ? exp.stepLogD.get(e.getKey()) : null;
                if (al == null || ld == null) continue;
                int len = Math.min(al.length, ld.length);
                boolean[] immobile = immobileFrames(exp, e.getKey(), len);
                for (int t = 0; t < len; t++) {
                    if (Double.isFinite(al[t]) && Double.isFinite(ld[t])) {
                        if (immobile != null && immobile[t]) {
                            immobileExp++;
                            if (!showImmobile) continue;
                        }
                        double d = ld[t] + off;
                        av.add(al[t]); dv.add(d);
                        ldMin = Math.min(ldMin, d);
                        ldMax = Math.max(ldMax, d);
                    }
                }
            }
            double[] aArr = toArray(av), dArr = toArray(dv);
            alphaByExp.put(exp, aArr);
            logdByExp.put(exp, dArr);
            total += aArr.length;
            immobileCount += immobileExp;
            allByExp.put(exp, showImmobile ? aArr.length : aArr.length + immobileExp);
        }

        // The legend's N counts every value; the line below it, the immobile ones.
        int all = showImmobile ? total : total + immobileCount;
        String note = immobileCount == 0 ? null
                : SegStatsPanel.immobileNote(immobileCount, all, bySegment ? "segments" : "frames",
                                             !showImmobile);
        immobileBox.setImmobilePresent(immobileCount > 0);
        if (total == 0) { canvas.setEmpty(); return; }

        double lRng = ldMax - ldMin;
        if (lRng < 1e-6) { ldMin -= 0.5; ldMax += 0.5; lRng = 1; }
        ldMin -= 0.03 * lRng;
        ldMax += 0.03 * lRng;
        final double aMin = 0.0, aMax = 2.0;

        // Per-experiment 2-D histograms (overlaid, each in its own colour) plus
        // per-experiment marginal KDEs / histograms.
        double[] evalA = linspace(aMin, aMax, NEVAL);
        double[] evalD = linspace(ldMin, ldMax, NEVAL);
        List<Marginal>  margs     = new ArrayList<>();
        List<ExpHist2D> densities = new ArrayList<>();
        for (ExperimentData exp : sel) {
            double[] a = alphaByExp.get(exp), d = logdByExp.get(exp);
            int[]   aHist = new int[alphaBins], dHist = new int[dBins];
            int[][] h2    = new int[alphaBins][dBins];
            int     max2  = 0;
            for (int i = 0; i < a.length; i++) {
                int ai = bin(a[i], aMin, aMax, alphaBins);
                int li = bin(d[i], ldMin, ldMax, dBins);
                aHist[ai]++;
                dHist[li]++;
                if (++h2[ai][li] > max2) max2 = h2[ai][li];
            }
            margs.add(new Marginal(exp.color, a.length, allByExp.get(exp),
                Kde.gaussianKde1D(a, evalA, Kde.silvermanBw(a)),
                Kde.gaussianKde1D(d, evalD, Kde.silvermanBw(d)),
                aHist, dHist));
            densities.add(new ExpHist2D(exp.color, h2, max2));
        }

        canvas.setData(densities, alphaBins, dBins, aMin, aMax, ldMin, ldMax, all, margs,
                       bySegment ? "segments" : "frames", note);
        canvas.logScale = logCountBox.isSelected();
        canvas.repaint();
    }

    /** Frames of a track that belong to an immobile segment; null when it has no segments. */
    private static boolean[] immobileFrames(ExperimentData exp, int trackId, int len) {
        List<Segment> segs = exp.segments != null ? exp.segments.get(trackId) : null;
        if (segs == null) return null;
        boolean[] immobile = new boolean[len];
        for (Segment s : segs)
            if (s.state == 0)
                for (int t = Math.max(0, s.start); t < Math.min(len, s.start + s.length); t++)
                    immobile[t] = true;
        return immobile;
    }

    /** One experiment's 2-D (α × log₁₀D) histogram, for the overlaid heatmap. */
    static class ExpHist2D {
        final Color   color;
        final int[][] hist;
        final int     max;
        ExpHist2D(Color color, int[][] hist, int max) {
            this.color = color; this.hist = hist; this.max = max;
        }
    }

    /** Returns the bin index for value v in [vMin, vMax) clamped to [0, nBins-1]. */
    private static int bin(double v, double vMin, double vMax, int n) {
        int b = (int) ((v - vMin) / (vMax - vMin) * n);
        return Math.max(0, Math.min(n - 1, b));
    }

    // ── KDE helpers ─────────────────────────────────────────────────────────



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

    /** One experiment's precomputed marginal KDE curves and histograms. */
    static class Marginal {
        final Color    color;
        final int      count;       // plotted values
        final int      all;         // every value, immobile included (legend)
        final double[] alphaKde;    // over [aMin, aMax]
        final double[] logdKde;     // over [ldMin, ldMax]
        final int[]    alphaHist;   // over [aMin, aMax], α bins
        final int[]    dHist;       // over [ldMin, ldMax], D bins
        Marginal(Color color, int count, int all, double[] alphaKde, double[] logdKde,
                 int[] alphaHist, int[] dHist) {
            this.color = color; this.count = count; this.all = all;
            this.alphaKde = alphaKde; this.logdKde = logdKde;
            this.alphaHist = alphaHist; this.dHist = dHist;
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
        private int            nbX, nbY;   // α bins × log₁₀(D) bins
        private double         aMin, aMax, ldMin, ldMax;
        private int            totalPts;
        private String         countUnit = "frames";   // what one count is
        private String         note;                   // hidden immobile data, or null
        private List<ExpHist2D> densities = java.util.Collections.emptyList();
        private List<Marginal> marginals = java.util.Collections.emptyList();
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
        private boolean dragDivH = false;   // dragging the α-strip / plot divider
        private boolean dragDivV = false;   // dragging the plot / D-strip divider
        private static final int DIV_HIT = 4;
        private final MovableLegend legend = new MovableLegend();
        private int hoverPx = -1, hoverPy = -1;

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

        // Divider positions: α strip ends at the plot top; D strip starts at the
        // plot right edge. Dragging them resizes the marginal strips.
        private boolean overHDiv(int x, int y) {
            return Math.abs(y - mt()) <= DIV_HIT && x >= ML && x <= getWidth() - mr();
        }
        private boolean overVDiv(int x, int y) {
            return Math.abs(x - (getWidth() - mr())) <= DIV_HIT
                   && y >= mt() && y <= getHeight() - MB;
        }

        private void addMouseInteraction() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (legend.press(e.getX(), e.getY())) return;
                    if (overHDiv(e.getX(), e.getY())) { dragDivH = true; return; }
                    if (overVDiv(e.getX(), e.getY())) { dragDivV = true; return; }
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
                    if (legend.dragTo(e.getX(), e.getY())) { repaint(); return; }
                    if (dragDivH) {
                        margH = Math.max(24, Math.min(e.getY() - 12, getHeight() - MB - 60));
                        revalidate(); repaint();
                    } else if (dragDivV) {
                        int w = (getWidth() - e.getX()) - 8 - CBAR_W - 66;
                        margW = Math.max(24, Math.min(w, getWidth() - ML - 100));
                        revalidate(); repaint();
                    } else if (dragging) {
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
                    if (dragDivH || dragDivV) { dragDivH = dragDivV = false; return; }
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
                @Override public void mouseMoved(MouseEvent e) {
                    hoverPx = e.getX(); hoverPy = e.getY();
                    int cur;
                    if (legend.over(e.getX(), e.getY()))   cur = Cursor.MOVE_CURSOR;
                    else if (overHDiv(e.getX(), e.getY()))      cur = Cursor.N_RESIZE_CURSOR;
                    else if (overVDiv(e.getX(), e.getY())) cur = Cursor.E_RESIZE_CURSOR;
                    else cur = panMode ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR;
                    setCursor(Cursor.getPredefinedCursor(cur));
                    repaint();
                }
                @Override public void mouseExited(MouseEvent e) { hoverPx = -1; repaint(); }
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

        void setData(List<ExpHist2D> densities, int nbX, int nbY, double aMin, double aMax,
                     double ldMin, double ldMax, int totalPts,
                     List<Marginal> marginals, String countUnit, String note) {
            this.densities = densities; this.nbX = nbX; this.nbY = nbY;
            this.aMin = aMin; this.aMax = aMax;
            this.ldMin = ldMin; this.ldMax = ldMax;
            this.totalPts = totalPts;
            this.marginals = marginals;
            this.countUnit = countUnit;
            this.note = note;
            this.hasData = true;
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
            int MT = mt(), MR = mr();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 20 || ph < 20) { g2.dispose(); return; }

            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            if (!hasData) {
                drawMsg(g2, "No predictions — run Perform Analysis, or select an experiment",
                        px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                drawAxisTitles(g2, px, py, pw, ph);
                g2.dispose();
                return;
            }

            int maxCount = 0;
            for (ExpHist2D d : densities) maxCount = Math.max(maxCount, d.max);
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

            // ── Overlaid per-experiment heatmaps ──────────────────────────
            // Each experiment is drawn in its own colour, with per-cell opacity
            // encoding its (own-max-normalised) density, so overlapping regions
            // blend instead of being summed.
            double binWa = (aMax - aMin) / nbX;
            double binWd = (ldMax - ldMin) / nbY;

            Shape oldClip = g2.getClip();
            g2.setClip(px, py, pw, ph);
            for (ExpHist2D dns : densities) {
                if (dns.max <= 0) continue;
                double lmax = Math.log1p(dns.max);
                int cr = dns.color.getRed(), cg = dns.color.getGreen(), cb = dns.color.getBlue();
                for (int ai = 0; ai < nbX; ai++) {
                    for (int li = 0; li < nbY; li++) {
                        int cnt = dns.hist[ai][li];
                        if (cnt == 0) continue;
                        double intensity = logScale ? Math.log1p(cnt) / lmax
                                                    : (double) cnt / dns.max;
                        int alpha = (int) (Math.max(0, Math.min(1, intensity)) * 210);
                        if (alpha <= 0) continue;
                        int cx  = mapXPx(aMin + ai * binWa,       dxMin, dxMax, px, pw);
                        int cx2 = mapXPx(aMin + (ai + 1) * binWa, dxMin, dxMax, px, pw);
                        int cy  = mapYPx(ldMin + (li + 1) * binWd, dyMin, dyMax, py, ph);
                        int cy2 = mapYPx(ldMin + li * binWd,       dyMin, dyMax, py, ph);
                        g2.setColor(new Color(cr, cg, cb, alpha));
                        g2.fillRect(cx, cy, Math.max(1, cx2 - cx) + 1, Math.max(1, cy2 - cy) + 1);
                    }
                }
            }
            g2.setClip(oldClip);

            drawGrid(g2, dxMin, dxMax, dyMin, dyMax, px, py, pw, ph);
            drawFrame(g2, px, py, pw, ph);
            drawXTicks(g2, dxMin, dxMax, px, py, pw, ph);
            drawYTicks(g2, dyMin, dyMax, px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            // The colour-scale bar is only meaningful for a single experiment.
            if (densities.size() == 1)
                drawColorBar(g2, densities.get(0).color, densities.get(0).max,
                             Math.log1p(densities.get(0).max), px, py, pw, ph);

            // ── Marginal KDE strips (use the display range) ───────────────
            drawAlphaMarginal(g2, px, py, pw, ph, dxMin, dxMax);
            drawDMarginal(g2, px, py, pw, ph, dyMin, dyMax);

            drawLegend(g2, px, py, pw, ph);

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

            // ── Hover readout ─────────────────────────────────────────────
            if (!dragging && !panning && hoverPx >= px && hoverPx <= px + pw
                    && hoverPy >= py && hoverPy <= py + ph) {
                double ax = pxToDataX(hoverPx), lyv = pxToDataY(hoverPy);
                drawHoverTip(g2, String.format("α = %.3f   log₁₀(D) = %.3f", ax, lyv),
                             hoverPx, hoverPy, W, H);
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

        // ── Marginal strips ─────────────────────────────────────────────────

        /** Translucent fill (α ≈ 0.5) of the experiment colour for its bars. */
        private static Color barFill(Color c) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 128);
        }

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

            // Each experiment is normalised to its OWN density max (histogram +
            // KDE in the same count/(N·binW) units), so its bars keep the same
            // height whether or not other experiments are shown, and the bars
            // still line up with its own KDE curve.
            double[] evalA = linspace(aMin, aMax, NEVAL);

            // Per-experiment histogram bars: face-colour only (no edge).
            for (Marginal m : marginals) {
                double bw = (aMax - aMin) / m.alphaHist.length;
                if (m.count <= 0 || bw <= 0) continue;
                double scale = alphaScale(m, stripH, bw);
                if (scale <= 0) continue;
                g2.setColor(barFill(m.color));
                for (int i = 0; i < m.alphaHist.length; i++) {
                    if (m.alphaHist[i] == 0) continue;
                    int x0 = mapXPx(aMin + i * bw,       dxMin, dxMax, px, pw);
                    int x1 = mapXPx(aMin + (i + 1) * bw, dxMin, dxMax, px, pw);
                    int h  = (int) (m.alphaHist[i] / (m.count * bw) * scale);
                    g2.fillRect(x0, stripY + stripH - h, Math.max(1, x1 - x0), h);
                }
            }

            // KDE curves: line only (no fill), each on its own density scale.
            g2.setStroke(new BasicStroke(1.4f));
            for (Marginal m : marginals) {
                double bw = (aMax - aMin) / m.alphaHist.length;
                double scale = alphaScale(m, stripH, bw);
                if (scale <= 0) continue;
                int[] xs = new int[NEVAL], ys = new int[NEVAL];
                for (int i = 0; i < NEVAL; i++) {
                    xs[i] = mapXPx(evalA[i], dxMin, dxMax, px, pw);
                    ys[i] = stripY + stripH - (int) (m.alphaKde[i] * scale);
                }
                g2.setColor(m.color);
                g2.drawPolyline(xs, ys, NEVAL);
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

            // Each experiment normalised to its own density max (see drawAlphaMarginal).
            for (Marginal m : marginals) {
                double bw = (ldMax - ldMin) / m.dHist.length;
                if (m.count <= 0 || bw <= 0) continue;
                double scale = dScale(m, stripW, bw);
                if (scale <= 0) continue;
                g2.setColor(barFill(m.color));
                for (int i = 0; i < m.dHist.length; i++) {
                    if (m.dHist[i] == 0) continue;
                    int y0 = mapYPx(ldMin + (i + 1) * bw, dyMin, dyMax, py, ph);
                    int y1 = mapYPx(ldMin + i * bw,       dyMin, dyMax, py, ph);
                    int w  = (int) (m.dHist[i] / (m.count * bw) * scale);
                    g2.fillRect(stripX, y0, w, Math.max(1, y1 - y0));
                }
            }

            double[] evalD = linspace(ldMin, ldMax, NEVAL);
            g2.setStroke(new BasicStroke(1.4f));
            for (Marginal m : marginals) {
                double bw = (ldMax - ldMin) / m.dHist.length;
                double scale = dScale(m, stripW, bw);
                if (scale <= 0) continue;
                int[] xs = new int[NEVAL], ys = new int[NEVAL];
                for (int i = 0; i < NEVAL; i++) {
                    ys[i] = mapYPx(evalD[i], dyMin, dyMax, py, ph);
                    xs[i] = stripX + (int) (m.logdKde[i] * scale);
                }
                g2.setColor(m.color);
                g2.drawPolyline(xs, ys, NEVAL);
            }
            g2.setClip(oldClip);
            g2.setStroke(new BasicStroke(1f));
        }

        /** Per-experiment α-strip density→pixel scale (own KDE + histogram max). */
        private double alphaScale(Marginal m, int stripH, double bw) {
            double dmax = arrayMax(m.alphaKde);
            if (m.count > 0 && bw > 0)
                dmax = Math.max(dmax, arrayMax(m.alphaHist) / (m.count * bw));
            return dmax > 0 ? (stripH - 2) / dmax : 0;
        }

        /** Per-experiment D-strip density→pixel scale (own KDE + histogram max). */
        private double dScale(Marginal m, int stripW, double bw) {
            double dmax = arrayMax(m.logdKde);
            if (m.count > 0 && bw > 0)
                dmax = Math.max(dmax, arrayMax(m.dHist) / (m.count * bw));
            return dmax > 0 ? (stripW - 2) / dmax : 0;
        }

        // ── Legend (top-left inside plot) ───────────────────────────────────

        private void drawLegend(Graphics2D g2, int px, int py, int pw, int ph) {
            if (marginals.isEmpty()) { legend.clear(); return; }
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            String total = "N = " + String.format("%,d", totalPts) + " " + countUnit;
            int lineH = 16, pad = 6;
            int maxW = Math.max(fm.stringWidth(total), note != null ? fm.stringWidth(note) : 0);
            int rows = marginals.size() + (note != null ? 2 : 1);
            int boxW = maxW + 22 + pad * 2, boxH = rows * lineH + pad * 2;
            Point lp = legend.layout(px + 6, py + 6, boxW, boxH, px, py, pw, ph);
            int lx = lp.x, ly = lp.y;

            g2.setColor(new Color(255, 255, 255, 215));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);

            int ry = ly + pad + lineH - 4;
            g2.setColor(new Color(70, 70, 70));
            g2.drawString(total, lx + pad, ry);
            if (note != null) { ry += lineH; g2.drawString(note, lx + pad, ry); }
            for (Marginal m : marginals) {
                ry += lineH;
                g2.setColor(m.color);
                g2.fillRect(lx + pad, ry - 9, 12, 10);
                g2.setColor(new Color(90, 90, 90));
                g2.drawRect(lx + pad, ry - 9, 12, 10);
                g2.setColor(Color.BLACK);
                g2.drawString("(" + String.format("%,d", m.all) + " " + countUnit + ")", lx + pad + 18, ry);
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

        private void drawColorBar(Graphics2D g2, Color base, int maxCount, double logMax,
                                  int px, int py, int pw, int ph) {
            int bx = px + pw + 6 + (margW - 8) + 12;
            int by = py, bh = ph;

            for (int y = 0; y < bh; y++) {
                double frac = (double) (bh - 1 - y) / (bh - 1);
                g2.setColor(mapColor(frac, base));
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
            double xStep = PlotUtils.niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / xStep) * xStep; t <= xMax + xStep * 0.01; t += xStep) {
                int xp = mapXPx(t, xMin, xMax, px, pw);
                g2.drawLine(xp, py, xp, py + ph);
            }
            double yStep = PlotUtils.niceStep(yMax - yMin, 5);
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
            double step = PlotUtils.niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / step) * step; t <= xMax + step * 0.01; t += step) {
                int xp = mapXPx(t, xMin, xMax, px, pw);
                g2.drawLine(xp, py + ph, xp, py + ph + 4);
                String lbl = PlotUtils.fmtTick(t, 0.01, 1000);
                g2.drawString(lbl, xp - fm.stringWidth(lbl) / 2, py + ph + 15);
            }
        }

        private static void drawYTicks(Graphics2D g2, double yMin, double yMax,
                                       int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = PlotUtils.niceStep(yMax - yMin, 5);
            for (double t = Math.ceil(yMin / step) * step; t <= yMax + step * 0.01; t += step) {
                int yp = mapYPx(t, yMin, yMax, py, ph);
                g2.drawLine(px - 4, yp, px, yp);
                String lbl = PlotUtils.fmtTick(t, 0.01, 1000);
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


    }
}
