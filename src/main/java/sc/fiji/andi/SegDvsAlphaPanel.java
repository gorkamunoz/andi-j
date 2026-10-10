package sc.fiji.andi;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * "D vs α" density tab for Trajectory Segmentation.
 *
 * <p>Mirrors the Trajectory-Analysis "D vs α" tab (left experiment multi-select,
 * marginal KDE strips on the top/right edges) but, because the data are
 * per-frame or per-segment predictions (far too many to scatter), the centre
 * shows a 2-D density histogram instead of individual points.  There is no
 * point selection / tagging.</p>
 *
 * <ul>
 *   <li>X axis – anomalous exponent α ∈ [0, 2]  (fixed range)</li>
 *   <li>Y axis – D in the display units, auto-ranged from the selected data; a log
 *       axis with log-spaced bins ("Log D axis", default) or a linear one</li>
 *   <li>Centre – 2-D histogram heatmaps, one per selected experiment, overlaid
 *       in the experiment's colour with opacity encoding its own density</li>
 *   <li>Top / right – per-experiment marginal KDE of α and D (on the D axis scale)</li>
 *   <li>Far right – colour-scale bar mapping shade → frame or segment count
 *       (only when a single experiment is selected)</li>
 * </ul>
 * Immobile segments, and in frame mode the frames of immobile segments, are left
 * out unless "Show immobile" is ticked. The legend has one row per experiment: its
 * number of frames or segments and how many of them are immobile.
 *
 * Controls: frames or segments, per-experiment toggles (left), bin count,
 * log-count colour scale.
 */
public class SegDvsAlphaPanel extends JPanel {

    private final List<ExperimentData>         experiments;
    private final Map<ExperimentData, Boolean> expVisible = new LinkedHashMap<>();

    /** What one count of the histogram is: a frame or a segment. */
    private static final String[] LEVELS = {"Frames", "Segments"};

    private final JComboBox<String> levelBox;
    private final JSpinner       alphaBinsSpin, dBinsSpin;   // separate α / D bin counts
    private final JCheckBox      logCountBox;
    private final ImmobileBox    immobileBox = new ImmobileBox();
    private final JCheckBox      logDBox     = new JCheckBox("Log D axis", true);
    private final DvsAlphaDensity.DensityCanvas canvas;

    // ═════════════════════════════════════════════════════════════════════
    // Constructor
    // ═════════════════════════════════════════════════════════════════════

    /** Display units shared by the window's tabs. */
    private final Units units;

    public SegDvsAlphaPanel(List<ExperimentData> experiments, Units units) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        this.units       = units;
        for (ExperimentData exp : experiments) expVisible.put(exp, true);

        levelBox      = new JComboBox<>(LEVELS);
        alphaBinsSpin = new JSpinner(new SpinnerNumberModel(40, 5, 200, 1));
        dBinsSpin     = new JSpinner(new SpinnerNumberModel(40, 5, 200, 1));
        logCountBox   = new JCheckBox("Log count scale", true);
        canvas        = new DvsAlphaDensity.DensityCanvas();

        add(Toolbars.makeScrollable(buildTopBar()), BorderLayout.NORTH);

