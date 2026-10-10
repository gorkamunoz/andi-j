package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * The (α, D) density heatmap shared by the "D vs α" tabs: 2-D histograms overlaid
 * per experiment (colour = experiment, opacity = its own density), marginal
 * histograms and KDEs of α (top) and D (right), colour-scale bar for a single
 * experiment, and a legend with one row per experiment.
 *
 * <p>{@link #show} bins the values; {@link DensityCanvas} draws them.</p>
 */
final class DvsAlphaDensity {

    private DvsAlphaDensity() {}

    static final int NEVAL = 100;   // KDE evaluation grid resolution

    /** One experiment's values: α and the D axis coordinate (log₁₀(D) on a log axis, D otherwise). */
    static final class Series {
        final Color    color;
        final double[] alpha, d;
        final int      all;        // values in the legend, immobile included
        final int      immobile;   // immobile values (legend), 0 if none
        Series(Color color, double[] alpha, double[] d, int all, int immobile) {
            this.color = color; this.alpha = alpha; this.d = d; this.all = all; this.immobile = immobile;
        }
    }

    /**
     * Bins {@code series} into {@code canvas} (or leaves it empty when there are no
     * values). The D range follows the data. α spans [0, 2], or, with
     * {@code scatterRanges}, the ranges of the Trajectory Analysis scatter: the data padded by
     * 5% on both axes (D in log space on a log axis).
     */
    static void show(DensityCanvas canvas, List<Series> series, int alphaBins, int dBins,
                     boolean logD, String yTitle, String countUnit, boolean immobileHidden,
                     boolean logCount, boolean scatterRanges) {
        double ldMin = Double.MAX_VALUE, ldMax = -Double.MAX_VALUE;
        double dataAMin = Double.MAX_VALUE, dataAMax = -Double.MAX_VALUE;
        int total = 0;
        for (Series se : series) {
            for (double v : se.d) { ldMin = Math.min(ldMin, v); ldMax = Math.max(ldMax, v); }
            for (double v : se.alpha) { dataAMin = Math.min(dataAMin, v); dataAMax = Math.max(dataAMax, v); }
            total += se.alpha.length;
        }
        if (total == 0) { canvas.setEmpty(); return; }

        double pad = scatterRanges ? 0.05 : 0.03;
        double lRng = ldMax - ldMin;
        if (lRng < 1e-6) { ldMin -= 0.5; ldMax += 0.5; lRng = 1; }
        ldMin -= pad * lRng;
        ldMax += pad * lRng;
        if (!logD && !scatterRanges) ldMin = Math.max(0, ldMin);
        double aMin = 0.0, aMax = 2.0;
        if (scatterRanges) {
            if (dataAMin == dataAMax) { dataAMin -= 0.1; dataAMax += 0.1; }
            double aPad = (dataAMax - dataAMin) * 0.05;
            aMin = dataAMin - aPad; aMax = dataAMax + aPad;
        }

        // Per-experiment 2-D histograms (overlaid, each in its own colour) plus
        // per-experiment marginal KDEs / histograms.
        double[] evalA = linspace(aMin, aMax, NEVAL);
        double[] evalD = linspace(ldMin, ldMax, NEVAL);
        List<Marginal>  margs     = new ArrayList<>();
        List<ExpHist2D> densities = new ArrayList<>();
        for (Series se : series) {
            double[] a = se.alpha, d = se.d;
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
            margs.add(new Marginal(se.color, a.length, se.all, se.immobile,
                Kde.gaussianKde1D(a, evalA, Kde.silvermanBw(a)),
                Kde.gaussianKde1D(d, evalD, Kde.silvermanBw(d)),
                aHist, dHist));
            densities.add(new ExpHist2D(se.color, h2, max2));
        }

        canvas.setData(densities, alphaBins, dBins, aMin, aMax, ldMin, ldMax, margs,
                       countUnit, immobileHidden, logD, yTitle);
        canvas.logScale = logCount;
        canvas.repaint();
    }

    /** Returns the bin index for value v in [vMin, vMax) clamped to [0, nBins-1]. */
    private static int bin(double v, double vMin, double vMax, int n) {
        int b = (int) ((v - vMin) / (vMax - vMin) * n);
        return Math.max(0, Math.min(n - 1, b));
    }

    static double[] linspace(double lo, double hi, int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = lo + (double) i / (n - 1) * (hi - lo);
        return a;
    }

    /** One experiment's 2-D (α × D) histogram, for the overlaid heatmap. */
    static class ExpHist2D {
        final Color   color;
        final int[][] hist;
        final int     max;
        ExpHist2D(Color color, int[][] hist, int max) {
            this.color = color; this.hist = hist; this.max = max;
        }
    }

    /** One experiment's precomputed marginal KDE curves and histograms. */
    static class Marginal {
        final Color    color;
        final int      count;       // plotted values
        final int      all;         // every value, immobile included (legend)
        final int      immobile;    // immobile values (legend)
        final double[] alphaKde;    // over [aMin, aMax]
        final double[] logdKde;     // over [ldMin, ldMax]
        final int[]    alphaHist;   // over [aMin, aMax], α bins
        final int[]    dHist;       // over [ldMin, ldMax], D bins
        Marginal(Color color, int count, int all, int immobile, double[] alphaKde,
                 double[] logdKde, int[] alphaHist, int[] dHist) {
            this.color = color; this.count = count; this.all = all; this.immobile = immobile;
            this.alphaKde = alphaKde; this.logdKde = logdKde;
            this.alphaHist = alphaHist; this.dHist = dHist;
        }
    }

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
        private int            nbX, nbY;   // α bins × D bins
        private boolean        logD = true;            // y coordinate is log₁₀(D), else D
        private String         yTitle = "D";
        private double         aMin, aMax, ldMin, ldMax;
        private String         countUnit = "frames";   // what one count is
        private boolean        immobileHidden;         // immobile data left out of the plot
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
                     double ldMin, double ldMax,
                     List<Marginal> marginals, String countUnit, boolean immobileHidden,
                     boolean logD, String yTitle) {
            this.logD = logD; this.yTitle = yTitle;
            this.densities = densities; this.nbX = nbX; this.nbY = nbY;
            this.aMin = aMin; this.aMax = aMax;
            this.ldMin = ldMin; this.ldMax = ldMax;
            this.marginals = marginals;
            this.countUnit = countUnit;
            this.immobileHidden = immobileHidden;
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
                drawHoverTip(g2, String.format("α = %.3f   D = %s", ax,
                                 PlotUtils.fmtTick(logD ? Math.pow(10, lyv) : lyv)),
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
            String[] rows = new String[marginals.size()];
            int maxW = 0;
            for (int i = 0; i < rows.length; i++) {
                Marginal m = marginals.get(i);
                rows[i] = String.format("%,d %s", m.all, countUnit)
                        + (m.immobile > 0 ? String.format(" | %,d immobile%s", m.immobile,
                                                          immobileHidden ? " (hidden)" : "") : "");
                maxW = Math.max(maxW, fm.stringWidth(rows[i]));
            }
            int lineH = 16, pad = 6;
            int boxW = maxW + 22 + pad * 2, boxH = rows.length * lineH + pad * 2;
            Point lp = legend.layout(px + 6, py + 6, boxW, boxH, px, py, pw, ph);
            int lx = lp.x, ly = lp.y;

            g2.setColor(new Color(255, 255, 255, 215));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);

            int ry = ly + pad - 4;
            for (int i = 0; i < rows.length; i++) {
                Marginal m = marginals.get(i);
                ry += lineH;
                g2.setColor(m.color);
                g2.fillRect(lx + pad, ry - 9, 12, 10);
                g2.setColor(new Color(90, 90, 90));
                g2.drawRect(lx + pad, ry - 9, 12, 10);
                g2.setColor(Color.BLACK);
                g2.drawString(rows[i], lx + pad + 18, ry);
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

        private void drawGrid(Graphics2D g2, double xMin, double xMax,
                                     double yMin, double yMax,
                                     int px, int py, int pw, int ph) {
            g2.setColor(new Color(0, 0, 0, 40));
            g2.setStroke(new BasicStroke(0.5f));
            double xStep = PlotUtils.niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / xStep) * xStep; t <= xMax + xStep * 0.01; t += xStep) {
                int xp = mapXPx(t, xMin, xMax, px, pw);
                g2.drawLine(xp, py, xp, py + ph);
            }
            for (double t : PlotUtils.axisTicks(yMin, yMax, logD, 5)) {
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

        private void drawYTicks(Graphics2D g2, double yMin, double yMax,
                                int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            for (double t : PlotUtils.axisTicks(yMin, yMax, logD, 5)) {
                int yp = mapYPx(t, yMin, yMax, py, ph);
                g2.drawLine(px - 4, yp, px, yp);
                String lbl = PlotUtils.fmtTick(logD ? Math.pow(10, t) : t);
                g2.drawString(lbl, px - 6 - fm.stringWidth(lbl), yp + fm.getAscent() / 2 - 1);
            }
        }

        private void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();
            String xTitle = "α  (anomalous exponent)";
            g2.drawString(xTitle, px + (pw - fm.stringWidth(xTitle)) / 2, py + ph + MB - 8);
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
