package sc.fiji.andi;

import ij.IJ;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * "Diffusive state" tab for Trajectory Segmentation.
 *
 * <p>Two stacked plots (draggable divider):</p>
 * <ol>
 *   <li><b>Top</b> — per experiment, the % of <em>segments</em> in each AnDi
 *       diffusive state (each segment's majority per-frame state).</li>
 *   <li><b>Bottom</b> — one segment-length histogram per diffusive state that
 *       has any segments (states with no segments are omitted).</li>
 * </ol>
 * The classification comes from the segmentation model (its per-frame state
 * head, {@code kac[:, 2]}), computed during Perform Analysis — so there are no
 * ML controls here; the model is chosen in the Data Manager.
 */
public class SegPhenomPanel extends JPanel {

    /** AnDi diffusive-state classes, in the model's output-class order (0…3). */
    public static final String[] PHENOM_NAMES = {
        "Immobile", "Confined", "Free diffusion", "Directed"
    };

    /** Per-state line colours (Okabe–Ito, colour-blind friendly). */
    static final Color[] STATE_COLORS = {
        new Color(120, 120, 120),   // Immobile
        new Color(230, 159,   0),   // Confined
        new Color(  0, 114, 178),   // Free diffusion
        new Color(213,  94,   0),   // Directed
    };

    private final List<ExperimentData>         experiments;
    private final Map<ExperimentData, Boolean> expVisible = new LinkedHashMap<>();
    /** Which diffusive-state lines to draw (default: the states present in the data). */
    private final boolean[] modeVisible = new boolean[PHENOM_NAMES.length];

    private final BarPanel            barPanel;
    private final SegStatsPanel.Hist1D lengthHist;   // one line per state
    private final JCheckBox           normalizeBox = new JCheckBox("Normalize (%)");

    public SegPhenomPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        setBorder(new EmptyBorder(6, 6, 6, 6));
        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        // Default: show only states that occur in the data.
        for (ExperimentData exp : experiments)
            if (exp.hasSegments())
                for (List<Segment> segs : exp.segments.values())
                    for (Segment s : segs)
                        if (s.state >= 0 && s.state < modeVisible.length) modeVisible[s.state] = true;

        barPanel   = new BarPanel();
        lengthHist = new SegStatsPanel.Hist1D();
        lengthHist.setTitle("Segment-length distribution by state");

        JLabel info = new JLabel("<html><i>Top: % of segments per diffusive state. "
            + "Bottom: segment-length distribution, one line per state.</i></html>");
        info.setBorder(new EmptyBorder(2, 4, 4, 4));
        info.setForeground(new Color(80, 80, 80));
        normalizeBox.setToolTipText("Show each state's length distribution as % of that state's segments");
        normalizeBox.addActionListener(e -> {
            lengthHist.setNormalize(normalizeBox.isSelected());
            lengthHist.repaint();
        });
        add(info, BorderLayout.NORTH);

        JSplitPane plots = new JSplitPane(JSplitPane.VERTICAL_SPLIT, barPanel, lengthHist);
        plots.setResizeWeight(0.5);
        plots.setContinuousLayout(true);
        plots.setDividerLocation(260);

        // Controls column mirrors the plots' vertical split: experiments align with
        // the top (bar chart), mode toggles with the bottom (length plot).
        JSplitPane controls = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildExperimentsPanel(), buildModesPanel());
        controls.setResizeWeight(0.5);
        controls.setContinuousLayout(true);
        controls.setDividerLocation(260);
        controls.setDividerSize(0);   // driven by the plots divider, not dragged directly
        syncDividers(plots, controls);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, plots, controls);
        main.setResizeWeight(1.0);
        main.setContinuousLayout(true);
        add(main, BorderLayout.CENTER);

        rebuild();
    }

    /** Keeps the controls column's divider aligned with the plots' divider. */
    private static void syncDividers(JSplitPane plots, JSplitPane controls) {
        final boolean[] guard = {false};
        plots.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (guard[0]) return;
            guard[0] = true;
            controls.setDividerLocation(plots.getDividerLocation());
            guard[0] = false;
        });
    }

    // -----------------------------------------------------------------------

    private JComponent buildExperimentsPanel() {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<JToggleButton> btns = new ArrayList<>();
        for (ExperimentData exp : experiments) {
            JToggleButton b = new JToggleButton(exp.name, expVisible.getOrDefault(exp, true));
            b.setForeground(exp.color);
            b.setFont(b.getFont().deriveFont(Font.BOLD));
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            b.setFocusPainted(false);
            b.addActionListener(e -> { expVisible.put(exp, b.isSelected()); rebuild(); });
            col.add(b);
            col.add(Box.createVerticalStrut(3));
            btns.add(b);
        }
        JButton deselect = new JButton("Deselect all");
        deselect.setFont(deselect.getFont().deriveFont(Font.PLAIN, 10f));
        deselect.setAlignmentX(Component.LEFT_ALIGNMENT);
        deselect.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        deselect.setFocusPainted(false);
        deselect.addActionListener(e -> {
            for (ExperimentData exp : experiments) expVisible.put(exp, false);
            btns.forEach(x -> x.setSelected(false));
            rebuild();
        });

        JButton saveBtn = new JButton("Save…");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.PLAIN, 10f));
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        saveBtn.setToolTipText("Export the per-segment diffusive state to CSV");
        saveBtn.addActionListener(e -> save());

        col.add(Box.createVerticalStrut(6));
        col.add(deselect);
        col.add(Box.createVerticalStrut(3));
        col.add(saveBtn);
        col.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(col);
        sp.setBorder(BorderFactory.createTitledBorder("Experiments"));
        sp.setMinimumSize(new Dimension(90, 0));
        sp.setPreferredSize(new Dimension(170, 0));
        return sp;
    }

    /** Right-column bottom: one toggle per diffusive state for the length plot. */
    private JComponent buildModesPanel() {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(new EmptyBorder(4, 4, 4, 4));
        for (int st = 0; st < PHENOM_NAMES.length; st++) {
            final int s = st;
            JToggleButton b = new JToggleButton(PHENOM_NAMES[st], modeVisible[st]);
            b.setForeground(STATE_COLORS[st]);
            b.setFont(b.getFont().deriveFont(Font.BOLD));
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            b.setFocusPainted(false);
            b.addActionListener(e -> { modeVisible[s] = b.isSelected(); rebuild(); });
            col.add(b);
            col.add(Box.createVerticalStrut(3));
        }
        col.add(Box.createVerticalGlue());
        normalizeBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(normalizeBox);

        JScrollPane sp = new JScrollPane(col);
        sp.setBorder(BorderFactory.createTitledBorder("Diffusive states"));
        sp.setMinimumSize(new Dimension(90, 0));
        sp.setPreferredSize(new Dimension(170, 0));
        return sp;
    }

    /** Rebuilds the per-state length distribution (one line per state) + bar chart. */
    private void rebuild() {
        List<ExperimentData> sel = new ArrayList<>();
        for (ExperimentData exp : experiments)
            if (expVisible.getOrDefault(exp, true) && exp.hasSegments()) sel.add(exp);

        // Pool the selected experiments; one histogram series per shown state.
        int maxLen = 1;
        for (ExperimentData exp : sel)
            for (List<Segment> segs : exp.segments.values())
                for (Segment s : segs) maxLen = Math.max(maxLen, s.length);

        int nb = Math.max(1, Math.min(40, maxLen));
        double lenMin = 0.5, lenMax = maxLen + 0.5;

        List<int[]>  counts = new ArrayList<>();
        List<Color>  colors = new ArrayList<>();
        List<String> names  = new ArrayList<>();
        for (int st = 0; st < PHENOM_NAMES.length; st++) {
            if (!modeVisible[st]) continue;                     // omit toggled-off states
            int[] c = new int[nb];                              // all-zeros if the state is empty
            for (ExperimentData exp : sel)
                for (List<Segment> segs : exp.segments.values())
                    for (Segment s : segs)
                        if (s.state == st) c[bin(s.length, lenMin, lenMax, nb)]++;
            counts.add(c);
            colors.add(STATE_COLORS[st]);
            names.add(PHENOM_NAMES[st]);
        }

        lengthHist.setData(lenMin, lenMax, nb, counts, colors, names);
        lengthHist.setNormalize(normalizeBox.isSelected());
        lengthHist.repaint();
        barPanel.repaint();
    }

    private static int bin(double v, double vMin, double vMax, int n) {
        int b = (int) ((v - vMin) / (vMax - vMin) * n);
        return Math.max(0, Math.min(n - 1, b));
    }

    private void save() {
        if (experiments.stream().noneMatch(ExperimentData::hasSegments)) {
            JOptionPane.showMessageDialog(this, "No segments available.",
                "Save", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle("Save per-segment diffusive state");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            pw.println("experiment,traj_idx,segment,start_frame,length,state");
            for (ExperimentData exp : experiments) {
                if (!exp.hasSegments()) continue;
                for (Map.Entry<Integer, List<Segment>> e : exp.segments.entrySet()) {
                    List<Segment> segs = e.getValue();
                    for (int k = 0; k < segs.size(); k++) {
                        Segment s = segs.get(k);
                        String state = (s.state >= 0 && s.state < PHENOM_NAMES.length)
                                     ? String.valueOf(s.state) : "";
                        pw.printf("%s,%d,%d,%d,%d,%s%n",
                            exp.name, e.getKey(), k + 1, s.start + 1, s.length, state);
                    }
                }
            }
            IJ.log("AnDi-J: per-segment diffusive state saved → " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save file:\n" + ex.getMessage(),
                "Save – error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Top: % of segments per state
    // ═════════════════════════════════════════════════════════════════════

    private class BarPanel extends JPanel {
        private static final int ML = 56, MR = 22, MT = 46, MB = 42;
        private final MovableLegend legend = new MovableLegend();

        BarPanel() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(560, 250));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { legend.press(e.getX(), e.getY()); }
                @Override public void mouseDragged(MouseEvent e) { if (legend.dragTo(e.getX(), e.getY())) repaint(); }
                @Override public void mouseReleased(MouseEvent e) { legend.release(); }
                @Override public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(
                        legend.over(e.getX(), e.getY()) ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 40 || ph < 40) { g2.dispose(); return; }

            List<ExperimentData> visible = new ArrayList<>();
            for (ExperimentData exp : experiments)
                if (expVisible.getOrDefault(exp, true) && exp.hasSegments()) visible.add(exp);

            int nModels = PHENOM_NAMES.length;
            double[][] pct = new double[visible.size()][nModels];
            for (int ei = 0; ei < visible.size(); ei++) {
                int[] counts = new int[nModels];
                int total = 0;
                for (List<Segment> segs : visible.get(ei).segments.values())
                    for (Segment s : segs)
                        if (s.state >= 0 && s.state < nModels) { counts[s.state]++; total++; }
                if (total > 0)
                    for (int m = 0; m < nModels; m++) pct[ei][m] = 100.0 * counts[m] / total;
            }

            double maxPct = 0;
            for (double[] row : pct) for (double v : row) maxPct = Math.max(maxPct, v);
            double yMax = niceCeil(Math.max(maxPct, 1));

            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            boolean anyState = false;
            for (double[] row : pct) for (double v : row) if (v > 0) anyState = true;
            if (!anyState) {
                boolean anySeg = experiments.stream().anyMatch(ExperimentData::hasSegments);
                String msg = anySeg ? "No diffusive-state prediction / no experiments selected"
                                    : "No segments available";
                g2.setColor(new Color(160, 160, 160));
                g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, px + (pw - fm.stringWidth(msg)) / 2, py + ph / 2 + fm.getAscent() / 2);
                drawFrameAndLabels(g2, px, py, pw, ph, nModels);
                g2.dispose();
                return;
            }

            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            double yStep = niceStep(yMax, 5);
            for (double t = 0; t <= yMax + yStep * 0.01; t += yStep) {
                int yp = py + ph - (int) (t / yMax * ph);
                g2.setColor(new Color(225, 225, 225)); g2.setStroke(new BasicStroke(0.5f));
                g2.drawLine(px, yp, px + pw, yp);
                g2.setColor(Color.BLACK); g2.drawLine(px - 4, yp, px, yp);
                String lbl = fmtPct(t);
                g2.drawString(lbl, px - 6 - fm.stringWidth(lbl), yp + fm.getAscent() / 2 - 1);
            }

            int nExp = visible.size();
            double groupW = (double) pw / nModels;
            double barsW  = groupW * 0.78;
            double barW   = barsW / nExp;
            double gap0   = (groupW - barsW) / 2.0;
            for (int mi = 0; mi < nModels; mi++) {
                double groupX = px + mi * groupW;
                for (int ei = 0; ei < nExp; ei++) {
                    int barH = (int) (pct[ei][mi] / yMax * ph);
                    int bx   = (int) (groupX + gap0 + ei * barW);
                    int by   = py + ph - barH;
                    int bw   = Math.max(1, (int) barW - 1);
                    Color base = visible.get(ei).color;
                    g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 200));
                    g2.fillRect(bx, by, bw, barH);
                    g2.setColor(base.darker()); g2.setStroke(new BasicStroke(0.8f));
                    g2.drawRect(bx, by, bw, barH);
                }
            }

            drawFrameAndLabels(g2, px, py, pw, ph, nModels);

            g2.setColor(Color.BLACK);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            java.awt.geom.AffineTransform orig = g2.getTransform();
            String yTitle = "% of segments";
            FontMetrics fm2 = g2.getFontMetrics();
            g2.translate(13, py + ph / 2.0); g2.rotate(-Math.PI / 2);
            g2.drawString(yTitle, -fm2.stringWidth(yTitle) / 2, fm2.getAscent() / 2 - 6);
            g2.setTransform(orig);

            drawLegend(g2, visible);
            g2.dispose();
        }

        private void drawFrameAndLabels(Graphics2D g2, int px, int py, int pw, int ph, int nModels) {
            g2.setColor(Color.BLACK); g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            FontMetrics fm = g2.getFontMetrics();
            double groupW = (double) pw / nModels;
            for (int mi = 0; mi < nModels; mi++) {
                String lbl = PHENOM_NAMES[mi];
                int cx = (int) (px + (mi + 0.5) * groupW);
                g2.drawString(lbl, cx - fm.stringWidth(lbl) / 2, py + ph + 16);
            }
        }

        private void drawLegend(Graphics2D g2, List<ExperimentData> visible) {
            if (visible.isEmpty()) { legend.clear(); return; }
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int totalW = 0;
            for (ExperimentData exp : visible) totalW += 16 + fm.stringWidth(exp.name) + 18;
            Point lp = legend.layout(ML, 5, totalW, 16, 0, 0, getWidth(), getHeight());
            int x = lp.x, y = lp.y + 9;
            for (ExperimentData exp : visible) {
                g2.setColor(exp.color); g2.fillRect(x, y - 9, 12, 12);
                g2.setColor(new Color(80, 80, 80)); g2.drawRect(x, y - 9, 12, 12);
                g2.setColor(Color.BLACK); g2.drawString(exp.name, x + 16, y + 1);
                x += 16 + fm.stringWidth(exp.name) + 18;
            }
        }

        private double niceCeil(double v) {
            double step = niceStep(v, 5);
            return Math.ceil(v / step) * step;
        }
        private double niceStep(double range, int target) {
            if (range <= 0) return 1;
            double rough = range / target;
            double base  = Math.pow(10, Math.floor(Math.log10(rough)));
            for (double m : new double[]{1, 2, 2.5, 5, 10}) if (m * base >= rough) return m * base;
            return base * 10;
        }
        private String fmtPct(double v) {
            if (Math.abs(v - Math.rint(v)) < 1e-9) return String.format("%d%%", (int) Math.rint(v));
            return String.format("%.1f%%", v);
        }
    }
}
