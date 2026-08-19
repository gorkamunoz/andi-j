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
 * "Phenom. model" tab for Trajectory Segmentation.
 *
 * <p>Shows, per experiment, the percentage of trajectories the segmentation
 * model classified into each AnDi-2 <b>phenomenological model</b>. The
 * classification is produced during <em>Perform Analysis</em> (from the model's
 * {@code model_type} head), so — unlike the Analysis "Diffusion Model" tab —
 * there are no ML controls here; the model is chosen in the Data Manager.</p>
 */
public class SegPhenomPanel extends JPanel {

    /** AnDi-2 phenomenological models, in the model's output-class order. */
    public static final String[] PHENOM_NAMES = {
        "Single-state", "Multi-state", "Dimerization", "Confinement", "Immobile traps"
    };

    private final List<ExperimentData>         experiments;
    private final Map<ExperimentData, Boolean> expVisible = new LinkedHashMap<>();

    private final BarPanel  barPanel;
    private JComponent      sideColumn;
    private JSplitPane      split;

    public SegPhenomPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        setBorder(new EmptyBorder(6, 6, 6, 6));
        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        JLabel info = new JLabel("<html><i>Percentage of trajectories classified into each "
            + "phenomenological model (from the segmentation model).</i></html>");
        info.setBorder(new EmptyBorder(2, 4, 4, 4));
        info.setForeground(new Color(80, 80, 80));
        add(info, BorderLayout.NORTH);

        barPanel   = new BarPanel();
        sideColumn = buildSideColumn();
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, barPanel, sideColumn);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);
    }

    // -----------------------------------------------------------------------

    private JComponent buildSideColumn() {
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
            b.addActionListener(e -> { expVisible.put(exp, b.isSelected()); barPanel.repaint(); });
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
            barPanel.repaint();
        });

        JButton saveBtn = new JButton("Save…");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.PLAIN, 10f));
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        saveBtn.setToolTipText("Export the per-trajectory phenomenological-model class to CSV");
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

    private void save() {
        if (experiments.stream().noneMatch(ExperimentData::hasPhenomModel)) {
            JOptionPane.showMessageDialog(this, "No phenomenological-model predictions available.",
                "Save", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle("Save phenomenological-model predictions");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            pw.println("experiment,traj_idx,phenom_model");
            for (ExperimentData exp : experiments) {
                if (!exp.hasPhenomModel()) continue;
                for (Map.Entry<Integer, Integer> e : exp.phenomModel.entrySet()) {
                    int c = e.getValue();
                    if (c < 0 || c >= PHENOM_NAMES.length) continue;
                    pw.printf("%s,%d,%s%n", exp.name, e.getKey(), PHENOM_NAMES[c]);
                }
            }
            IJ.log("AnDi-J: phenomenological-model predictions saved → " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save file:\n" + ex.getMessage(),
                "Save – error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Bar chart
    // ═════════════════════════════════════════════════════════════════════

    private class BarPanel extends JPanel {
        private static final int ML = 56, MR = 22, MT = 46, MB = 42;
        private final MovableLegend legend = new MovableLegend();

        BarPanel() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(560, 380));
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
                if (expVisible.getOrDefault(exp, true) && exp.hasPhenomModel()) visible.add(exp);

            int nModels = PHENOM_NAMES.length;
            double[][] pct = new double[visible.size()][nModels];
            for (int ei = 0; ei < visible.size(); ei++) {
                int[] counts = new int[nModels];
                int total = 0;
                for (int m : visible.get(ei).phenomModel.values())
                    if (m >= 0 && m < nModels) { counts[m]++; total++; }
                if (total > 0)
                    for (int m = 0; m < nModels; m++) pct[ei][m] = 100.0 * counts[m] / total;
            }

            double maxPct = 0;
            for (double[] row : pct) for (double v : row) maxPct = Math.max(maxPct, v);
            double yMax = niceCeil(Math.max(maxPct, 1));

            g2.setColor(Color.WHITE);
            g2.fillRect(px, py, pw, ph);

            if (visible.isEmpty()) {
                boolean any = experiments.stream().anyMatch(ExperimentData::hasPhenomModel);
                String msg = any ? "No experiments selected"
                                 : "No phenomenological-model prediction available";
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
            String yTitle = "% of trajectories";
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
