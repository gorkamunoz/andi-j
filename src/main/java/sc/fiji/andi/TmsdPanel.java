package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * The "TMSD Visualization" tab.
 *
 * Layout
 * ──────
 *   North  : lag parameter controls + Compute button
 *   Center : JSplitPane
 *              Left  – per-experiment toggle buttons (Avg / All)
 *              Right – custom log-log plot canvas
 */
public class TmsdPanel extends JPanel {

    private final List<ExperimentData> experiments;
    private final TmsdPlotPanel plotPanel;

    // Parameter fields
    private final JTextField minLagField, maxLagField, nLagsField, intervalField, unitField;
    private final JCheckBox  logSpacedBox;

    TmsdPanel(List<ExperimentData> experiments, int initMaxLag, double initInterval) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        this.plotPanel   = new TmsdPlotPanel(experiments);

        setBorder(new EmptyBorder(6, 6, 6, 6));

        // ── parameter bar (North) ────────────────────────────────────────
        minLagField   = new JTextField(String.valueOf(1),       4);
        maxLagField   = new JTextField(String.valueOf(initMaxLag), 4);
        nLagsField    = new JTextField("15",                    4);
        logSpacedBox  = new JCheckBox("Log-spaced", true);
        intervalField = new JTextField(String.format("%.3f", initInterval), 6);
        unitField     = new JTextField("frames",                6);
        JButton computeBtn = new JButton("Compute tMSD");

        JPanel paramBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramBar.add(new JLabel("Min lag:")); paramBar.add(minLagField);
        paramBar.add(new JLabel("Max lag:")); paramBar.add(maxLagField);
        paramBar.add(new JLabel("N lags:"));  paramBar.add(nLagsField);
        paramBar.add(logSpacedBox);
        paramBar.add(Box.createHorizontalStrut(10));
        paramBar.add(new JLabel("Frame interval:")); paramBar.add(intervalField);
        paramBar.add(new JLabel("Unit:"));           paramBar.add(unitField);
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

    /** Rebuilds the experiment controls panel. */
    private JPanel buildExperimentsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        for (ExperimentData exp : experiments) {
            panel.add(makeExpRow(exp));
            panel.add(Box.createVerticalStrut(4));
        }
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    /** One row per experiment: colour swatch + name + [Avg] [All] buttons. */
    private JPanel makeExpRow(ExperimentData exp) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        // Colour swatch
        JPanel swatch = new JPanel();
        swatch.setBackground(exp.color);
        swatch.setPreferredSize(new Dimension(12, 12));
        swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        row.add(swatch);

        // Name label
        JLabel nameLbl = new JLabel(exp.name);
        nameLbl.setFont(nameLbl.getFont().deriveFont(Font.PLAIN, 11f));
        row.add(nameLbl);

        // Toggle buttons
        JToggleButton avgBtn = new JToggleButton("Avg", exp.showAverage);
        JToggleButton allBtn = new JToggleButton("All", exp.showAll);
        styleToggle(avgBtn);
        styleToggle(allBtn);

        avgBtn.addActionListener(e -> {
            exp.showAverage = avgBtn.isSelected();
            plotPanel.repaint();
        });
        allBtn.addActionListener(e -> {
            exp.showAll = allBtn.isSelected();
            plotPanel.repaint();
        });

