package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * "Full analysis" tab.
 *
 * One point per trajectory in a 2-D scatter plot:
 *   X axis  →  anomalous exponent  α
 *   Y axis  →  diffusion coefficient  D  (log scale by default)
 *
 * Independent time-lag fields for D and α; a single "Compute" button.
 * Same per-experiment 2D / X / Y toggle selectors as the other tabs.
 * The canvas supports rubber-band zoom, drag-to-pan, double-click reset.
 */
public class FullAnalysisPanel extends JPanel {

    private final List<ExperimentData>              experiments;
    private final Map<ExperimentData, Set<Integer>> componentSelection = new LinkedHashMap<>();
    private final List<JToggleButton>               allCompButtons     = new ArrayList<>();

    private final JTextField dLagsField, intervalField, unitField, alphaLagsField;
    private final ScatterPanel scatterPanel;

    // -----------------------------------------------------------------------

    FullAnalysisPanel(List<ExperimentData> experiments, double initInterval) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        setBorder(new EmptyBorder(6, 6, 6, 6));

        // Default: only 2D selected for every experiment
        for (ExperimentData exp : experiments) {
            Set<Integer> s = new LinkedHashSet<>();
            s.add(0);
            componentSelection.put(exp, s);
        }

        // ── parameter bar (North) ────────────────────────────────────────
        dLagsField     = new JTextField("[1,2]",                              8);
        intervalField  = new JTextField("1",      6);
        unitField      = new JTextField("second", 5);
        alphaLagsField = new JTextField("[1, 20]",                            8);
        JButton computeBtn = new JButton("Compute");
        computeBtn.setFont(computeBtn.getFont().deriveFont(Font.BOLD));

        JPanel paramBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramBar.add(new JLabel("D lags:"));          paramBar.add(dLagsField);
        paramBar.add(new JLabel("Frame interval:"));  paramBar.add(intervalField);
        paramBar.add(new JLabel("Unit:"));            paramBar.add(unitField);
        paramBar.add(Box.createHorizontalStrut(10));
        paramBar.add(new JLabel("α lags:"));          paramBar.add(alphaLagsField);
        paramBar.add(computeBtn);
        add(paramBar, BorderLayout.NORTH);

        // ── scatter canvas ───────────────────────────────────────────────
        scatterPanel = new ScatterPanel(experiments, componentSelection);

        // ── view toolbar ─────────────────────────────────────────────────
        JToggleButton zoomBtn = new JToggleButton("⊕ Zoom", true);
        JToggleButton panBtn  = new JToggleButton("✥ Pan",  false);
        ButtonGroup   viewBG  = new ButtonGroup();
        viewBG.add(zoomBtn); viewBG.add(panBtn);
        JButton   resetViewBtn = new JButton("↺ Reset view");
        JCheckBox logYBox      = new JCheckBox("Log D axis", true);
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

        // ── actions ───────────────────────────────────────────────────────
        computeBtn.addActionListener(e -> recompute());

