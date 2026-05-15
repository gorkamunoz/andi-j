package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * "TMSD Visualization" tab.
 *
 * Layout
 * ──────
 *   North  : lag parameter controls + Compute button
 *   Center : JSplitPane
 *              Left  – per-experiment compact grid: two rows (Avg / All),
 *                      each with three independent [2D] [X] [Y] toggle buttons
 *              Right – custom plot canvas (log-log or linear depending on checkbox)
 *
 * Component colour convention (same as DiffusionCoeffPanel):
 *   2D = experiment base colour
 *   X  = lighter shade  (+55 on each channel)
 *   Y  = darker shade   (.darker())
 */
public class TmsdPanel extends JPanel {

    private static final String[] COMP_LABELS = {"2D", "X", "Y"};

    private final List<ExperimentData> experiments;
    private final TmsdPlotPanel plotPanel;

    // Which components are shown for the average line / individual trajs, per experiment
    private final Map<ExperimentData, Set<Integer>> avgComps = new LinkedHashMap<>();
    private final Map<ExperimentData, Set<Integer>> allComps = new LinkedHashMap<>();

    // All component toggle buttons – used by the "Deselect all" action
    private final List<JToggleButton> allToggleButtons = new ArrayList<>();

    // Parameter fields
    private final JTextField minLagField, maxLagField, nLagsField, intervalField, unitField;
    private final JCheckBox  logSpacedBox;

    TmsdPanel(List<ExperimentData> experiments, int initMaxLag, double initInterval) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        setBorder(new EmptyBorder(6, 6, 6, 6));

        // Default: only Avg-2D on, everything else off
        for (ExperimentData exp : experiments) {
            Set<Integer> av = new LinkedHashSet<>();
            av.add(0);
            avgComps.put(exp, av);
            allComps.put(exp, new LinkedHashSet<>());
        }

        this.plotPanel = new TmsdPlotPanel(experiments);

        // ── parameter bar (North) ────────────────────────────────────────
        minLagField   = new JTextField(String.valueOf(1),          4);
        maxLagField   = new JTextField(String.valueOf(initMaxLag), 4);
        nLagsField    = new JTextField("15",                       4);
        logSpacedBox  = new JCheckBox("Log-spaced", false);          // default: LINEAR
        intervalField = new JTextField(String.format("%.3f", initInterval), 6);
        unitField     = new JTextField("frames",                   6);
        JButton computeBtn = new JButton("Compute tMSD");

        JPanel paramBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramBar.add(new JLabel("Min lag:")); paramBar.add(minLagField);
        paramBar.add(new JLabel("Max lag:")); paramBar.add(maxLagField);
        paramBar.add(new JLabel("N lags:"));  paramBar.add(nLagsField);
        paramBar.add(logSpacedBox);
        paramBar.add(Box.createHorizontalStrut(10));
        paramBar.add(new JLabel("Frame interval:")); paramBar.add(intervalField);
        paramBar.add(new JLabel("Unit:"));            paramBar.add(unitField);
        paramBar.add(computeBtn);
        add(paramBar, BorderLayout.NORTH);

