package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * "D vs α" tab.
 *
 * One point per trajectory in a 2-D scatter plot:
 *   X axis → anomalous exponent α   (from the Anomalous exponent tab)
 *   Y axis → diffusion coefficient D (from the Diffusion coefficient tab)
 *
 * D and α values are read directly from the pre-computed
 * {@link ExperimentData#dCoeffs} and {@link ExperimentData#alphaCoeffs}.
 * To change the lag range or frame interval used for each quantity,
 * visit the respective tab and click Compute there.
 *
 * Marginal KDE strips are always shown along the top (α) and right (D)
 * edges of the plot, tracking zoom and pan automatically.
 * Rubber-band zoom, drag-to-pan, double-click reset.
 */
public class FullAnalysisPanel extends JPanel {

    private final List<ExperimentData>              experiments;
    private final Map<ExperimentData, Set<Integer>> componentSelection = new LinkedHashMap<>();
    private final List<JToggleButton>               allCompButtons     = new ArrayList<>();

    private final ScatterPanel scatterPanel;

    // -----------------------------------------------------------------------

    FullAnalysisPanel(List<ExperimentData> experiments, double initInterval) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
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
        scatterPanel = new ScatterPanel(experiments, componentSelection);

        // ── view toolbar ─────────────────────────────────────────────────
        JToggleButton zoomBtn      = new JToggleButton("⊕ Zoom", true);
        JToggleButton panBtn       = new JToggleButton("✥ Pan",  false);
        JButton       resetViewBtn = new JButton("↺ Reset view");
        JCheckBox     logYBox      = new JCheckBox("Log D axis", true);
        ButtonGroup   bg           = new ButtonGroup();
        bg.add(zoomBtn); bg.add(panBtn);
        for (AbstractButton b : new AbstractButton[]{zoomBtn, panBtn, resetViewBtn, logYBox}) {
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            b.setFocusPainted(false);
        }
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolbar.add(zoomBtn); toolbar.add(panBtn); toolbar.add(resetViewBtn);
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(logYBox);

        zoomBtn     .addActionListener(e -> scatterPanel.setPanMode(false));
        panBtn      .addActionListener(e -> scatterPanel.setPanMode(true));
        resetViewBtn.addActionListener(e -> { scatterPanel.resetViewport(); scatterPanel.repaint(); });
        logYBox     .addActionListener(e -> { scatterPanel.setLogY(logYBox.isSelected()); scatterPanel.repaint(); });

        JPanel centerArea = new JPanel(new BorderLayout(0, 2));
        centerArea.add(toolbar,      BorderLayout.NORTH);
        centerArea.add(scatterPanel, BorderLayout.CENTER);

        // ── left experiment selector ──────────────────────────────────────
        JScrollPane leftScroll = new JScrollPane(buildExperimentsPanel());
        leftScroll.setPreferredSize(new Dimension(170, 0));
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, centerArea);
        split.setDividerLocation(180);
        split.setResizeWeight(0.0);
        add(split, BorderLayout.CENTER);

        if (experiments.stream().anyMatch(exp -> exp.hasD() && exp.hasAlpha()))
            scatterPanel.repaint();
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
    // Scatter-plot canvas
    // =======================================================================

    static class ScatterPanel extends JPanel {

        private final List<ExperimentData>              experiments;
        private final Map<ExperimentData, Set<Integer>> componentSel;

        private boolean logY = true;

        // Plot margins
        private static final int ML = 72;   // left  (Y-axis labels)
        private static final int MB = 50;   // bottom (X-axis labels + title)
        private static final int MT = 72;   // top   (alpha marginal strip)
        private static final int MR = 80;   // right (D marginal strip)

        // Marginal strip dimensions (within MT / MR)
        private static final int MARG_H = 60; // alpha marginal height
        private static final int MARG_W = 62; // D marginal width

        // viewport
        private double[] viewport = null;

        // saved render bounds (mouse ↔ data transforms)
        private double renderXMin = 0, renderXMax = 2;
        private double renderYMin = 1e-4, renderYMax = 1;
        private int    renderPx = ML, renderPy = MT, renderPw = 400, renderPh = 300;

        // zoom / pan
        private boolean panMode  = false;
        private boolean dragging = false;
        private int     dragX0, dragY0, dragX1, dragY1;
        private boolean panning  = false;
        private int     panX0, panY0;
        private double  panInitXMin, panInitXMax, panInitYMin, panInitYMax;

        private int hoverPx = -1, hoverPy = -1;
        private static final int PT_R = 4;

        // ── One series ───────────────────────────────────────────────────
        private static class Series {
            final ExperimentData exp;
            final int            comp;
            final Color          color;
            final List<double[]> pts = new ArrayList<>(); // {alpha, D}
            Series(ExperimentData exp, int comp) {
                this.exp = exp; this.comp = comp;
                this.color = compColor(exp.color, comp);
            }
        }

        // ────────────────────────────────────────────────────────────────

        ScatterPanel(List<ExperimentData> exps,
                     Map<ExperimentData, Set<Integer>> sel) {
            this.experiments = exps;
            this.componentSel = sel;
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(600, 460));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseInteraction();
        }

        void setLogY(boolean on)   { if (logY != on) { logY = on; viewport = null; } }
        void resetViewport()       { viewport = null; }
        void setPanMode(boolean pan) {
            panMode = pan;
            setCursor(pan ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                          : Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        // ── Mouse ─────────────────────────────────────────────────────────

        private void addMouseInteraction() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!panMode) {
                        dragging = true;
                        dragX0 = dragX1 = clamp(e.getX(), renderPx, renderPx + renderPw);
                        dragY0 = dragY1 = clamp(e.getY(), renderPy, renderPy + renderPh);
                    } else {
                        panning = true;
                        panX0 = e.getX(); panY0 = e.getY();
                        panInitXMin = renderXMin; panInitXMax = renderXMax;
                        panInitYMin = renderYMin; panInitYMax = renderYMax;
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragging) {
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
                    if (dragging) {
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
                    if (e.getClickCount() == 2) { viewport = null; repaint(); }
                }
                @Override public void mouseMoved(MouseEvent e) {
                    hoverPx = e.getX(); hoverPy = e.getY(); repaint();
                }
                @Override public void mouseExited(MouseEvent e) {
                    hoverPx = -1; repaint();
                }
            };
            addMouseListener(ma); addMouseMotionListener(ma);
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

            // ── Build series ──────────────────────────────────────────────
            List<Series> seriesList = new ArrayList<>();
            for (ExperimentData exp : experiments) {
                if (!exp.hasD() || !exp.hasAlpha()) continue;
                for (int comp : componentSel.getOrDefault(exp, Collections.emptySet())) {
                    Series s = new Series(exp, comp);
                    for (Integer id : exp.dCoeffs.keySet()) {
                        double[] d = exp.dCoeffs.get(id);
                        double[] a = exp.alphaCoeffs.get(id);
                        if (a == null) continue;
                        double dv = d[comp], av = a[comp];
                        if (Double.isFinite(dv) && Double.isFinite(av)
                                && dv >= 0 && av >= 0 && av <= 2
                                && (!logY || dv > 0))
                            s.pts.add(new double[]{av, dv});
                    }
                    if (!s.pts.isEmpty()) seriesList.add(s);
                }
            }

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
            g2.setClip(null);
            g2.setStroke(new BasicStroke(1f));

            drawFrame(g2, px, py, pw, ph);
            drawXTicks(g2, dispXMin, dispXMax, px, py, pw, ph);
            drawYTicks(g2, dispYMin, dispYMax, px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            drawLegend(g2, seriesList, px, py, pw, ph);

            // ── Marginal KDE strips (always visible) ──────────────────────
            drawAlphaMarginal(g2, seriesList, dispXMin, dispXMax, px, py, pw, ph);
            drawDMarginal    (g2, seriesList, dispYMin, dispYMax, px, py, pw, ph);

            // ── Rubber-band ───────────────────────────────────────────────
            if (dragging && !panMode) {
                int rx = Math.min(dragX0, dragX1), ry = Math.min(dragY0, dragY1);
                int rw = Math.abs(dragX1 - dragX0), rh = Math.abs(dragY1 - dragY0);
                g2.setColor(new Color(0,100,255,40));   g2.fillRect(rx,ry,rw,rh);
                g2.setColor(new Color(0,100,255,200));  g2.setStroke(new BasicStroke(1f));
                g2.drawRect(rx, ry, rw, rh);
            }

            // ── Hover tooltip ─────────────────────────────────────────────
            if (hoverPx >= px && hoverPx <= px+pw && hoverPy >= py && hoverPy <= py+ph) {
                double dataX = pxToDataX(hoverPx);
                double dataY = pxToDataY(hoverPy);
                String tip = String.format("α = %.3f     D = %s", dataX, fmtTick(dataY));
                drawHoverTooltip(g2, tip, hoverPx, hoverPy, W, H);
            }

            g2.dispose();
        }

        // ── Marginal KDE strips ───────────────────────────────────────────

        private void drawAlphaMarginal(Graphics2D g2, List<Series> seriesList,
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

            Map<ExperimentData, List<Double>> expAlphas = new LinkedHashMap<>();
            for (Series s : seriesList)
                expAlphas.computeIfAbsent(s.exp, k -> new ArrayList<>())
                         .addAll(collectAlphas(s.pts));

            int nEval = 100;
            double[] evalPts = linspace(xMin, xMax, nEval);
            Shape oldClip = g2.getClip();
            g2.setClip(px, stripY, pw, stripH);

            for (Map.Entry<ExperimentData, List<Double>> entry : expAlphas.entrySet()) {
                double[] vals = toArray(entry.getValue());
                if (vals.length < 2) continue;
                double[] kde = gaussianKde1D(vals, evalPts, silvermanBw(vals));
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

                Color c = entry.getKey().color;
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
                g2.fillPolygon(xs, ys, nEval + 2);
                g2.setColor(c);
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawPolyline(xs, ys, nEval);
            }
            g2.setClip(oldClip);
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawDMarginal(Graphics2D g2, List<Series> seriesList,
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

            Map<ExperimentData, List<Double>> expDs = new LinkedHashMap<>();
            for (Series s : seriesList) {
                List<Double> dl = expDs.computeIfAbsent(s.exp, k -> new ArrayList<>());
                for (double[] pt : s.pts)
                    dl.add(logY ? safeLog(pt[1]) : pt[1]);
            }

            Shape oldClip = g2.getClip();
            g2.setClip(stripX, py, stripW, ph);

            for (Map.Entry<ExperimentData, List<Double>> entry : expDs.entrySet()) {
                double[] vals = toArray(entry.getValue());
                if (vals.length < 2) continue;
                double[] kde = gaussianKde1D(vals, evalPts, silvermanBw(vals));
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

                Color c = entry.getKey().color;
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

        private static double[] gaussianKde1D(double[] data, double[] eval, double bw) {
            double[] result = new double[eval.length];
            if (data.length == 0 || bw <= 0) return result;
            double c = 1.0 / (data.length * bw * Math.sqrt(2.0 * Math.PI));
            for (int i = 0; i < eval.length; i++) {
                double s = 0;
                for (double d : data) { double u = (eval[i]-d)/bw; s += Math.exp(-0.5*u*u); }
                result[i] = s * c;
            }
            return result;
        }

        private static double silvermanBw(double[] data) {
            if (data.length < 2) return 1.0;
            double mean = 0; for (double d : data) mean += d; mean /= data.length;
            double var  = 0; for (double d : data) { double dd=d-mean; var+=dd*dd; }
            double std  = Math.sqrt(var / data.length);
            return std < 1e-10 ? 1.0 : 1.06 * std * Math.pow(data.length, -0.2);
        }

        // ── Coordinate transforms ─────────────────────────────────────────

        // alpha (X) axis is ALWAYS linear
        private static int mapX(double v, double xMin, double xMax, int ox, int w) {
            if (xMax == xMin) return ox;
            return ox + (int)((v - xMin) / (xMax - xMin) * w);
        }

        // D (Y) axis: linear or log depending on logY
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

            // X axis (alpha) is ALWAYS linear — never log
            g2.setColor(new Color(220, 220, 220));
            double xStep = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin/xStep)*xStep; t <= xMax+xStep*0.01; t += xStep)
                g2.drawLine(mapX(t,xMin,xMax,px,pw), py,
                            mapX(t,xMin,xMax,px,pw), py+ph);

            // Y axis (D) is log or linear depending on logY
            if (logY) {
                drawLogGridY(g2, yMin, yMax, xMin, xMax, px, py, pw, ph);
            } else {
                double yStep = niceStep(yMax - yMin, 5);
                for (double t = Math.ceil(yMin/yStep)*yStep; t <= yMax+yStep*0.01; t += yStep)
                    g2.drawLine(px, mapY(t,yMin,yMax,py,ph),
                                px+pw, mapY(t,yMin,yMax,py,ph));
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawLogGridY(Graphics2D g2, double yMin, double yMax,
                                   double xMin, double xMax,
                                   int px, int py, int pw, int ph) {
            int e0 = (int) Math.floor(safeLog(yMin));
            int e1 = (int) Math.ceil (safeLog(yMax));
            for (int e = e0; e <= e1; e++) for (int m : new int[]{1,2,5}) {
                double val = m * Math.pow(10, e);
                if (val < yMin || val > yMax) continue;
                boolean major = m == 1;
                g2.setColor(major ? new Color(200,200,200) : new Color(230,230,230));
                g2.setStroke(new BasicStroke(major ? 0.6f : 0.4f));
                g2.drawLine(px, mapY(val,yMin,yMax,py,ph),
                            px+pw, mapY(val,yMin,yMax,py,ph));
            }
        }

        // ── Tick marks ────────────────────────────────────────────────────

        // X ticks are always linear (alpha axis)
        private static void drawXTicks(Graphics2D g2,
                                        double xMin, double xMax,
                                        int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin/step)*step; t <= xMax+step*0.01; t += step) {
                int p = mapX(t, xMin, xMax, px, pw);
                g2.drawLine(p, py+ph, p, py+ph+4);
                String lbl = fmtTick(t);
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
                    String lbl = fmtTick(v);
                    g2.drawString(lbl, px-6-fm.stringWidth(lbl), p+fm.getAscent()/2-1);
                    for (int m : new int[]{2,5}) {
                        double mv = m * v;
                        if (mv < yMin*0.999 || mv > yMax*1.001) continue;
                        g2.drawLine(px-3, mapY(mv,yMin,yMax,py,ph), px, mapY(mv,yMin,yMax,py,ph));
                    }
                }
            } else {
                double step = niceStep(yMax - yMin, 5);
                for (double t = Math.ceil(yMin/step)*step; t <= yMax+step*0.01; t += step) {
                    int p = mapY(t, yMin, yMax, py, ph);
                    g2.drawLine(px-4, p, px, p);
                    String lbl = fmtTick(t);
                    g2.drawString(lbl, px-6-fm.stringWidth(lbl), p+fm.getAscent()/2-1);
                }
            }
        }

        // ── Axis titles ───────────────────────────────────────────────────

        private static void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();
            String xTitle = "α (anomalous exponent)";
            g2.drawString(xTitle, px + (pw - fm.stringWidth(xTitle))/2, py+ph+MB-8);
            String yTitle = "D  (μm²)";
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph/2.0);
            g2.rotate(-Math.PI/2);
            g2.drawString(yTitle, -fm.stringWidth(yTitle)/2, fm.getAscent()/2);
            g2.setTransform(orig);
        }

        // ── Legend ────────────────────────────────────────────────────────

        private static final String[] COMP_LABELS = {"2D", "X", "Y"};

        private static void drawLegend(Graphics2D g2, List<Series> seriesList,
                                        int px, int py, int pw, int ph) {
            if (seriesList.isEmpty()) return;
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int lineH = 18, pad = 6;
            int maxW = 0;
            for (Series s : seriesList) {
                String lbl = s.exp.name + " (" + COMP_LABELS[s.comp] + ", " + s.pts.size() + ")";
                maxW = Math.max(maxW, fm.stringWidth(lbl));
            }
            int boxW = maxW + 28 + pad*2, boxH = seriesList.size()*lineH + pad*2;
            int lx = px + pw - boxW - 6, ly = py + 6;
            g2.setColor(new Color(255,255,255,210));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180,180,180)); g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);
            for (int i = 0; i < seriesList.size(); i++) {
                Series s = seriesList.get(i);
                int ry = ly + pad + (i+1)*lineH - 3;
                Color fill   = new Color(s.color.getRed(),s.color.getGreen(),s.color.getBlue(),120);
                Color border = s.color.darker();
                g2.setColor(fill);   g2.fillOval(lx+pad, ry-9, 12, 12);
                g2.setColor(border); g2.setStroke(new BasicStroke(0.8f));
                g2.drawOval(lx+pad, ry-9, 12, 12);
                g2.setColor(Color.BLACK);
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g2.drawString(s.exp.name + " (" + COMP_LABELS[s.comp] + ", " + s.pts.size() + ")",
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
        private static double niceStep(double range, int target) {
            if (range<=0) return 1;
            double rough=range/target, base=Math.pow(10,(int)Math.floor(Math.log10(rough)));
            for (double m : new double[]{1,2,2.5,5,10}) if (m*base>=rough) return m*base;
            return base*10;
        }
        private static String fmtTick(double v) {
            if (v==0) return "0";
            double abs=Math.abs(v);
            if (abs>=0.001 && abs<10000)
                return String.format("%.4g",v).replaceAll("0+$","").replaceAll("\\.$","");
            return String.format("%.2e",v)
                         .replace("e+0","e").replace("e-0","e-").replace("e+","e");
        }
        private static double[] linspace(double lo, double hi, int n) {
            double[] a = new double[n];
            for (int i=0; i<n; i++) a[i] = lo + (double)i/(n-1)*(hi-lo);
            return a;
        }
        private static List<Double> collectAlphas(List<double[]> pts) {
            List<Double> out = new ArrayList<>(pts.size());
            for (double[] pt : pts) out.add(pt[0]);
            return out;
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
