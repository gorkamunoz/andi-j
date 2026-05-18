package sc.fiji.andi;

import ij.IJ;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * "Anomalous exponent" tab.
 *
 * α is estimated via log-log fitting of the TAMSD curve
 * (mirrors andi_datasets.analysis.msd_analysis.get_exponent):
 *   alpha = slope of log(TAMSD) vs log(t_lag)
 *
 * α ≈ 1  → normal diffusion
 * α < 1  → subdiffusion
 * α > 1  → superdiffusion
 *
 * The 2D / X / Y toggle buttons are independent – any combination can
 * be displayed simultaneously.
 *
 * The histogram canvas supports zoom (drag rectangle) and pan (drag in
 * pan mode).  Double-click resets to the full view.
 */
public class AnomalousExponentPanel extends JPanel {

    private final List<ExperimentData> experiments;

    // Per-experiment selected components: Set may contain {0=2D, 1=X, 2=Y}
    private final Map<ExperimentData, Set<Integer>> componentSelection = new LinkedHashMap<>();

    // All 2D/X/Y toggle buttons – used by "Deselect all"
    private final List<JToggleButton> allCompButtons = new ArrayList<>();

    // Parameter fields
    private final JTextField tLagsField, binsField;

    // Sub-panels
    private final AlphaHistogramPanel histPanel;
    private final DefaultTableModel   tableModel;

    // -----------------------------------------------------------------------

