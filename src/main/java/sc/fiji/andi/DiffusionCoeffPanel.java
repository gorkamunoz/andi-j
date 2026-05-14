package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * "Diffusion coefficient" tab.
 *
 * D is estimated via linear fitting of the TAMSD curve
 * (mirrors andi_datasets.msd_analysis.get_diff_coeff):
 *   D = slope(TAMSD vs t_lag) / (2 × n_dim × frame_interval)
 *
 * The 2D / X / Y toggle buttons are independent – any combination can
 * be displayed simultaneously.
 */
public class DiffusionCoeffPanel extends JPanel {

    private final List<ExperimentData> experiments;

    // Per-experiment selected components: Set may contain {0=2D, 1=X, 2=Y}
    private final Map<ExperimentData, Set<Integer>> componentSelection = new LinkedHashMap<>();

    // Parameter fields
    private final JTextField tLagsField, binsField, intervalField, unitField;

    // Sub-panels
    private final HistogramPanel histPanel;
    private final DefaultTableModel tableModel;

    // -----------------------------------------------------------------------

    DiffusionCoeffPanel(List<ExperimentData> experiments, double initInterval) {
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
        tLagsField    = new JTextField("[1,2]",                              8);
        binsField     = new JTextField("20",                                 4);
        intervalField = new JTextField(String.format("%.3f", initInterval), 6);
        unitField     = new JTextField("frames",                             6);
        JButton computeBtn = new JButton("Compute D");

        JPanel paramBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramBar.add(new JLabel("Time lags:"));      paramBar.add(tLagsField);
        paramBar.add(new JLabel("Bins:"));           paramBar.add(binsField);
        paramBar.add(new JLabel("Frame interval:")); paramBar.add(intervalField);
        paramBar.add(new JLabel("Unit:"));           paramBar.add(unitField);
        paramBar.add(computeBtn);
        add(paramBar, BorderLayout.NORTH);

        // ── histogram panel ──────────────────────────────────────────────
        histPanel = new HistogramPanel(experiments, componentSelection);

        // ── averages table ───────────────────────────────────────────────
        String[] cols = {"Experiment", "2D", "X", "Y"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(tableModel);
        table.setFont(table.getFont().deriveFont(11f));
        table.getTableHeader().setFont(
            table.getTableHeader().getFont().deriveFont(Font.BOLD, 11f));
        table.setRowHeight(20);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(0, 100));
        tableScroll.setBorder(
            BorderFactory.createTitledBorder("Average diffusion coefficients"));

        JPanel rightPanel = new JPanel(new BorderLayout(0, 4));
        rightPanel.add(histPanel,   BorderLayout.CENTER);
        rightPanel.add(tableScroll, BorderLayout.SOUTH);

        // ── experiment selector panel (Left) ─────────────────────────────
        JPanel leftPanel = buildExperimentsPanel();
        JScrollPane leftScroll = new JScrollPane(leftPanel);
        leftScroll.setPreferredSize(new Dimension(170, 0));
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));

        JSplitPane split = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightPanel);
        split.setDividerLocation(180);
        split.setResizeWeight(0.0);
        add(split, BorderLayout.CENTER);

        // ── Compute action ───────────────────────────────────────────────
        computeBtn.addActionListener(e -> recompute());

        // If D was pre-computed before the tab was built, populate immediately
        if (experiments.stream().anyMatch(ExperimentData::hasD)) refreshTable();
    }

    // -----------------------------------------------------------------------
    // Left panel – per-experiment independent 2D / X / Y toggle buttons
    // -----------------------------------------------------------------------

    private JPanel buildExperimentsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));
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

        // Colour swatch + name
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JPanel swatch = new JPanel();
        swatch.setBackground(exp.color);
        swatch.setPreferredSize(new Dimension(12, 12));
        swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        nameRow.add(swatch);
        JLabel lbl = new JLabel(exp.name);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 11f));
        nameRow.add(lbl);
        row.add(nameRow);

        // Independent toggle buttons – no ButtonGroup
        JToggleButton btn2D = new JToggleButton("2D", true);
        JToggleButton btnX  = new JToggleButton("X",  false);
        JToggleButton btnY  = new JToggleButton("Y",  false);
        styleToggle(btn2D); styleToggle(btnX); styleToggle(btnY);

        Set<Integer> sel = componentSelection.get(exp);
        btn2D.addActionListener(e -> { if (btn2D.isSelected()) sel.add(0); else sel.remove(0); histPanel.repaint(); });
        btnX .addActionListener(e -> { if (btnX .isSelected()) sel.add(1); else sel.remove(1); histPanel.repaint(); });
        btnY .addActionListener(e -> { if (btnY .isSelected()) sel.add(2); else sel.remove(2); histPanel.repaint(); });

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
    // Compute
    // -----------------------------------------------------------------------

    private void recompute() {
        int[]  tLags    = parseTLags(tLagsField.getText());
        int    nBins    = parseIntOr(binsField.getText(), 20);
        double interval = parseDblOr(intervalField.getText(), 1.0);
        String unit     = unitField.getText().trim().isEmpty()
                          ? "frames" : unitField.getText().trim();

        for (ExperimentData exp : experiments)
            exp.computeDiffusionCoefficients(tLags, interval);

        histPanel.setNBins(nBins);
        histPanel.setTimeUnit(unit);
        histPanel.repaint();
        refreshTable();
    }

    private void refreshTable() {
        tableModel.setRowCount(0);
        for (ExperimentData exp : experiments)
            tableModel.addRow(new Object[]{
                exp.name, fmtD(exp.avgD(0)), fmtD(exp.avgD(1)), fmtD(exp.avgD(2))});
    }

    private static String fmtD(double v) {
        if (!Double.isFinite(v)) return "–";
        if (v == 0) return "0";
        return String.format("%.3e", v);
    }

    private static int[] parseTLags(String s) {
        s = s.trim().replaceAll("[\\[\\]\\s]", "");
        List<Integer> lags = new ArrayList<>();
        for (String p : s.split(","))
            try { int v = Integer.parseInt(p); if (v > 0) lags.add(v); }
            catch (NumberFormatException ignored) {}
        return lags.isEmpty() ? new int[]{1, 2}
                              : lags.stream().mapToInt(Integer::intValue).toArray();
    }
    private static int    parseIntOr(String s, int    d) { try { return Integer.parseInt(s.trim());   } catch (Exception e) { return d; } }
    private static double parseDblOr(String s, double d) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return d; } }

    // =======================================================================
    // Histogram canvas
    // =======================================================================

    static class HistogramPanel extends JPanel {

        private final List<ExperimentData>            experiments;
        private final Map<ExperimentData, Set<Integer>> componentSel;
        private int    nBins    = 20;
        private String timeUnit = "frames";

        private static final int ML = 62, MR = 22, MT = 22, MB = 50;

        // Component shade variants: 2D = base, X = lighter, Y = darker
        private static Color compColor(Color base, int comp) {
            if (comp == 1) return new Color(
                Math.min(255, base.getRed()   + 55),
                Math.min(255, base.getGreen() + 55),
                Math.min(255, base.getBlue()  + 55));
            if (comp == 2) return base.darker();
            return base;
        }

        HistogramPanel(List<ExperimentData> exps,
                       Map<ExperimentData, Set<Integer>> sel) {
            this.experiments  = exps;
            this.componentSel = sel;
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(500, 340));
        }

        void setNBins(int n)       { this.nBins    = Math.max(2, n); }
        void setTimeUnit(String u) { this.timeUnit = u; }

        // One entry per (experiment × component) pair that is selected
        private static class HistEntry {
            final ExperimentData exp;
            final int  component;
            final Color fillColor;
            int[] counts;
            double avg;
            HistEntry(ExperimentData exp, int comp) {
                this.exp       = exp;
                this.component = comp;
                this.fillColor = compColor(exp.color, comp);
            }
        }

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

            if (experiments.stream().noneMatch(ExperimentData::hasD)) {
                drawMsg(g2, "Click «Compute D» to display results",
                        px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            // Collect (exp, component) pairs that are both selected and have data
            List<HistEntry> entries = new ArrayList<>();
            for (ExperimentData exp : experiments) {
                if (!exp.hasD()) continue;
                for (int comp : componentSel.getOrDefault(exp, Collections.emptySet()))
                    entries.add(new HistEntry(exp, comp));
            }

            if (entries.isEmpty()) {
                drawMsg(g2, "No components selected", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            // Global D range across all selected (exp, comp) pairs
            double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE;
            for (HistEntry he : entries)
                for (double[] d : he.exp.dCoeffs.values()) {
                    double v = d[he.component];
                    if (Double.isFinite(v)) {
                        xMin = Math.min(xMin, v);
                        xMax = Math.max(xMax, v);
                    }
                }

            if (xMin == Double.MAX_VALUE) {
                drawMsg(g2, "No finite D values", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph); g2.dispose(); return;
            }
            if (xMin == xMax) {
                xMin -= Math.abs(xMin) * 0.1 + 1e-9;
                xMax += Math.abs(xMax) * 0.1 + 1e-9;
            }
            double pad  = (xMax - xMin) * 0.04;
            xMin -= pad; xMax += pad;
            double binW = (xMax - xMin) / nBins;

            // Build histograms
            int maxCount = 1;
            for (HistEntry he : entries) {
                he.counts = new int[nBins];
                double sum = 0; int n = 0;
                for (double[] d : he.exp.dCoeffs.values()) {
                    double v = d[he.component];
                    if (!Double.isFinite(v)) continue;
                    int bin = Math.min(nBins - 1, Math.max(0,
                                (int) ((v - xMin) / binW)));
                    he.counts[bin]++;
                    sum += v; n++;
                }
                he.avg = n > 0 ? sum / n : Double.NaN;
                for (int c : he.counts) maxCount = Math.max(maxCount, c);
            }

            // Grid
            drawLinearGrid(g2, xMin, xMax, 0, maxCount, px, py, pw, ph);

            // Bars + average lines
            g2.setClip(px, py, pw, ph);
            Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1f, new float[]{4f, 4f}, 0f);

            for (HistEntry he : entries) {
                Color fill   = new Color(he.fillColor.getRed(),
                                         he.fillColor.getGreen(),
                                         he.fillColor.getBlue(), 70);
                Color border = he.fillColor.darker();
                for (int b = 0; b < nBins; b++) {
                    if (he.counts[b] == 0) continue;
                    int bx  = linPxX(xMin + b * binW,       xMin, xMax, px, pw);
                    int bx2 = linPxX(xMin + (b+1) * binW,   xMin, xMax, px, pw);
                    int by  = linPxY((double) he.counts[b],  0, maxCount, py, ph);
                    int by0 = linPxY(0,                       0, maxCount, py, ph);
                    g2.setColor(fill);
                    g2.fillRect(bx, by, Math.max(1, bx2 - bx), by0 - by);
                    g2.setColor(border);
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.drawRect(bx, by, Math.max(1, bx2 - bx), by0 - by);
                }
                if (Double.isFinite(he.avg)) {
                    int ax = linPxX(he.avg, xMin, xMax, px, pw);
                    g2.setColor(he.fillColor);
                    g2.setStroke(dashed);
                    g2.drawLine(ax, py, ax, py + ph);
                }
            }
            g2.setClip(null);
            g2.setStroke(new BasicStroke(1f));

            drawFrame(g2, px, py, pw, ph);
            drawXTicks(g2, xMin, xMax, px, py, pw, ph);
            drawYTicks(g2, 0, maxCount, px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            drawLegend(g2, entries, px, py, pw, ph);

            g2.dispose();
        }

        // -- coordinate transforms ---

        private static int linPxX(double v, double xMin, double xMax, int ox, int w) {
            return ox + (int) ((v - xMin) / (xMax - xMin) * w);
        }
        private static int linPxY(double v, double yMin, double yMax, int oy, int h) {
            return oy + h - (int) ((v - yMin) / (yMax - yMin) * h);
        }

        // -- grid ---

        private static void drawLinearGrid(Graphics2D g2,
                                           double xMin, double xMax,
                                           double yMin, double yMax,
                                           int px, int py, int pw, int ph) {
            g2.setColor(new Color(220, 220, 220));
            g2.setStroke(new BasicStroke(0.5f));
            double xStep = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / xStep) * xStep;
                 t <= xMax + xStep * 0.01; t += xStep) {
                int p = linPxX(t, xMin, xMax, px, pw);
                g2.drawLine(p, py, p, py + ph);
            }
            double yStep = niceStep(yMax - yMin, 5);
            for (double t = 0; t <= yMax + yStep * 0.01; t += yStep) {
                int p = linPxY(t, yMin, yMax, py, ph);
                g2.drawLine(px, p, px + pw, p);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // -- tick marks ---

        private static void drawXTicks(Graphics2D g2, double xMin, double xMax,
                                       int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = niceStep(xMax - xMin, 6);
            for (double t = Math.ceil(xMin / step) * step;
                 t <= xMax + step * 0.01; t += step) {
                int p = linPxX(t, xMin, xMax, px, pw);
                g2.drawLine(p, py + ph, p, py + ph + 4);
                String lbl = fmtTick(t);
                g2.drawString(lbl, p - fm.stringWidth(lbl) / 2, py + ph + 15);
            }
        }

        private static void drawYTicks(Graphics2D g2, double yMin, double yMax,
                                       int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double step = Math.max(1, niceStep(yMax - yMin, 5));
            for (double t = 0; t <= yMax + step * 0.01; t += step) {
                int p = linPxY(t, yMin, yMax, py, ph);
                g2.drawLine(px - 4, p, px, p);
                String lbl = String.valueOf((int) Math.round(t));
                g2.drawString(lbl, px - 6 - fm.stringWidth(lbl),
                              p + fm.getAscent() / 2 - 1);
            }
        }

        // -- axis titles ---

        private void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();

            String xTitle = "D  (μm² / " + timeUnit + ")";
            g2.drawString(xTitle,
                px + (pw - fm.stringWidth(xTitle)) / 2,
                py + ph + MB - 8);

            String yTitle = "Count";
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph / 2.0);
            g2.rotate(-Math.PI / 2);
            g2.drawString(yTitle, -fm.stringWidth(yTitle) / 2, fm.getAscent() / 2);
            g2.setTransform(orig);
        }

        // -- legend ---

        private static final String[] COMP_LABELS = {"2D", "X", "Y"};

        private static void drawLegend(Graphics2D g2, List<HistEntry> entries,
                                       int px, int py, int pw, int ph) {
            if (entries.isEmpty()) return;
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int lineH = 18, pad = 6;
            int maxW = 0;
            for (HistEntry he : entries) {
                String s = he.exp.name + " (" + COMP_LABELS[he.component] + ")";
                maxW = Math.max(maxW, fm.stringWidth(s));
            }
            int boxW = maxW + 28 + pad * 2;
            int boxH = entries.size() * lineH + pad * 2;
            int lx = px + pw - boxW - 6, ly = py + 6;

            g2.setColor(new Color(255, 255, 255, 210));
            g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180, 180, 180));
            g2.setStroke(new BasicStroke(0.7f));
            g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);

            for (int i = 0; i < entries.size(); i++) {
                HistEntry he = entries.get(i);
                int ry = ly + pad + (i + 1) * lineH - 3;
                g2.setColor(new Color(he.fillColor.getRed(),
                                      he.fillColor.getGreen(),
                                      he.fillColor.getBlue(), 120));
                g2.fillRect(lx + pad, ry - 10, 14, 10);
                g2.setColor(he.fillColor.darker());
                g2.setStroke(new BasicStroke(0.8f));
                g2.drawRect(lx + pad, ry - 10, 14, 10);
                g2.setColor(Color.BLACK);
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g2.drawString(he.exp.name + " (" + COMP_LABELS[he.component] + ")",
                              lx + pad + 18, ry);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        // -- utilities ---

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

        private static double niceStep(double range, int target) {
            if (range <= 0) return 1;
            double rough = range / target;
            int exp = (int) Math.floor(Math.log10(rough));
            double base = Math.pow(10, exp);
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
                         .replace("e+0","e").replace("e-0","e-").replace("e+","e");
        }
    }
}
