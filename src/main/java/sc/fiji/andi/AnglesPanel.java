package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * "Angles" tab – polar rose histogram of trajectory turning angles.
 *
 * Algorithm mirrors andi_datasets.analysis.dataset_angles:
 *   For each consecutive triplet (p_{i-1}, p_i, p_{i+1}) in a trajectory:
 *     v1 = p_i   − p_{i-1}
 *     v2 = p_{i+1} − p_i
 *     turning angle = atan2(cross(v1,v2), dot(v1,v2))  ∈ (−π, π]
 *   Mapped to [0°, 360°) before binning.
 *
 * The polar rose shows, for each angular bin, a wedge whose radius is
 * proportional to the fraction of angles that fell in that bin (so
 * experiments with different numbers of tracks are comparable).
 * The max radius is shared across all visible experiments.
 */
public class AnglesPanel extends JPanel {

    private final List<ExperimentData>        experiments;
    /** Working list = original experiments plus any tagged populations added later. */
    private final List<ExperimentData>        shown      = new ArrayList<>();
    private final Map<ExperimentData, Boolean> expVisible  = new LinkedHashMap<>();

    /** All turning angles, in radians [0, 2π), indexed by experiment. */
    private final Map<ExperimentData, double[]> anglesMap  = new LinkedHashMap<>();

    private final JTextField binsField;
    private final PolarPanel polarPanel;
    private JComponent       sideColumn;
    private JSplitPane       split;

    // -----------------------------------------------------------------------

    AnglesPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        this.shown.addAll(experiments);
        setBorder(new EmptyBorder(6, 6, 6, 6));

        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        // ── top bar ─────────────────────────────────────────────────────
        binsField = new JTextField("36", 4);
        JButton applyBtn = new JButton("Apply bins");

        JPanel paramBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramBar.add(new JLabel("Bins:"));
        paramBar.add(binsField);
        paramBar.add(applyBtn);
        add(Toolbars.makeScrollable(paramBar), BorderLayout.NORTH);

        // ── center: polar plot ───────────────────────────────────────────
        polarPanel = new PolarPanel();
        paramBar.add(SvgExport.button(() -> polarPanel, "turning_angles"));

        // Listeners registered after polarPanel is assigned
        applyBtn.addActionListener(e -> polarPanel.repaint());
        binsField.addActionListener(e -> polarPanel.repaint());