    AnomalousExponentPanel(List<ExperimentData> experiments) {
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
        tLagsField = new JTextField("[1, 0.1]", 8);
        binsField  = new JTextField("20",      4);
        JButton computeBtn = new JButton("Compute α");   // α
        JButton saveBtn    = new JButton("Save α…"); // α…

        JButton aHint = DiffusionCoeffPanel.makeHintLabel(
            "<html>Default values [1, 0.1] chosen for best results.<br>" +
            "For further details see the Wiki.</html>");

        JPanel paramBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramBar.add(new JLabel("Time lags:")); paramBar.add(tLagsField);
        paramBar.add(aHint);
        paramBar.add(new JLabel("Bins:"));      paramBar.add(binsField);
        paramBar.add(computeBtn);
        paramBar.add(saveBtn);
        add(paramBar, BorderLayout.NORTH);

        // ── histogram panel ──────────────────────────────────────────────
        histPanel = new AlphaHistogramPanel(experiments, componentSelection);

        // ── zoom / pan toolbar ───────────────────────────────────────────
        JToggleButton zoomBtn    = new JToggleButton("⊕ Zoom", true);
        JToggleButton panBtn     = new JToggleButton("✥ Pan",  false);
        ButtonGroup   viewBG     = new ButtonGroup();
        viewBG.add(zoomBtn); viewBG.add(panBtn);
        JButton resetViewBtn = new JButton("↺ Reset view");
        for (AbstractButton b : new AbstractButton[]{zoomBtn, panBtn, resetViewBtn}) {
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            b.setFocusPainted(false);
        }
        JPanel histToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        histToolbar.add(zoomBtn);
        histToolbar.add(panBtn);
        histToolbar.add(resetViewBtn);
        zoomBtn.addActionListener(e -> histPanel.setPanMode(false));
        panBtn .addActionListener(e -> histPanel.setPanMode(true));
        resetViewBtn.addActionListener(e -> { histPanel.resetViewport(); histPanel.repaint(); });

        JPanel histArea = new JPanel(new BorderLayout(0, 2));
        histArea.add(histToolbar, BorderLayout.NORTH);
        histArea.add(histPanel,   BorderLayout.CENTER);

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
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(
            BorderFactory.createTitledBorder("Average anomalous exponents"));

        // Preferred height = exactly the data rows (no blank space below last row)
        int tableNaturalH = table.getRowHeight() * experiments.size() + 20 + 38;
        tableScroll.setPreferredSize(new Dimension(0, tableNaturalH));

        // Vertical split: histogram (top, resizable) and table (bottom, resizable)
        JSplitPane rightSplit = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, histArea, tableScroll);
        rightSplit.setResizeWeight(1.0);
        rightSplit.setOneTouchExpandable(true);
        rightSplit.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                    && rightSplit.isShowing())
                SwingUtilities.invokeLater(rightSplit::resetToPreferredSizes);
        });

        // ── experiment selector panel (Left) ─────────────────────────────
        JPanel leftPanel = buildExperimentsPanel();
        JScrollPane leftScroll = new JScrollPane(leftPanel);
        leftScroll.setPreferredSize(new Dimension(170, 0));
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));

        JSplitPane split = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightSplit);
        split.setDividerLocation(180);
        split.setResizeWeight(0.0);
        add(split, BorderLayout.CENTER);

        // ── Actions ──────────────────────────────────────────────────────
        computeBtn.addActionListener(e -> recompute());
        saveBtn   .addActionListener(e -> saveAlpha());

        // If alpha was pre-computed before the tab was built, populate immediately
        if (experiments.stream().anyMatch(ExperimentData::hasAlpha)) refreshTable();
    }

    // -----------------------------------------------------------------------
    // Left panel – per-experiment independent 2D / X / Y toggle buttons
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
            histPanel.resetViewport();
            histPanel.repaint();
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
        JPanel swatch = new JPanel();
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
        btn2D.addActionListener(e -> { if (btn2D.isSelected()) sel.add(0); else sel.remove(0); histPanel.resetViewport(); histPanel.repaint(); });
        btnX .addActionListener(e -> { if (btnX .isSelected()) sel.add(1); else sel.remove(1); histPanel.resetViewport(); histPanel.repaint(); });
        btnY .addActionListener(e -> { if (btnY .isSelected()) sel.add(2); else sel.remove(2); histPanel.resetViewport(); histPanel.repaint(); });

        allCompButtons.add(btn2D);
        allCompButtons.add(btnX);
        allCompButtons.add(btnY);

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
        LagSpec lagSpec = DiffusionCoeffPanel.parseTLags(tLagsField.getText());
        int     nBins   = parseIntOr(binsField.getText(), 20);

        for (ExperimentData exp : experiments)
            exp.computeAnomalousExponents(lagSpec);

        histPanel.resetViewport();
        histPanel.setNBins(nBins);
        histPanel.repaint();
        refreshTable();
    }

    private void refreshTable() {
        tableModel.setRowCount(0);
        for (ExperimentData exp : experiments)
            tableModel.addRow(new Object[]{
                exp.name,
                fmtAlpha(exp.avgAlpha(0)),
                fmtAlpha(exp.avgAlpha(1)),
                fmtAlpha(exp.avgAlpha(2))});
    }

    private static String fmtAlpha(double v) {
        if (!Double.isFinite(v)) return "–"; // –
        return String.format("%.3f", v);
    }

    // -----------------------------------------------------------------------
    // Save α
    // -----------------------------------------------------------------------

    private void saveAlpha() {
        if (experiments.stream().noneMatch(ExperimentData::hasAlpha)) {
            JOptionPane.showMessageDialog(this,
                "No α values computed yet. Click «Compute α» first.",
                "Save α", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle("Save anomalous exponents");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");

        boolean multiExp = experiments.stream().filter(ExperimentData::hasAlpha).count() > 1;

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            if (multiExp) pw.println("experiment,traj_idx,alpha_2d,alpha_x,alpha_y");
            else          pw.println("traj_idx,alpha_2d,alpha_x,alpha_y");

            for (ExperimentData exp : experiments) {
                if (!exp.hasAlpha()) continue;
                for (Map.Entry<Integer, double[]> entry : exp.alphaCoeffs.entrySet()) {
                    int      id = entry.getKey();
                    double[] a  = entry.getValue();
                    if (multiExp)
                        pw.printf("%s,%d,%.6f,%.6f,%.6f%n",
                                  exp.name, id, a[0], a[1], a[2]);
                    else
                        pw.printf("%d,%.6f,%.6f,%.6f%n",
                                  id, a[0], a[1], a[2]);
                }
            }
            IJ.log("AnDi: α values saved → " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not save file:\n" + ex.getMessage(),
                "Save α – error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -----------------------------------------------------------------------
    // Parsers
    // -----------------------------------------------------------------------

    private static int parseIntOr(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }

    // =======================================================================
    // Histogram canvas  (adapted from DiffusionCoeffPanel.HistogramPanel)
    // =======================================================================

    static class AlphaHistogramPanel extends JPanel {

        private final List<ExperimentData>               experiments;
        private final Map<ExperimentData, Set<Integer>>  componentSel;
        private int nBins = 20;

        // ── Viewport state ──────────────────────────────────────────────
        private double[] viewport = null;  // null=auto; {xMin,xMax,0,yMax}

        // Saved after each paint for mouse-event inverse transforms
        private double renderXMin = 0, renderXMax = 1, renderYMax = 10;
        private int    renderPx = 62, renderPy = 22, renderPw = 100, renderPh = 100;

        // ── Interaction ──────────────────────────────────────────────────
        private boolean panMode = false;

        // Zoom drag (rubber-band)
        private boolean dragging = false;
        private int dragX0, dragY0, dragX1, dragY1;

        // Pan drag
        private boolean panning  = false;
        private int     panX0, panY0;
        private double  panInitXMin, panInitXMax, panInitYMax;

        // Hover position (-1 = outside plot)
        private int hoverPx = -1, hoverPy = -1;

        private static final int ML = 62, MR = 22, MT = 22, MB = 50;

        private static Color compColor(Color base, int comp) {
            if (comp == 1) return new Color(
                Math.min(255, base.getRed()   + 55),
                Math.min(255, base.getGreen() + 55),
                Math.min(255, base.getBlue()  + 55));
            if (comp == 2) return base.darker();
            return base;
        }

        AlphaHistogramPanel(List<ExperimentData> exps,
                            Map<ExperimentData, Set<Integer>> sel) {
            this.experiments  = exps;
            this.componentSel = sel;
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(500, 300));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseInteraction();
        }

        // ── Public controls ─────────────────────────────────────────────

        void setNBins(int n)  { this.nBins = Math.max(2, n); }
        void resetViewport()  { this.viewport = null; }

        void setPanMode(boolean pan) {
            this.panMode = pan;
            setCursor(pan
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        // ── Mouse interaction ────────────────────────────────────────────

        private void addMouseInteraction() {
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!panMode) {
                        dragging = true;
                        dragX0 = dragX1 = clamp(e.getX(), renderPx, renderPx + renderPw);
                        dragY0 = dragY1 = clamp(e.getY(), renderPy, renderPy + renderPh);
                    } else {
                        panning      = true;
                        panX0        = e.getX();
                        panY0        = e.getY();
                        panInitXMin  = renderXMin;
                        panInitXMax  = renderXMax;
                        panInitYMax  = renderYMax;
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragging) {
                        dragX1 = clamp(e.getX(), renderPx, renderPx + renderPw);
                        dragY1 = clamp(e.getY(), renderPy, renderPy + renderPh);
                        repaint();
                    } else if (panning && renderPw > 0 && renderPh > 0) {
                        double dx = (double)(e.getX() - panX0) / renderPw
                                    * (panInitXMax - panInitXMin);
                        double dy = (double)(e.getY() - panY0) / renderPh * panInitYMax;
                        double nxMin = panInitXMin - dx;
                        double nxMax = panInitXMax - dx;
                        double nyMax = Math.max(1, panInitYMax + dy);
                        if (nxMin < nxMax)
                            viewport = new double[]{nxMin, nxMax, 0, nyMax};
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (dragging) {
                        dragging = false;
                        int x0 = Math.min(dragX0, dragX1), x1 = Math.max(dragX0, dragX1);
                        int y0 = Math.min(dragY0, dragY1), y1 = Math.max(dragY0, dragY1);
                        if (x1 - x0 > 5 && y1 - y0 > 5) {
                            double dxMin = pxToDataX(x0), dxMax = pxToDataX(x1);
                            double dyMax = pxToDataY(y0), dyMin = pxToDataY(y1);
                            if (dxMin < dxMax && dyMax > dyMin)
                                viewport = new double[]{dxMin, dxMax, 0,
                                                        Math.max(1, dyMax)};
                        }
                        repaint();
                    } else if (panning) {
                        panning = false;
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) { viewport = null; repaint(); }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    hoverPx = e.getX(); hoverPy = e.getY(); repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
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
            if (renderPh <= 0) return 0;
            return (double)(renderPy + renderPh - py) / renderPh * renderYMax;
        }
        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        // ── Paint ────────────────────────────────────────────────────────

        private static class HistEntry {
            final ExperimentData exp;
            final int  component;
            final Color fillColor;
            int[]  counts;
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

            if (experiments.stream().noneMatch(ExperimentData::hasAlpha)) {
                drawMsg(g2, "Click «Compute α» to display results",
                        px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            List<HistEntry> entries = new ArrayList<>();
            for (ExperimentData exp : experiments) {
                if (!exp.hasAlpha()) continue;
                for (int comp : componentSel.getOrDefault(exp, Collections.emptySet()))
                    entries.add(new HistEntry(exp, comp));
            }

            if (entries.isEmpty()) {
                drawMsg(g2, "No components selected", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph);
                g2.dispose(); return;
            }

            // ── Global α range (for binning) ─────────────────────────────
            double globalXMin = Double.MAX_VALUE, globalXMax = -Double.MAX_VALUE;
            for (HistEntry he : entries)
                for (double[] a : he.exp.alphaCoeffs.values()) {
                    double v = a[he.component];
                    if (Double.isFinite(v) && v >= 0 && v <= 2) {
                        globalXMin = Math.min(globalXMin, v);
                        globalXMax = Math.max(globalXMax, v);
                    }
                }

            if (globalXMin == Double.MAX_VALUE) {
                drawMsg(g2, "No finite α values", px, py, pw, ph);
                drawFrame(g2, px, py, pw, ph); g2.dispose(); return;
            }
            if (globalXMin == globalXMax) {
                globalXMin -= 0.05;
                globalXMax += 0.05;
            }
            double pad   = (globalXMax - globalXMin) * 0.04;
            globalXMin  -= pad; globalXMax += pad;
            double binW  = (globalXMax - globalXMin) / nBins;

            // ── Build histograms ─────────────────────────────────────────
            int maxCount = 1;
            for (HistEntry he : entries) {
                he.counts = new int[nBins];
                double sum = 0; int n = 0;
                for (double[] a : he.exp.alphaCoeffs.values()) {
                    double v = a[he.component];
                    if (!Double.isFinite(v) || v < 0 || v > 2) continue;
                    int bin = Math.min(nBins - 1, Math.max(0,
                                (int) ((v - globalXMin) / binW)));
                    he.counts[bin]++;
                    sum += v; n++;
                }
                he.avg = n > 0 ? sum / n : Double.NaN;
                for (int c : he.counts) maxCount = Math.max(maxCount, c);
            }

            // ── Display range (viewport or auto) ─────────────────────────
            double dispXMin = (viewport != null) ? viewport[0] : globalXMin;
            double dispXMax = (viewport != null) ? viewport[1] : globalXMax;
            double dispYMax = (viewport != null) ? Math.max(1, viewport[3]) : maxCount;

            renderXMin = dispXMin; renderXMax = dispXMax; renderYMax = dispYMax;
            renderPx = px; renderPy = py; renderPw = pw; renderPh = ph;

            // ── Draw ─────────────────────────────────────────────────────
            drawLinearGrid(g2, dispXMin, dispXMax, 0, dispYMax, px, py, pw, ph);

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
                    int bx  = linPxX(globalXMin + b * binW,       dispXMin, dispXMax, px, pw);
                    int bx2 = linPxX(globalXMin + (b + 1) * binW, dispXMin, dispXMax, px, pw);
                    int by  = linPxY((double) he.counts[b],        0, dispYMax, py, ph);
                    int by0 = linPxY(0,                             0, dispYMax, py, ph);
                    g2.setColor(fill);
                    g2.fillRect(bx, by, Math.max(1, bx2 - bx), by0 - by);
                    g2.setColor(border);
                    g2.setStroke(new BasicStroke(0.8f));
                    g2.drawRect(bx, by, Math.max(1, bx2 - bx), by0 - by);
                }
                if (Double.isFinite(he.avg)) {
                    int ax = linPxX(he.avg, dispXMin, dispXMax, px, pw);
                    g2.setColor(he.fillColor);
                    g2.setStroke(dashed);
                    g2.drawLine(ax, py, ax, py + ph);
                }
            }
            g2.setClip(null);
            g2.setStroke(new BasicStroke(1f));

            drawFrame(g2, px, py, pw, ph);
            drawXTicks(g2, dispXMin, dispXMax, px, py, pw, ph);
            drawYTicks(g2, 0, dispYMax, px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            drawLegend(g2, entries, px, py, pw, ph);

            // ── Rubber-band rectangle (zoom mode only) ───────────────────
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
                drawHoverTooltip(g2, String.format("α = %.3f", dataX),
                                 hoverPx, hoverPy, W, H);
            }

            g2.dispose();
        }

        // -- coordinate transforms ---

        private static int linPxX(double v, double xMin, double xMax, int ox, int w) {
            if (xMax == xMin) return ox;
            return ox + (int) ((v - xMin) / (xMax - xMin) * w);
        }
        private static int linPxY(double v, double yMin, double yMax, int oy, int h) {
            if (yMax == yMin) return oy + h;
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
            double yStep = Math.max(1, niceStep(yMax - yMin, 5));
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

        private static void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();

            // α (anomalous exponent)
            String xTitle = "α (anomalous exponent)";
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