        row.add(avgBtn);
        row.add(allBtn);
        return row;
    }

    private static void styleToggle(JToggleButton btn) {
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 10f));
        btn.setPreferredSize(new Dimension(38, 20));
        btn.setMargin(new Insets(1, 2, 1, 2));
        btn.setFocusPainted(false);
    }

    private void recompute() {
        int    minL     = parseIntOr(minLagField.getText(),    1);
        int    maxL     = parseIntOr(maxLagField.getText(),   20);
        int    n        = parseIntOr(nLagsField.getText(),    15);
        boolean logS    = logSpacedBox.isSelected();
        double interval = parseDblOr(intervalField.getText(), 1.0);
        String unit     = unitField.getText().trim();
        if (unit.isEmpty()) unit = "frames";

        for (ExperimentData exp : experiments)
            exp.computeTamsd(minL, maxL, n, logS, interval);

        plotPanel.setTimeUnit(unit);
        plotPanel.repaint();
    }

    private static int    parseIntOr(String s, int    def) { try { return Integer.parseInt(s.trim());   } catch (Exception e) { return def; } }
    private static double parseDblOr(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }

    // ======================================================================
    // Custom log-log plot canvas
    // ======================================================================

    static class TmsdPlotPanel extends JPanel {

        private final List<ExperimentData> experiments;
        private String timeUnit = "frames";

        // Plot margins (pixels)
        private static final int ML = 68, MR = 22, MT = 22, MB = 52;

        TmsdPlotPanel(List<ExperimentData> exps) {
            this.experiments = exps;
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(560, 420));
        }

        void setTimeUnit(String u) { this.timeUnit = u; }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 20 || ph < 20) { g2.dispose(); return; }

            // White plot area
            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            double[] bounds = computeBounds();

            if (bounds == null) {
                drawNoDataMessage(g2, px, py, pw, ph);
            } else {
                double xMin = bounds[0], xMax = bounds[1];
                double yMin = bounds[2], yMax = bounds[3];
                double lxMin = Math.log10(xMin), lxMax = Math.log10(xMax);
                double lyMin = Math.log10(yMin), lyMax = Math.log10(yMax);

                drawGrid(g2, xMin, xMax, true,  px, py, pw, ph, lxMin, lxMax, lyMin, lyMax);
                drawGrid(g2, yMin, yMax, false, px, py, pw, ph, lxMin, lxMax, lyMin, lyMax);

                // Clip curves to plot area
                g2.setClip(px, py, pw, ph);
                drawCurves(g2, px, py, pw, ph, lxMin, lxMax, lyMin, lyMax);
                g2.setClip(null);

                drawTicks(g2, xMin, xMax, true,  px, py, pw, ph, lxMin, lxMax, lyMin, lyMax);
                drawTicks(g2, yMin, yMax, false, px, py, pw, ph, lxMin, lxMax, lyMin, lyMax);
                drawLegend(g2, px, py, pw, ph);
            }

            // Axes border (drawn on top of everything)
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);

            drawAxisTitles(g2, px, py, pw, ph);
            g2.dispose();
        }

        // ------------------------------------------------------------------
        // Bounds
        // ------------------------------------------------------------------

        private double[] computeBounds() {
            double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE;
            double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;

            for (ExperimentData exp : experiments) {
                if (!exp.hasTamsd() || (!exp.showAll && !exp.showAverage)) continue;
                for (double t : exp.tAxis)
                    if (t > 0) { xMin = Math.min(xMin, t); xMax = Math.max(xMax, t); }
                if (exp.showAll)
                    for (double[] v : exp.tamsdPerTrack.values())
                        for (double d : v) if (!Double.isNaN(d) && d > 0) { yMin = Math.min(yMin, d); yMax = Math.max(yMax, d); }
                if (exp.showAverage)
                    for (double d : exp.avgTamsd) if (!Double.isNaN(d) && d > 0) { yMin = Math.min(yMin, d); yMax = Math.max(yMax, d); }
            }

            if (xMin == Double.MAX_VALUE || yMin == Double.MAX_VALUE) return null;
            if (xMin == xMax) xMax = xMin * 10;
            if (yMin == yMax) yMax = yMin * 10;

            // 5% padding in log space
            double dX = (Math.log10(xMax) - Math.log10(xMin)) * 0.05;
            double dY = (Math.log10(yMax) - Math.log10(yMin)) * 0.05;
            if (dX == 0) dX = 0.2; if (dY == 0) dY = 0.2;
            return new double[]{
                Math.pow(10, Math.log10(xMin) - dX), Math.pow(10, Math.log10(xMax) + dX),
                Math.pow(10, Math.log10(yMin) - dY), Math.pow(10, Math.log10(yMax) + dY)
            };
        }

        // ------------------------------------------------------------------
        // Coordinate transforms
        // ------------------------------------------------------------------

        private static int toPxX(double v, double lMin, double lMax, int ox, int w) {
            return ox + (int) ((Math.log10(v) - lMin) / (lMax - lMin) * w);
        }
        private static int toPxY(double v, double lMin, double lMax, int oy, int h) {
            return oy + h - (int) ((Math.log10(v) - lMin) / (lMax - lMin) * h);
        }

        // ------------------------------------------------------------------
        // Grid lines  (at 1×, 2×, 5× per decade)
        // ------------------------------------------------------------------

        private static void drawGrid(Graphics2D g2,
                                     double vMin, double vMax, boolean isX,
                                     int px, int py, int pw, int ph,
                                     double lxMin, double lxMax,
                                     double lyMin, double lyMax) {
            int e0 = (int) Math.floor(Math.log10(vMin));
            int e1 = (int) Math.ceil (Math.log10(vMax));
            for (int e = e0; e <= e1; e++) {
                for (int m : new int[]{1, 2, 5}) {
                    double val = m * Math.pow(10, e);
                    if (val < vMin || val > vMax) continue;
                    boolean major = (m == 1);
                    g2.setColor(major ? new Color(200, 200, 200) : new Color(230, 230, 230));
                    g2.setStroke(new BasicStroke(major ? 0.6f : 0.4f));
                    if (isX) {
                        int p = toPxX(val, lxMin, lxMax, px, pw);
                        g2.drawLine(p, py, p, py + ph);
                    } else {
                        int p = toPxY(val, lyMin, lyMax, py, ph);
                        g2.drawLine(px, p, px + pw, p);
                    }
                }
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // ------------------------------------------------------------------
        // Tick marks + labels  (major at 10^n, minor at 2,5 × 10^n)
        // ------------------------------------------------------------------

        private static void drawTicks(Graphics2D g2,
                                      double vMin, double vMax, boolean isX,
                                      int px, int py, int pw, int ph,
                                      double lxMin, double lxMax,
                                      double lyMin, double lyMax) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int e0 = (int) Math.floor(Math.log10(vMin));
            int e1 = (int) Math.ceil (Math.log10(vMax));
            for (int e = e0; e <= e1; e++) {
                for (int m = 1; m <= 9; m++) {
                    double val = m * Math.pow(10, e);
                    if (val < vMin || val > vMax) continue;
                    boolean major = (m == 1);
                    int tickLen = major ? 5 : 3;
                    if (isX) {
                        int p = toPxX(val, lxMin, lxMax, px, pw);
                        g2.drawLine(p, py + ph, p, py + ph + tickLen);
                        if (major) {
                            String lbl = fmt(val);
                            g2.drawString(lbl, p - fm.stringWidth(lbl) / 2, py + ph + 17);
                        }
                    } else {
                        int p = toPxY(val, lyMin, lyMax, py, ph);
                        g2.drawLine(px - tickLen, p, px, p);
                        if (major) {
                            String lbl = fmt(val);
                            g2.drawString(lbl, px - 6 - fm.stringWidth(lbl), p + fm.getAscent() / 2 - 1);
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Curves
        // ------------------------------------------------------------------

        private void drawCurves(Graphics2D g2, int px, int py, int pw, int ph,
                                double lxMin, double lxMax, double lyMin, double lyMax) {
            for (ExperimentData exp : experiments) {
                if (!exp.hasTamsd()) continue;

                if (exp.showAll) {
                    Composite save = g2.getComposite();
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
                    g2.setColor(exp.color);
                    g2.setStroke(new BasicStroke(1f));
                    for (double[] vals : exp.tamsdPerTrack.values())
                        drawPolyline(g2, exp.tAxis, vals, px, py, pw, ph, lxMin, lxMax, lyMin, lyMax);
                    g2.setComposite(save);
                }

                if (exp.showAverage) {
                    g2.setColor(exp.color);
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    drawPolyline(g2, exp.tAxis, exp.avgTamsd, px, py, pw, ph, lxMin, lxMax, lyMin, lyMax);
                }
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private static void drawPolyline(Graphics2D g2, double[] tAxis, double[] vals,
                                         int px, int py, int pw, int ph,
                                         double lxMin, double lxMax,
                                         double lyMin, double lyMax) {
            if (tAxis == null || vals == null) return;
            List<Integer> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (int i = 0; i < tAxis.length && i < vals.length; i++) {
                if (!Double.isNaN(vals[i]) && vals[i] > 0 && tAxis[i] > 0) {
                    xs.add(toPxX(tAxis[i], lxMin, lxMax, px, pw));
                    ys.add(toPxY(vals[i],  lyMin, lyMax, py, ph));
                }
            }
            if (xs.size() < 2) return;
            int[] xa = new int[xs.size()], ya = new int[ys.size()];
            for (int i = 0; i < xs.size(); i++) { xa[i] = xs.get(i); ya[i] = ys.get(i); }
            g2.drawPolyline(xa, ya, xa.length);
        }

        // ------------------------------------------------------------------
        // Legend  (top-right, inside plot area)
        // ------------------------------------------------------------------

        private void drawLegend(Graphics2D g2, int px, int py, int pw, int ph) {
            List<ExperimentData> vis = new ArrayList<>();
            for (ExperimentData exp : experiments)
                if (exp.hasTamsd() && (exp.showAll || exp.showAverage)) vis.add(exp);
            if (vis.isEmpty()) return;

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int lineH = 18, pad = 6;
            int maxW = 0;
            for (ExperimentData exp : vis) maxW = Math.max(maxW, fm.stringWidth(exp.name));
            int boxW = maxW + 30 + pad * 2;
            int boxH = vis.size() * lineH + pad * 2;
            int lx = px + pw - boxW - 6, ly = py + 6;

            g2.setColor(new Color(255, 255, 255, 210));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setStroke(new BasicStroke(1f));

            for (int i = 0; i < vis.size(); i++) {
                ExperimentData exp = vis.get(i);
                int rowY = ly + pad + (i + 1) * lineH - 3;
                // Colour line swatch
                g2.setColor(exp.color);
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(lx + pad, rowY - 4, lx + pad + 18, rowY - 4);
                g2.setStroke(new BasicStroke(1f));
                // Name
                g2.setColor(Color.BLACK);
                g2.drawString(exp.name, lx + pad + 22, rowY);
            }
        }

        // ------------------------------------------------------------------
        // Axis titles
        // ------------------------------------------------------------------

        private void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();

            // X title (centred below x-axis)
            String xTitle = "Time lag (" + timeUnit + ")";
            g2.drawString(xTitle,
                px + (pw - fm.stringWidth(xTitle)) / 2,
                py + ph + MB - 8);

            // Y title (rotated, centred on y-axis)
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

        private static void drawNoDataMessage(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(new Color(160, 160, 160));
            g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
            String msg = "No visible data – toggle Avg or All for at least one experiment";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg,
                px + (pw - fm.stringWidth(msg)) / 2,
                py + ph / 2 + fm.getAscent() / 2);
        }

        // ------------------------------------------------------------------
        // Tick label formatter
        // ------------------------------------------------------------------

        private static String fmt(double val) {
            if (val <= 0) return "0";
            int e = (int) Math.floor(Math.log10(val));
            if (e >= -2 && e <= 4) {
                if (val >= 1) return String.valueOf(Math.round(val));
                return String.format("%.3g", val)
                             .replaceAll("0+$", "")
                             .replaceAll("\\.$", "");
            }
            return String.format("1e%d", e);
        }
    }
}