        JScrollPane leftScroll = new JScrollPane(buildExperimentsPanel());
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));
        leftScroll.setMinimumSize(new Dimension(80, 0));

        // Draggable divider between the Experiments column and the plot
        // (same as the Trajectory-Analysis tabs).
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, canvas);
        split.setDividerLocation(160);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        add(split, BorderLayout.CENTER);

        levelBox.addActionListener(e -> rebuild());
        immobileBox.addActionListener(e -> rebuild());
        logDBox.addActionListener(e -> { canvas.resetViewport(); rebuild(); });
        alphaBinsSpin.addChangeListener(e -> rebuild());
        dBinsSpin.addChangeListener(e -> rebuild());
        logCountBox.addActionListener(e -> {
            canvas.logScale = logCountBox.isSelected();
            canvas.repaint();
        });

        if (experiments.stream().anyMatch(ExperimentData::hasStepPredictions))
            rebuild();
    }

    // ─────────────────────────────────────────────────────────────────────

    private JPanel buildTopBar() {
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Recompute the histogram");
        refreshBtn.addActionListener(e -> rebuild());

        // Zoom / pan / reset tools (same as the Trajectory-Analysis histograms)
        JToggleButton zoomBtn = new JToggleButton("⊕ Zoom", true);
        JToggleButton panBtn  = new JToggleButton("✥ Pan",  false);
        ButtonGroup   viewBG  = new ButtonGroup();
        viewBG.add(zoomBtn); viewBG.add(panBtn);
        JButton resetViewBtn = new JButton("↺ Reset view");
        for (AbstractButton b : new AbstractButton[]{zoomBtn, panBtn, resetViewBtn}) {
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            b.setFocusPainted(false);
        }
        zoomBtn.addActionListener(e -> canvas.setPanMode(false));
        panBtn .addActionListener(e -> canvas.setPanMode(true));
        resetViewBtn.addActionListener(e -> { canvas.resetViewport(); canvas.repaint(); });

        levelBox.setToolTipText("Plot the α and D of every frame, or of every segment");
        alphaBinsSpin.setToolTipText("Number of α bins (histogram + heatmap x-axis)");
        dBinsSpin.setToolTipText("Number of D bins (histogram + heatmap y-axis)");
        logDBox.setToolTipText("Logarithmic D axis, with log-spaced bins");

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.setBorder(BorderFactory.createEtchedBorder());
        bar.add(levelBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(new JLabel("α bins:"));
        bar.add(alphaBinsSpin);
        bar.add(new JLabel("D bins:"));
        bar.add(dBinsSpin);
        bar.add(logDBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(logCountBox);
        bar.add(immobileBox);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(refreshBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(zoomBtn);
        bar.add(panBtn);
        bar.add(resetViewBtn);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(SvgExport.button(() -> canvas, "seg_d_vs_alpha"));
        bar.add(units.button());
        units.addListener(this::rebuild);
        return bar;
    }

    private JPanel buildExperimentsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<JToggleButton> allBtns = new ArrayList<>();
        for (ExperimentData exp : experiments) {
            JToggleButton btn = new JToggleButton(exp.name, true);
            btn.setForeground(exp.color);
            btn.setFont(btn.getFont().deriveFont(Font.BOLD));
            btn.setAlignmentX(Component.LEFT_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            btn.setFocusPainted(false);
            btn.addActionListener(e -> { expVisible.put(exp, btn.isSelected()); rebuild(); });
            panel.add(btn);
            panel.add(Box.createVerticalStrut(3));
            allBtns.add(btn);
        }

        JButton deselectAll = new JButton("Deselect all");
        deselectAll.setFont(deselectAll.getFont().deriveFont(Font.PLAIN, 10f));
        deselectAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        deselectAll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        deselectAll.setFocusPainted(false);
        deselectAll.addActionListener(e -> {
            for (ExperimentData exp : experiments) expVisible.put(exp, false);
            allBtns.forEach(b -> b.setSelected(false));
            rebuild();
        });
        panel.add(Box.createVerticalStrut(6));
        panel.add(deselectAll);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Histogram + marginal computation
    // ═════════════════════════════════════════════════════════════════════

    private void rebuild() {
        int alphaBins = (Integer) alphaBinsSpin.getValue();
        int dBins     = (Integer) dBinsSpin.getValue();
        boolean bySegment = levelBox.getSelectedIndex() == 1;

        List<ExperimentData> sel = new ArrayList<>();
        for (ExperimentData exp : experiments)
            if (expVisible.getOrDefault(exp, true)
                    && (bySegment ? exp.hasSegments() : exp.hasStepPredictions()))
                sel.add(exp);

        // Per-experiment per-frame (or per-segment) values. The D coordinate is log₁₀(D)
        // on a log axis and D on a linear one.
        boolean logD = logDBox.isSelected();
        boolean showImmobile = immobileBox.isSelected();
        int immobileCount = 0;   // immobile values, shown or not
        List<DvsAlphaDensity.Series> series = new ArrayList<>();

        for (ExperimentData exp : sel) {
            List<Double> av = new ArrayList<>();
            List<Double> dv = new ArrayList<>();
            int immobileExp = 0;
            double off = Math.log10(units.diffusion(exp));   // log₁₀(D) in display units
            if (bySegment) {
                for (List<Segment> segs : exp.segments.values())
                    for (Segment s : segs)
                        if (Double.isFinite(s.alpha) && Double.isFinite(s.logD)) {
                            if (s.state == 0) {
                                immobileExp++;
                                if (!showImmobile) continue;
                            }
                            double d = logD ? s.logD + off : Math.pow(10, s.logD + off);
                            av.add(s.alpha); dv.add(d);
                        }
            } else for (Map.Entry<Integer, double[]> e : exp.stepAlpha.entrySet()) {
                double[] al = e.getValue();
                double[] ld = exp.stepLogD != null ? exp.stepLogD.get(e.getKey()) : null;
                if (al == null || ld == null) continue;
                int len = Math.min(al.length, ld.length);
                boolean[] immobile = immobileFrames(exp, e.getKey(), len);
                for (int t = 0; t < len; t++) {
                    if (Double.isFinite(al[t]) && Double.isFinite(ld[t])) {
                        if (immobile != null && immobile[t]) {
                            immobileExp++;
                            if (!showImmobile) continue;
                        }
                        double d = logD ? ld[t] + off : Math.pow(10, ld[t] + off);
                        av.add(al[t]); dv.add(d);
                    }
                }
            }
            immobileCount += immobileExp;
            int all = showImmobile ? av.size() : av.size() + immobileExp;
            series.add(new DvsAlphaDensity.Series(exp.color, toArray(av), toArray(dv), all, immobileExp));
        }

        immobileBox.setImmobilePresent(immobileCount > 0);
        DvsAlphaDensity.show(canvas, series, alphaBins, dBins, logD,
                             "D  (" + units.diffusionLabel() + ")",
                             bySegment ? "segments" : "frames", !showImmobile,
                             logCountBox.isSelected());
    }

    /** Frames of a track that belong to an immobile segment; null when it has no segments. */
    private static boolean[] immobileFrames(ExperimentData exp, int trackId, int len) {
        List<Segment> segs = exp.segments != null ? exp.segments.get(trackId) : null;
        if (segs == null) return null;
        boolean[] immobile = new boolean[len];
        for (Segment s : segs)
            if (s.state == 0)
                for (int t = Math.max(0, s.start); t < Math.min(len, s.start + s.length); t++)
                    immobile[t] = true;
        return immobile;
    }

    static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }
}