        // ── plot + draggable right experiment column ──────────────────────
        sideColumn = buildSideColumn();
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, polarPanel, sideColumn);
        split.setResizeWeight(1.0);          // extra space → plot; column stays draggable
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);

        // Compute angles from raw tracks immediately
        computeAngles();
    }

    /**
     * Adds a tagged population (from the D vs α tab) as a new dataset:
     * it gets its own toggle in the right column and its own rose petals.
     */
    void addPopulation(ExperimentData exp) {
        if (shown.contains(exp)) return;
        shown.add(exp);
        expVisible.put(exp, true);
        anglesMap.put(exp, computeAnglesFor(exp));
        int loc = split.getDividerLocation();
        sideColumn = buildSideColumn();
        split.setRightComponent(sideColumn);
        split.setDividerLocation(loc);
        revalidate();
        polarPanel.repaint();
    }

    // -----------------------------------------------------------------------
    // Right-side experiment selector column
    // -----------------------------------------------------------------------

    private JComponent buildSideColumn() {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<JToggleButton> allBtns = new ArrayList<>();
        for (ExperimentData exp : shown) {
            JToggleButton btn = new JToggleButton(exp.name, expVisible.getOrDefault(exp, true));
            btn.setForeground(exp.color);
            btn.setFont(btn.getFont().deriveFont(Font.BOLD));
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            btn.setFocusPainted(false);
            btn.addActionListener(e -> {
                expVisible.put(exp, btn.isSelected());
                polarPanel.repaint();
            });
            col.add(btn);
            col.add(Box.createVerticalStrut(3));
            allBtns.add(btn);
        }

        JButton deselectAll = new JButton("Deselect all");
        deselectAll.setFont(deselectAll.getFont().deriveFont(Font.PLAIN, 10f));
        deselectAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        deselectAll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        deselectAll.setFocusPainted(false);
        deselectAll.addActionListener(e -> {
            for (ExperimentData exp : shown) expVisible.put(exp, false);
            allBtns.forEach(b -> b.setSelected(false));
            polarPanel.repaint();
        });
        col.add(Box.createVerticalStrut(6));
        col.add(deselectAll);

        col.add(Box.createVerticalGlue());

        JButton saveBtn = new JButton("Save angles…");
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        saveBtn.setFocusPainted(false);
        saveBtn.addActionListener(e -> saveAngles());
        col.add(saveBtn);

        JScrollPane scroll = new JScrollPane(col,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(200, 0));
        scroll.setBorder(BorderFactory.createTitledBorder("Experiments"));
        return scroll;
    }

    // -----------------------------------------------------------------------
    // Save turning angles (one row per angle, with its trajectory index)
    // -----------------------------------------------------------------------

    private void saveAngles() {
        if (anglesMap.values().stream().allMatch(a -> a == null || a.length == 0)) {
            JOptionPane.showMessageDialog(this,
                "No turning angles available.",
                "Save angles", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle("Save turning angles");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");

        boolean multiExp = shown.size() > 1;

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            if (multiExp) pw.println("experiment,traj_idx,turning_angle_rad");
            else          pw.println("traj_idx,turning_angle_rad");

            for (ExperimentData exp : shown) {
                for (Map.Entry<Integer, List<double[]>> te : exp.tracks.entrySet()) {
                    int            id    = te.getKey();
                    List<double[]> spots = new ArrayList<>(te.getValue());
                    spots.sort(Comparator.comparingDouble(s -> s[0]));
                    for (int i = 1; i < spots.size() - 1; i++) {
                        double dx1 = spots.get(i  )[1] - spots.get(i-1)[1];
                        double dy1 = spots.get(i  )[2] - spots.get(i-1)[2];
                        double dx2 = spots.get(i+1)[1] - spots.get(i  )[1];
                        double dy2 = spots.get(i+1)[2] - spots.get(i  )[2];
                        if (Math.hypot(dx1, dy1) < 1e-12) continue;
                        if (Math.hypot(dx2, dy2) < 1e-12) continue;
                        double angle = Math.atan2(dx1 * dy2 - dy1 * dx2,
                                                  dx1 * dx2 + dy1 * dy2);
                        if (angle < 0) angle += 2 * Math.PI;
                        if (multiExp) pw.printf("%s,%d,%.6f%n", exp.name, id, angle);
                        else          pw.printf("%d,%.6f%n", id, angle);
                    }
                }
            }
            ij.IJ.log("AnDi-J: turning angles saved → " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not save file:\n" + ex.getMessage(),
                "Save angles – error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -----------------------------------------------------------------------
    // Angle computation
    // -----------------------------------------------------------------------

    /**
     * For every track in every experiment computes all turning angles
     * (atan2 of cross/dot of successive displacement vectors) and stores
     * them in radians in [0, 2π).
     */
    private void computeAngles() {
        anglesMap.clear();
        for (ExperimentData exp : shown)
            anglesMap.put(exp, computeAnglesFor(exp));
    }

    /** Computes all turning angles (radians in [0, 2π)) for one experiment. */
    private static double[] computeAnglesFor(ExperimentData exp) {
        List<Double> list = new ArrayList<>();
        for (List<double[]> rawSpots : exp.tracks.values()) {
            // Sort spots by frame number (column 0)
            List<double[]> spots = new ArrayList<>(rawSpots);
            spots.sort(Comparator.comparingDouble(s -> s[0]));
            for (int i = 1; i < spots.size() - 1; i++) {
                double dx1 = spots.get(i  )[1] - spots.get(i-1)[1];
                double dy1 = spots.get(i  )[2] - spots.get(i-1)[2];
                double dx2 = spots.get(i+1)[1] - spots.get(i  )[1];
                double dy2 = spots.get(i+1)[2] - spots.get(i  )[2];
                // Skip stationary steps (no displacement)
                if (Math.hypot(dx1, dy1) < 1e-12) continue;
                if (Math.hypot(dx2, dy2) < 1e-12) continue;
                // Signed turning angle: positive = CCW, negative = CW
                double angle = Math.atan2(
                    dx1 * dy2 - dy1 * dx2,   // z-component of cross product
                    dx1 * dx2 + dy1 * dy2);   // dot product
                if (angle < 0) angle += 2 * Math.PI;
                list.add(angle);
            }
        }
        double[] arr = new double[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    // -----------------------------------------------------------------------
    // Polar rose panel
    // -----------------------------------------------------------------------

    private class PolarPanel extends JPanel {

        private final MovableLegend legend = new MovableLegend();

        PolarPanel() {
            setBackground(Color.WHITE);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { legend.press(e.getX(), e.getY()); }
                @Override public void mouseDragged(MouseEvent e) {
                    if (legend.dragTo(e.getX(), e.getY())) repaint();
                }
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
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int nBins = parseBins();
            double binW = 2 * Math.PI / nBins;  // width of each angular bin

            // Collect visible experiments
            List<ExperimentData> visible = new ArrayList<>();
            for (ExperimentData exp : shown)
                if (expVisible.getOrDefault(exp, true) &&
                    anglesMap.containsKey(exp))
                    visible.add(exp);

            if (visible.isEmpty()) { g2.dispose(); return; }

            // ── bin each experiment ──────────────────────────────────────
            int[][] counts = new int[visible.size()][nBins];
            int[]   totals = new int[visible.size()];
            for (int ei = 0; ei < visible.size(); ei++) {
                double[] angles = anglesMap.get(visible.get(ei));
                if (angles == null) continue;
                for (double a : angles) {
                    int b = (int)(a / binW);
                    if (b >= nBins) b = nBins - 1;
                    counts[ei][b]++;
                    totals[ei]++;
                }
            }

            // Global maximum frequency (fraction of total) – shared scale
            double maxFrac = 0;
            for (int ei = 0; ei < visible.size(); ei++) {
                if (totals[ei] == 0) continue;
                for (int b = 0; b < nBins; b++)
                    maxFrac = Math.max(maxFrac, (double) counts[ei][b] / totals[ei]);
            }
            if (maxFrac == 0) { g2.dispose(); return; }

            // ── layout ───────────────────────────────────────────────────
            int W = getWidth(), H = getHeight();
            // Leave room for labels (left/right/top/bottom), legend at top-left
            int legendH  = visible.size() * 16 + 8;
            int topMargin    = Math.max(30, legendH);
            int bottomMargin = 30;
            int sideMargin   = 40;
            int sz = Math.min(W - 2 * sideMargin, H - topMargin - bottomMargin);
            if (sz < 20) { g2.dispose(); return; }
            double maxR = sz / 2.0;
            int cx = W / 2;
            int cy = topMargin + sz / 2;

            // ── reference grid ────────────────────────────────────────────
            g2.setStroke(new BasicStroke(0.5f));
            g2.setColor(new Color(215, 215, 215));
            int nRings = 4;
            for (int k = 1; k <= nRings; k++) {
                double r = maxR * k / nRings;
                g2.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            }
            // Spoke lines every 45°
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                g2.drawLine(cx, cy,
                    (int)(cx + maxR * Math.cos(a)),
                    (int)(cy - maxR * Math.sin(a)));
            }

            // ── petals ───────────────────────────────────────────────────
            for (int ei = 0; ei < visible.size(); ei++) {
                if (totals[ei] == 0) continue;
                Color base = visible.get(ei).color;
                Color fill = new Color(base.getRed(), base.getGreen(),
                                       base.getBlue(), 90);
                g2.setStroke(new BasicStroke(0.9f));

                for (int b = 0; b < nBins; b++) {
                    double frac = (double) counts[ei][b] / totals[ei] / maxFrac;
                    double r = maxR * frac;
                    if (r < 0.5) continue;

                    double startA = b * binW;
                    double endA   = startA + binW;
                    int    steps  = Math.max(6, (int)(r * binW));

                    Path2D path = new Path2D.Double();
                    path.moveTo(cx, cy);
                    for (int s = 0; s <= steps; s++) {
                        double a = startA + s * (endA - startA) / steps;
                        // Standard math: x=cos, y=-sin (y axis is flipped in screen coords)
                        path.lineTo(cx + r * Math.cos(a),
                                    cy - r * Math.sin(a));
                    }
                    path.closePath();

                    g2.setColor(fill);
                    g2.fill(path);
                    g2.setColor(base);
                    g2.draw(path);
                }
            }

            // ── outer border circle ───────────────────────────────────────
            g2.setColor(new Color(100, 100, 100));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new Ellipse2D.Double(cx - maxR, cy - maxR, 2 * maxR, 2 * maxR));

            // ── ring labels (% of max) ────────────────────────────────────
            g2.setFont(g2.getFont().deriveFont(9f));
            g2.setColor(new Color(130, 130, 130));
            for (int k = 1; k <= nRings; k++) {
                double r = maxR * k / nRings;
                double pct = maxFrac * k / nRings * 100;
                String lbl = pct < 1 ? String.format("%.1f%%", pct)
                                     : String.format("%.0f%%", pct);
                g2.drawString(lbl, (int)(cx + 3), (int)(cy - r + 10));
            }

            // ── axis labels (0°, 90°, 180°, 270°) ───────────────────────
            g2.setFont(g2.getFont().deriveFont(11f));
            g2.setColor(Color.DARK_GRAY);
            String[] cardLabels  = {"0°", "90°", "180°", "270°"};
            double[] cardAngles  = {0, Math.PI / 2, Math.PI, 3 * Math.PI / 2};
            double   labelR      = maxR + 18;
            FontMetrics fm = g2.getFontMetrics();
            for (int k = 0; k < 4; k++) {
                double a  = cardAngles[k];
                int    lx = (int)(cx + labelR * Math.cos(a));
                int    ly = (int)(cy - labelR * Math.sin(a));
                String lbl = cardLabels[k];
                g2.drawString(lbl,
                    lx - fm.stringWidth(lbl) / 2,
                    ly + fm.getAscent() / 2);
            }

            // ── legend (movable, default top-left) ────────────────────────
            if (visible.isEmpty()) {
                legend.clear();
            } else {
                g2.setFont(g2.getFont().deriveFont(11f));
                fm = g2.getFontMetrics();
                int maxW = 0;
                for (ExperimentData exp : visible) {
                    double[] angles = anglesMap.get(exp);
                    int n = angles != null ? angles.length : 0;
                    maxW = Math.max(maxW, fm.stringWidth(exp.name + "  (n=" + n + " angles)"));
                }
                int boxW = maxW + 16 + 4, boxH = visible.size() * 16 + 4;
                Point lp = legend.layout(8, 8, boxW, boxH, 0, 0, getWidth(), getHeight());
                int bx = lp.x, ly = lp.y;
                for (ExperimentData exp : visible) {
                    double[] angles = anglesMap.get(exp);
                    int n = angles != null ? angles.length : 0;
                    g2.setColor(exp.color);
                    g2.fillRect(bx, ly, 12, 12);
                    g2.setColor(new Color(80, 80, 80));
                    g2.drawRect(bx, ly, 12, 12);
                    g2.drawString(exp.name + "  (n=" + n + " angles)",
                        bx + 16, ly + fm.getAscent());
                    ly += 16;
                }
            }

            g2.dispose();
        }

        private int parseBins() {
            try {
                int n = Integer.parseInt(binsField.getText().trim());
                return Math.max(4, Math.min(360, n));
            } catch (NumberFormatException e) {
                return 36;
            }
        }
    }
}