        // ── split pane (Center) ──────────────────────────────────────────
        JPanel leftPanel = buildExperimentsPanel();
        JScrollPane leftScroll = new JScrollPane(leftPanel);
        leftScroll.setPreferredSize(new Dimension(190, 0));
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                                          leftScroll, plotPanel);
        split.setDividerLocation(200);
        split.setResizeWeight(0.0);
        add(split, BorderLayout.CENTER);

        // ── Compute action ───────────────────────────────────────────────
        computeBtn.addActionListener(e -> recompute());
    }

    // -----------------------------------------------------------------------
    // Left panel
    // -----------------------------------------------------------------------

    private JPanel buildExperimentsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        // "Deselect all" clears every Avg/All × 2D/X/Y toggle
        JButton deselectAll = new JButton("Deselect all");
        deselectAll.setFont(deselectAll.getFont().deriveFont(Font.PLAIN, 10f));
        deselectAll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        deselectAll.setFocusPainted(false);
        deselectAll.addActionListener(e -> {
            for (ExperimentData exp : experiments) {
                avgComps.get(exp).clear();
                allComps.get(exp).clear();
            }
            for (JToggleButton btn : allToggleButtons) btn.setSelected(false);
            plotPanel.repaint();
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

    /**
     * One row per experiment (vertical stack):
     *   ■ exp_name
     *   Avg  [2D] [X] [Y]     ← primary: average curve
     *   All  [2D] [X] [Y]     ← secondary: individual trajectories
     */
    private JPanel makeExpRow(ExperimentData exp) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JPanel swatch = new JPanel();
        swatch.setBackground(exp.color);
        swatch.setPreferredSize(new Dimension(12, 12));
        swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        nameRow.add(swatch);
        JLabel nameLbl = new JLabel(exp.name);
        nameLbl.setFont(nameLbl.getFont().deriveFont(Font.PLAIN, 11f));
        nameRow.add(nameLbl);
        row.add(nameRow);

        row.add(makeCompToggleRow("Avg", avgComps.get(exp), new boolean[]{true,  false, false}));
        row.add(makeCompToggleRow("All", allComps.get(exp), new boolean[]{false, false, false}));
        return row;
    }

    /** One row: label + [2D] [X] [Y] independent toggle buttons. */
    private JPanel makeCompToggleRow(String label, Set<Integer> sel, boolean[] defaults) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1));
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 9f));
        lbl.setPreferredSize(new Dimension(22, 16));
        row.add(lbl);
        for (int c = 0; c < 3; c++) {
            final int comp = c;
            JToggleButton btn = new JToggleButton(COMP_LABELS[c], defaults[c]);
            btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 9f));
            btn.setPreferredSize(new Dimension(30, 18));
            btn.setMargin(new Insets(0, 1, 0, 1));
            btn.setFocusPainted(false);
            btn.addActionListener(e -> {
                if (btn.isSelected()) sel.add(comp); else sel.remove(comp);
                plotPanel.repaint();
            });
            allToggleButtons.add(btn);
            row.add(btn);
        }
        return row;
    }

    // -----------------------------------------------------------------------
    // Compute
    // -----------------------------------------------------------------------

    private void recompute() {
        int     minL     = parseIntOr(minLagField.getText(),    1);
        int     maxL     = parseIntOr(maxLagField.getText(),   20);
        int     n        = parseIntOr(nLagsField.getText(),    15);
        boolean logS     = logSpacedBox.isSelected();
        double  interval = parseDblOr(intervalField.getText(), 1.0);
        String  unit     = unitField.getText().trim();
        if (unit.isEmpty()) unit = "frames";

        for (ExperimentData exp : experiments)
            exp.computeTamsd(minL, maxL, n, logS, interval);

        plotPanel.setLogScale(logS);
        plotPanel.setTimeUnit(unit);
        plotPanel.repaint();
    }

    private static int    parseIntOr(String s, int    d) { try { return Integer.parseInt(s.trim());   } catch (Exception e) { return d; } }
    private static double parseDblOr(String s, double d) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return d; } }

    // -----------------------------------------------------------------------
    // Component colour helper (shared with DiffusionCoeffPanel)
    // -----------------------------------------------------------------------

    static Color compColor(Color base, int comp) {
        if (comp == 1) return new Color(
            Math.min(255, base.getRed()   + 55),
            Math.min(255, base.getGreen() + 55),
            Math.min(255, base.getBlue()  + 55));
        if (comp == 2) return base.darker();
        return base;
    }

    // ======================================================================
    // Plot canvas — non-static inner class to access avgComps / allComps
    // ======================================================================

    class TmsdPlotPanel extends JPanel {

        private final List<ExperimentData> experiments;
        private String  timeUnit = "frames";
        private boolean logScale = false;   // default: linear axes

        // Plot margins
        private static final int ML = 68, MR = 22, MT = 22, MB = 52;

        TmsdPlotPanel(List<ExperimentData> exps) {
            this.experiments = exps;
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(560, 420));
        }

        void setTimeUnit(String u) { this.timeUnit = u; }
        void setLogScale(boolean b) { this.logScale = b; }

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

            double[] bounds = computeBounds();
            if (bounds == null) {
                drawNoDataMessage(g2, px, py, pw, ph);
            } else {
                double xMin = bounds[0], xMax = bounds[1];
                double yMin = bounds[2], yMax = bounds[3];

                drawGrid(g2, xMin, xMax, yMin, yMax, px, py, pw, ph);

                g2.setClip(px, py, pw, ph);
                drawCurves(g2, xMin, xMax, yMin, yMax, px, py, pw, ph);
                g2.setClip(null);

                drawTicks(g2, xMin, xMax, yMin, yMax, px, py, pw, ph);
                drawLegend(g2, px, py, pw, ph);
            }

            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            g2.dispose();
        }

        // ------------------------------------------------------------------
        // Unified coordinate mapping (dispatches on logScale)
        // ------------------------------------------------------------------

        private int mapX(double v, double xMin, double xMax, int ox, int w) {
            if (logScale) {
                double lMin = Math.log10(xMin), lMax = Math.log10(xMax);
                return ox + (int) ((Math.log10(Math.max(v, 1e-300)) - lMin) / (lMax - lMin) * w);
            }
            return ox + (int) ((v - xMin) / (xMax - xMin) * w);
        }

        private int mapY(double v, double yMin, double yMax, int oy, int h) {
            if (logScale) {
                double lMin = Math.log10(yMin), lMax = Math.log10(yMax);
                return oy + h - (int) ((Math.log10(Math.max(v, 1e-300)) - lMin) / (lMax - lMin) * h);
            }
            return oy + h - (int) ((v - yMin) / (yMax - yMin) * h);
        }

        // ------------------------------------------------------------------
        // Bounds
        // ------------------------------------------------------------------

        private double[] computeBounds() {
            double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE;
            double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;

            for (ExperimentData exp : experiments) {
                if (!exp.hasTamsd()) continue;
                Set<Integer> av = avgComps.getOrDefault(exp, Collections.emptySet());
                Set<Integer> al = allComps.getOrDefault(exp, Collections.emptySet());
                if (av.isEmpty() && al.isEmpty()) continue;

                for (double t : exp.tAxis) {
                    if (logScale ? t > 0 : Double.isFinite(t)) {
                        xMin = Math.min(xMin, t); xMax = Math.max(xMax, t);
                    }
                }
                for (int comp : av) {
                    for (double d : exp.avgTamsdComp[comp]) {
                        boolean ok = logScale ? (!Double.isNaN(d) && d > 0) : Double.isFinite(d);
                        if (ok) { yMin = Math.min(yMin, d); yMax = Math.max(yMax, d); }
                    }
                }
                for (int comp : al) {
                    for (double[][] byComp : exp.tamsdByComp.values()) {
                        for (double d : byComp[comp]) {
                            boolean ok = logScale ? (!Double.isNaN(d) && d > 0) : Double.isFinite(d);
                            if (ok) { yMin = Math.min(yMin, d); yMax = Math.max(yMax, d); }
                        }
                    }
                }
            }

            if (xMin == Double.MAX_VALUE || yMin == Double.MAX_VALUE) return null;

            if (logScale) {
                if (xMin == xMax) xMax = xMin * 10;
                if (yMin == yMax) yMax = yMin * 10;
                double dX = (Math.log10(xMax) - Math.log10(xMin)) * 0.05;
                double dY = (Math.log10(yMax) - Math.log10(yMin)) * 0.05;
                if (dX == 0) dX = 0.2; if (dY == 0) dY = 0.2;
                return new double[]{
                    Math.pow(10, Math.log10(xMin) - dX), Math.pow(10, Math.log10(xMax) + dX),
                    Math.pow(10, Math.log10(yMin) - dY), Math.pow(10, Math.log10(yMax) + dY)
                };
            } else {
                if (xMin == xMax) { xMin -= 1; xMax += 1; }
                if (yMin == yMax) { yMin -= Math.abs(yMin)*0.1 + 0.1; yMax += Math.abs(yMax)*0.1 + 0.1; }
                double padX = (xMax - xMin) * 0.05;
                double padY = (yMax - yMin) * 0.05;
                return new double[]{xMin - padX, xMax + padX,
                                    Math.min(0, yMin - padY), yMax + padY};
            }
        }

        // ------------------------------------------------------------------
        // Grid
        // ------------------------------------------------------------------

        private void drawGrid(Graphics2D g2,
                               double xMin, double xMax, double yMin, double yMax,
                               int px, int py, int pw, int ph) {
            g2.setStroke(new BasicStroke(0.5f));
            if (logScale) {
                drawLogGridForAxis(g2, xMin, xMax, true,  xMin, xMax, yMin, yMax, px, py, pw, ph);
                drawLogGridForAxis(g2, yMin, yMax, false, xMin, xMax, yMin, yMax, px, py, pw, ph);
            } else {
                g2.setColor(new Color(220, 220, 220));
                double xStep = niceStep(xMax - xMin, 6);
                for (double t = Math.ceil(xMin / xStep) * xStep; t <= xMax + xStep * 0.01; t += xStep) {
                    int p = mapX(t, xMin, xMax, px, pw);
                    g2.drawLine(p, py, p, py + ph);
                }
                double yStep = niceStep(yMax - yMin, 5);
                for (double t = Math.floor(yMin / yStep) * yStep; t <= yMax + yStep * 0.01; t += yStep) {
                    if (t < yMin) continue;
                    int p = mapY(t, yMin, yMax, py, ph);
                    g2.drawLine(px, p, px + pw, p);
                }
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawLogGridForAxis(Graphics2D g2, double vMin, double vMax, boolean isX,
                                         double xMin, double xMax, double yMin, double yMax,
                                         int px, int py, int pw, int ph) {
            int e0 = (int) Math.floor(Math.log10(vMin));
            int e1 = (int) Math.ceil (Math.log10(vMax));
            for (int e = e0; e <= e1; e++) {
                for (int m : new int[]{1, 2, 5}) {
                    double val = m * Math.pow(10, e);
                    if (val < vMin || val > vMax) continue;
                    boolean major = (m == 1);
                    g2.setColor(major ? new Color(200, 200, 200) : new Color(230, 230, 230));
                    g2.setStroke(new BasicStroke(major ? 0.6f : 0.4f));
                    if (isX) { int p = mapX(val, xMin, xMax, px, pw); g2.drawLine(p, py, p, py + ph); }
                    else     { int p = mapY(val, yMin, yMax, py, ph); g2.drawLine(px, p, px + pw, p); }
                }
            }
        }

        // ------------------------------------------------------------------
        // Tick marks + labels
        // ------------------------------------------------------------------

        private void drawTicks(Graphics2D g2,
                                double xMin, double xMax, double yMin, double yMax,
                                int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();

            if (logScale) {
                drawLogTicksForAxis(g2, fm, xMin, xMax, true,  xMin, xMax, yMin, yMax, px, py, pw, ph);
                drawLogTicksForAxis(g2, fm, yMin, yMax, false, xMin, xMax, yMin, yMax, px, py, pw, ph);
            } else {
                // X ticks
                double xStep = niceStep(xMax - xMin, 6);
                for (double t = Math.ceil(xMin / xStep) * xStep; t <= xMax + xStep * 0.01; t += xStep) {
                    int p = mapX(t, xMin, xMax, px, pw);
                    g2.drawLine(p, py + ph, p, py + ph + 5);
                    String lbl = fmtLinear(t);
                    g2.drawString(lbl, p - fm.stringWidth(lbl) / 2, py + ph + 17);
                }
                // Y ticks
                double yStep = niceStep(yMax - yMin, 5);
                for (double t = Math.floor(yMin / yStep) * yStep; t <= yMax + yStep * 0.01; t += yStep) {
                    if (t < yMin) continue;
                    int p = mapY(t, yMin, yMax, py, ph);
                    g2.drawLine(px - 5, p, px, p);
                    String lbl = fmtLinear(t);
                    g2.drawString(lbl, px - 6 - fm.stringWidth(lbl),
                                  p + fm.getAscent() / 2 - 1);
                }
            }
        }

        private void drawLogTicksForAxis(Graphics2D g2, FontMetrics fm,
                                          double vMin, double vMax, boolean isX,
                                          double xMin, double xMax, double yMin, double yMax,
                                          int px, int py, int pw, int ph) {
            int e0 = (int) Math.floor(Math.log10(vMin));
            int e1 = (int) Math.ceil (Math.log10(vMax));
            for (int e = e0; e <= e1; e++) {
                for (int m = 1; m <= 9; m++) {
                    double val = m * Math.pow(10, e);
                    if (val < vMin || val > vMax) continue;
                    boolean major = (m == 1);
                    int tickLen = major ? 5 : 3;
                    if (isX) {
                        int p = mapX(val, xMin, xMax, px, pw);
                        g2.drawLine(p, py + ph, p, py + ph + tickLen);
                        if (major) {
                            String lbl = fmtLog(val);
                            g2.drawString(lbl, p - fm.stringWidth(lbl) / 2, py + ph + 17);
                        }
                    } else {
                        int p = mapY(val, yMin, yMax, py, ph);
                        g2.drawLine(px - tickLen, p, px, p);
                        if (major) {
                            String lbl = fmtLog(val);
                            g2.drawString(lbl, px - 6 - fm.stringWidth(lbl),
                                          p + fm.getAscent() / 2 - 1);
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Curves – All (secondary) then Avg (primary)
        // ------------------------------------------------------------------

        private void drawCurves(Graphics2D g2,
                                  double xMin, double xMax, double yMin, double yMax,
                                  int px, int py, int pw, int ph) {
            // Pass 1 – individual trajectories (semi-transparent)
            for (ExperimentData exp : experiments) {
                if (!exp.hasTamsd()) continue;
                Set<Integer> al = allComps.getOrDefault(exp, Collections.emptySet());
                if (al.isEmpty()) continue;
                Composite save = g2.getComposite();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
                g2.setStroke(new BasicStroke(1f));
                for (int comp : al) {
                    g2.setColor(compColor(exp.color, comp));
                    for (double[][] byComp : exp.tamsdByComp.values())
                        drawPolyline(g2, exp.tAxis, byComp[comp],
                                     xMin, xMax, yMin, yMax, px, py, pw, ph);
                }
                g2.setComposite(save);
            }
            // Pass 2 – averages (full opacity, thick)
            for (ExperimentData exp : experiments) {
                if (!exp.hasTamsd()) continue;
                Set<Integer> av = avgComps.getOrDefault(exp, Collections.emptySet());
                for (int comp : av) {
                    g2.setColor(compColor(exp.color, comp));
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND,
                                                 BasicStroke.JOIN_ROUND));
                    drawPolyline(g2, exp.tAxis, exp.avgTamsdComp[comp],
                                 xMin, xMax, yMin, yMax, px, py, pw, ph);
                }
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawPolyline(Graphics2D g2, double[] tAxis, double[] vals,
                                    double xMin, double xMax, double yMin, double yMax,
                                    int px, int py, int pw, int ph) {
            if (tAxis == null || vals == null) return;
            List<Integer> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (int i = 0; i < tAxis.length && i < vals.length; i++) {
                double t = tAxis[i], v = vals[i];
                if (Double.isNaN(v)) continue;
                if (logScale && (t <= 0 || v <= 0)) continue;
                xs.add(mapX(t, xMin, xMax, px, pw));
                ys.add(mapY(v, yMin, yMax, py, ph));
            }
            if (xs.size() < 2) return;
            int[] xa = new int[xs.size()], ya = new int[ys.size()];
            for (int i = 0; i < xs.size(); i++) { xa[i] = xs.get(i); ya[i] = ys.get(i); }
            g2.drawPolyline(xa, ya, xa.length);
        }

        // ------------------------------------------------------------------
        // Legend – top-left, one entry per visible (exp, component) pair
        // ------------------------------------------------------------------

        private void drawLegend(Graphics2D g2, int px, int py, int pw, int ph) {
            List<int[]> entries = new ArrayList<>();  // {expIdx, comp}
            for (int ei = 0; ei < experiments.size(); ei++) {
                ExperimentData exp = experiments.get(ei);
                if (!exp.hasTamsd()) continue;
                Set<Integer> combined = new LinkedHashSet<>(
                        avgComps.getOrDefault(exp, Collections.emptySet()));
                combined.addAll(allComps.getOrDefault(exp, Collections.emptySet()));
                for (int c : combined) entries.add(new int[]{ei, c});
            }
            if (entries.isEmpty()) return;

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int lineH = 18, pad = 6;
            int maxW = 0;
            for (int[] e : entries) {
                String s = experiments.get(e[0]).name + " (" + COMP_LABELS[e[1]] + ")";
                maxW = Math.max(maxW, fm.stringWidth(s));
            }
            int boxW = maxW + 30 + pad * 2;
            int boxH = entries.size() * lineH + pad * 2;
            int lx = px + 6, ly = py + 6;   // TOP-LEFT

            g2.setColor(new Color(255, 255, 255, 210));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);

            for (int i = 0; i < entries.size(); i++) {
                int ei = entries.get(i)[0], comp = entries.get(i)[1];
                ExperimentData exp = experiments.get(ei);
                Color col = compColor(exp.color, comp);
                int rowY = ly + pad + (i + 1) * lineH - 3;
                g2.setColor(col);
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(lx + pad, rowY - 4, lx + pad + 18, rowY - 4);
                g2.setStroke(new BasicStroke(1f));
                g2.setColor(Color.BLACK);
                g2.drawString(exp.name + " (" + COMP_LABELS[comp] + ")",
                              lx + pad + 22, rowY);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // ------------------------------------------------------------------
        // Axis titles
        // ------------------------------------------------------------------

        private void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();

            String xTitle = "Time lag (" + timeUnit + ")";
            g2.drawString(xTitle,
                px + (pw - fm.stringWidth(xTitle)) / 2,
                py + ph + MB - 8);

            String yTitle = "tMSD (μm²)";
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph / 2.0);
            g2.rotate(-Math.PI / 2);
            g2.drawString(yTitle, -fm.stringWidth(yTitle) / 2, fm.getAscent() / 2);
            g2.setTransform(orig);
        }

        // ------------------------------------------------------------------
        // No-data message
        // ------------------------------------------------------------------

        private void drawNoDataMessage(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(new Color(160, 160, 160));
            g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
            String msg = "No visible data – enable at least one component above";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg,
                px + (pw - fm.stringWidth(msg)) / 2,
                py + ph / 2 + fm.getAscent() / 2);
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        private String fmtLog(double val) {
            if (val <= 0) return "0";
            int e = (int) Math.floor(Math.log10(val));
            if (e >= -2 && e <= 4) {
                if (val >= 1) return String.valueOf(Math.round(val));
                return String.format("%.3g", val)
                             .replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return String.format("1e%d", e);
        }

        private String fmtLinear(double val) {
            if (val == 0) return "0";
            double abs = Math.abs(val);
            if (abs >= 0.001 && abs < 10000)
                return String.format("%.4g", val)
                             .replaceAll("0+$", "").replaceAll("\\.$", "");
            return String.format("%.2e", val)
                         .replace("e+0", "e").replace("e-0", "e-").replace("e+", "e");
        }

        private double niceStep(double range, int target) {
            if (range <= 0) return 1;
            double rough = range / target;
            int e = (int) Math.floor(Math.log10(rough));
            double base = Math.pow(10, e);
            for (double m : new double[]{1, 2, 2.5, 5, 10})
                if (m * base >= rough) return m * base;
            return base * 10;
        }
    }
}