        // Show pre-computed data immediately if available
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
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

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

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        btnRow.add(btn2D); btnRow.add(btnX); btnRow.add(btnY);
        row.add(btnRow);
        return row;
    }

    private static void styleToggle(JToggleButton btn) {
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 10f));
        btn.setPreferredSize(new Dimension(38, 20));
        btn.setMargin(new Insets(1, 2, 1, 2));
        btn.setFocusPainted(false);
    }

    // -----------------------------------------------------------------------
    // Recompute
    // -----------------------------------------------------------------------

    private void recompute() {
        LagSpec dSpec   = DiffusionCoeffPanel.parseTLags(dLagsField.getText());
        LagSpec aSpec   = DiffusionCoeffPanel.parseTLags(alphaLagsField.getText());
        double  interval = parseDblOr(intervalField.getText(), 1.0);
        String  unit     = unitField.getText().trim().isEmpty()
                           ? "frames" : unitField.getText().trim();

        for (ExperimentData exp : experiments) {
            exp.computeDiffusionCoefficients(dSpec, interval);
            exp.computeAnomalousExponents(aSpec);
        }
        scatterPanel.resetViewport();
        scatterPanel.setTimeUnit(unit);
        scatterPanel.repaint();
    }

    private static double parseDblOr(String s, double d) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return d; }
    }

    // =======================================================================
    // Scatter-plot canvas
    // =======================================================================

    static class ScatterPanel extends JPanel {

        private final List<ExperimentData>              experiments;
        private final Map<ExperimentData, Set<Integer>> componentSel;

        private boolean logY     = true;
        private String  timeUnit = "frames";

        // viewport: null = auto; {xMin, xMax, yMin, yMax}
        private double[] viewport = null;

        // saved render state for mouse ↔ data inverse transforms
        private double renderXMin = 0,  renderXMax = 2;
        private double renderYMin = 1e-4, renderYMax = 1;
        private int    renderPx   = ML, renderPy = MT, renderPw = 400, renderPh = 300;

        // zoom (rubber-band)
        private boolean panMode  = false;
        private boolean dragging = false;
        private int     dragX0, dragY0, dragX1, dragY1;

        // pan (drag)
        private boolean panning      = false;
        private int     panX0,  panY0;
        private double  panInitXMin, panInitXMax, panInitYMin, panInitYMax;

        // hover position (-1 = outside plot)
        private int hoverPx = -1, hoverPy = -1;

        private static final int ML   = 72, MR = 22, MT = 22, MB = 50;
        private static final int PT_R = 4;   // scatter point radius (px)

        // ── One plotted series: one experiment × one component ──────────
        private static class Series {
            final ExperimentData exp;
            final int            comp;
            final Color          color;
            final List<double[]> pts = new ArrayList<>();  // {alpha, D}
            Series(ExperimentData exp, int comp) {
                this.exp   = exp;  this.comp = comp;
                this.color = compColor(exp.color, comp);
            }
        }

        ScatterPanel(List<ExperimentData> exps,
                     Map<ExperimentData, Set<Integer>> sel) {
            this.experiments  = exps;
            this.componentSel = sel;
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(600, 420));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseInteraction();
        }

        // ── Public controls ─────────────────────────────────────────────

        void setLogY(boolean on) {
            if (logY != on) { logY = on; viewport = null; }
        }
        void setTimeUnit(String u) { this.timeUnit = u; }
        void resetViewport()       { this.viewport = null; }
        void setPanMode(boolean pan) {
            panMode = pan;
            setCursor(pan ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                          : Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        // ── Mouse interaction ────────────────────────────────────────────

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
                            ny0 = Math.pow(10, lMin + dy);
                            ny1 = Math.pow(10, lMax + dy);
                        } else {
                            double dy = (double)(e.getY() - panY0) / renderPh
                                        * (panInitYMax - panInitYMin);
                            ny0 = panInitYMin + dy;
                            ny1 = panInitYMax + dy;
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
                    } else if (panning) {
                        panning = false;
                    }
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
            addMouseListener(ma);
            addMouseMotionListener(ma);
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

        // ── Paint ────────────────────────────────────────────────────────

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

            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            boolean anyD     = experiments.stream().anyMatch(ExperimentData::hasD);
            boolean anyAlpha = experiments.stream().anyMatch(ExperimentData::hasAlpha);
            if (!anyD || !anyAlpha) {
                drawMsg(g2, "Click «Compute» to display results", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            // ── Gather series ─────────────────────────────────────────────
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
                else       { double d = Math.abs(yMin) * 0.1 + 1e-12; yMin -= d; yMax += d; }
            }

            double dispXMin, dispXMax, dispYMin, dispYMax;
            if (viewport != null) {
                dispXMin = viewport[0]; dispXMax = viewport[1];
                dispYMin = viewport[2]; dispYMax = viewport[3];
            } else {
                double xPad  = (xMax - xMin) * 0.05;
                dispXMin = xMin - xPad; dispXMax = xMax + xPad;
                if (logY) {
                    double lPad = (safeLog(yMax) - safeLog(yMin)) * 0.05;
                    dispYMin = Math.pow(10, safeLog(yMin) - lPad);
                    dispYMax = Math.pow(10, safeLog(yMax) + lPad);
                } else {
                    double yPad  = (yMax - yMin) * 0.05;
                    dispYMin = yMin - yPad; dispYMax = yMax + yPad;
                }
            }

            renderXMin = dispXMin; renderXMax = dispXMax;
            renderYMin = dispYMin; renderYMax = dispYMax;
            renderPx = px; renderPy = py; renderPw = pw; renderPh = ph;

            // ── Draw grid ─────────────────────────────────────────────────
            drawGrid(g2, dispXMin, dispXMax, dispYMin, dispYMax, px, py, pw, ph);

            // ── Draw points (clipped to plot area) ────────────────────────
            g2.setClip(px, py, pw, ph);
            for (Series s : seriesList) {
                Color fill   = new Color(s.color.getRed(), s.color.getGreen(),
                                          s.color.getBlue(), 100);
                Color border = s.color.darker();
                for (double[] pt : s.pts) {
                    int cx = mapX(pt[0], dispXMin, dispXMax, px, pw);
                    int cy = mapY(pt[1], dispYMin, dispYMax, py, ph);
                    g2.setColor(fill);
                    g2.fillOval(cx - PT_R, cy - PT_R, PT_R * 2, PT_R * 2);
                    g2.setColor(border);
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.drawOval(cx - PT_R, cy - PT_R, PT_R * 2, PT_R * 2);
                }
            }
            g2.setClip(null);
            g2.setStroke(new BasicStroke(1f));

            drawFrame(g2, px, py, pw, ph);
            drawXTicks(g2, dispXMin, dispXMax, px, py, pw, ph);
            drawYTicks(g2, dispYMin, dispYMax, px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            drawLegend(g2, seriesList, px, py, pw, ph);

            // ── Rubber-band overlay ───────────────────────────────────────
            if (dragging && !panMode) {
                int rx = Math.min(dragX0, dragX1), ry = Math.min(dragY0, dragY1);
                int rw = Math.abs(dragX1 - dragX0), rh = Math.abs(dragY1 - dragY0);
                g2.setColor(new Color(0, 100, 255, 40));
                g2.fillRect(rx, ry, rw, rh);
                g2.setColor(new Color(0, 100, 255, 200));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRect(rx, ry, rw, rh);
            }

            // ── Hover tooltip ─────────────────────────────────────────────
            if (hoverPx >= px && hoverPx <= px + pw
                    && hoverPy >= py && hoverPy <= py + ph) {
                double dataX = pxToDataX(hoverPx);
                double dataY = pxToDataY(hoverPy);
                String tip = String.format("α = %.3f     D = %s", dataX, fmtTick(dataY));
                drawHoverTooltip(g2, tip, hoverPx, hoverPy, W, H);
            }

            g2.dispose();
        }

        // ── Coordinate transforms ────────────────────────────────────────

        /** Linear X mapping (α axis – always linear). */
        private static int mapX(double v, double xMin, double xMax, int ox, int w) {
            if (xMax == xMin) return ox;
            return ox + (int) ((v - xMin) / (xMax - xMin) * w);
        }

        /** Y mapping – linear or log depending on {@code logY}. */
        private int mapY(double v, double yMin, double yMax, int oy, int h) {
            if (logY) {
                double lMin = safeLog(yMin), lMax = safeLog(yMax);
                if (lMax == lMin) return oy + h;
                return oy + h - (int) ((safeLog(v) - lMin) / (lMax - lMin) * h);
            }
            if (yMax == yMin) return oy + h;
            return oy + h - (int) ((v - yMin) / (yMax - yMin) * h);
        }

        private static double safeLog(double v) { return Math.log10(Math.max(v, 1e-300)); }

        // ── Grid ─────────────────────────────────────────────────────────

        private void drawGrid(Graphics2D g2,
                              double xMin, double xMax,
                              double yMin, double yMax,
                              int px, int py, int pw, int ph) {
            g2.setColor(new Color(220, 220, 220));
            g2.setStroke(new BasicStroke(0.5f));

            // X (linear)
            double xStep = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / xStep) * xStep;
                 t <= xMax + xStep * 0.01; t += xStep)
                g2.drawLine(mapX(t, xMin, xMax, px, pw), py,
                            mapX(t, xMin, xMax, px, pw), py + ph);

            // Y (log or linear)
            if (logY) {
                int lo = (int) Math.floor(safeLog(yMin));
                int hi = (int) Math.ceil (safeLog(yMax));
                for (int e = lo; e <= hi; e++) {
                    for (double m : new double[]{1, 2, 5}) {
                        double v = m * Math.pow(10, e);
                        if (v < yMin * 0.999 || v > yMax * 1.001) continue;
                        int yp = mapY(v, yMin, yMax, py, ph);
                        g2.drawLine(px, yp, px + pw, yp);
                    }
                }
            } else {
                double yStep = niceStep(yMax - yMin, 5);
                for (double t = Math.ceil(yMin / yStep) * yStep;
                     t <= yMax + yStep * 0.01; t += yStep)
                    g2.drawLine(px, mapY(t, yMin, yMax, py, ph),
                                px + pw, mapY(t, yMin, yMax, py, ph));
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // ── Tick marks ───────────────────────────────────────────────────

        private static void drawXTicks(Graphics2D g2,
                                       double xMin, double xMax,
                                       int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / step) * step;
                 t <= xMax + step * 0.01; t += step) {
                int p = mapX(t, xMin, xMax, px, pw);
                g2.drawLine(p, py + ph, p, py + ph + 4);
                String lbl = fmtTick(t);
                g2.drawString(lbl, p - fm.stringWidth(lbl) / 2, py + ph + 15);
            }
        }

        private void drawYTicks(Graphics2D g2,
                                double yMin, double yMax,
                                int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            if (logY) {
                int lo = (int) Math.floor(safeLog(yMin));
                int hi = (int) Math.ceil (safeLog(yMax));
                for (int e = lo; e <= hi; e++) {
                    double v = Math.pow(10, e);
                    if (v < yMin * 0.999 || v > yMax * 1.001) continue;
                    int p = mapY(v, yMin, yMax, py, ph);
                    g2.drawLine(px - 4, p, px, p);
                    String lbl = fmtTick(v);
                    g2.drawString(lbl, px - 6 - fm.stringWidth(lbl),
                                  p + fm.getAscent() / 2 - 1);
                    // minor ticks at 2× and 5× within decade
                    for (double m : new double[]{2, 5}) {
                        double mv = m * v;
                        if (mv < yMin * 0.999 || mv > yMax * 1.001) continue;
                        int mp = mapY(mv, yMin, yMax, py, ph);
                        g2.drawLine(px - 3, mp, px, mp);
                    }
                }
            } else {
                double step = niceStep(yMax - yMin, 5);
                for (double t = Math.ceil(yMin / step) * step;
                     t <= yMax + step * 0.01; t += step) {
                    int p = mapY(t, yMin, yMax, py, ph);
                    g2.drawLine(px - 4, p, px, p);
                    String lbl = fmtTick(t);
                    g2.drawString(lbl, px - 6 - fm.stringWidth(lbl),
                                  p + fm.getAscent() / 2 - 1);
                }
            }
        }

        // ── Axis titles ───────────────────────────────────────────────────

        private void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();

            String xTitle = "α (anomalous exponent)";
            g2.drawString(xTitle,
                px + (pw - fm.stringWidth(xTitle)) / 2,
                py + ph + MB - 8);

            String yTitle = "D  (μm² / " + timeUnit + ")";
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph / 2.0);
            g2.rotate(-Math.PI / 2);
            g2.drawString(yTitle, -fm.stringWidth(yTitle) / 2, fm.getAscent() / 2);
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
            int maxW  = 0;
            for (Series s : seriesList) {
                String lbl = s.exp.name + " (" + COMP_LABELS[s.comp] + ", "
                             + s.pts.size() + ")";
                maxW = Math.max(maxW, fm.stringWidth(lbl));
            }
            int boxW = maxW + 28 + pad * 2;
            int boxH = seriesList.size() * lineH + pad * 2;
            int lx = px + pw - boxW - 6, ly = py + 6;

            g2.setColor(new Color(255, 255, 255, 210));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);

            for (int i = 0; i < seriesList.size(); i++) {
                Series s  = seriesList.get(i);
                int    ry = ly + pad + (i + 1) * lineH - 3;
                Color  fill   = new Color(s.color.getRed(), s.color.getGreen(),
                                           s.color.getBlue(), 120);
                Color  border = s.color.darker();
                g2.setColor(fill);
                g2.fillOval(lx + pad, ry - 9, 12, 12);
                g2.setColor(border);
                g2.setStroke(new BasicStroke(0.8f));
                g2.drawOval(lx + pad, ry - 9, 12, 12);
                g2.setColor(Color.BLACK);
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g2.drawString(s.exp.name + " (" + COMP_LABELS[s.comp] + ", "
                              + s.pts.size() + ")",
                              lx + pad + 18, ry);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // ── Utilities ─────────────────────────────────────────────────────

        private static void drawFrame(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);
        }

        private static void drawMsg(Graphics2D g2, String msg,
                                    int px, int py, int pw, int ph) {
            g2.setColor(new Color(160, 160, 160));
            g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg,
                px + (pw - fm.stringWidth(msg)) / 2,
                py + ph / 2 + fm.getAscent() / 2);
        }

        private static Color compColor(Color base, int comp) {
            if (comp == 1) return new Color(
                Math.min(255, base.getRed()   + 55),
                Math.min(255, base.getGreen() + 55),
                Math.min(255, base.getBlue()  + 55));
            if (comp == 2) return base.darker();
            return base;
        }

        private static void drawHoverTooltip(Graphics2D g2, String text,
                                             int mx, int my, int W, int H) {
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(text), th = fm.getAscent();
            int pad = 4, bw = tw + pad * 2, bh = th + pad * 2;
            int bx = mx + 14, by = my - bh - 4;
            if (bx + bw > W - 4) bx = mx - bw - 6;
            if (by < 4) by = my + 10;
            g2.setColor(new Color(255, 255, 220, 230));
            g2.fillRoundRect(bx, by, bw, bh, 4, 4);
            g2.setColor(new Color(160, 160, 100));
            g2.setStroke(new BasicStroke(0.5f));
            g2.drawRoundRect(bx, by, bw, bh, 4, 4);
            g2.setColor(new Color(30, 30, 30));
            g2.drawString(text, bx + pad, by + pad + th - 1);
        }

        private static double niceStep(double range, int target) {
            if (range <= 0) return 1;
            double rough = range / target;
            int    exp   = (int) Math.floor(Math.log10(rough));
            double base  = Math.pow(10, exp);
            for (double m : new double[]{1, 2, 2.5, 5, 10})
                if (m * base >= rough) return m * base;
            return base * 10;
        }

        private static String fmtTick(double v) {
            if (v == 0) return "0";
            double abs = Math.abs(v);
            if (abs >= 0.001 && abs < 10000)
                return String.format("%.4g", v)
                             .replaceAll("0+$", "").replaceAll("\\.$", "");
            return String.format("%.2e", v)
                         .replace("e+0", "e").replace("e-0", "e-").replace("e+", "e");
        }
    }
}
