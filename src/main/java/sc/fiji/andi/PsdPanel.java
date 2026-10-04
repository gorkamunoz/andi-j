package sc.fiji.andi;

import ij.IJ;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * "PSD" tab – ensemble-averaged power spectral density.
 *
 * <p>Adapts {@code andi_datasets.analysis.psd}, which uses
 * {@code scipy.signal.periodogram} (defaults: sampling 1, boxcar window,
 * constant detrend, one-sided, density scaling) and, for multi-dimensional
 * trajectories, sums the per-dimension periodograms.  Here the single-trajectory
 * periodograms are averaged over all trajectories of an experiment (binned on a
 * shared log-frequency grid), and only the global average is shown — with
 * independent <b>2D / X / Y</b> selectors per experiment.</p>
 *
 * <p>The plot mirrors the tMSD Visualization tab: log–log axes and a "Draw line"
 * tool that reports the slope of a reference line.</p>
 */
public class PsdPanel extends JPanel {

    private static final String[] COMP_LABELS = {"2D", "X", "Y"};

    private final List<ExperimentData> experiments;
    private final PsdPlotPanel plotPanel;

    /** Which components (0=2D,1=X,2=Y) are shown per experiment. */
    private final Map<ExperimentData, Set<Integer>> compSel = new LinkedHashMap<>();
    private final List<JToggleButton> allToggleButtons = new ArrayList<>();

    // Shared log-frequency grid (bin centres) and per-experiment averaged PSD.
    private double[] freqAxis;
    private final Map<ExperimentData, double[][]> psdByComp = new LinkedHashMap<>();

    private final JTextField nFreqField;
    private final JCheckBox  logLogBox;

    // -----------------------------------------------------------------------

    PsdPanel(List<ExperimentData> experiments) {
        super(new BorderLayout(0, 4));
        this.experiments = experiments;
        setBorder(new EmptyBorder(6, 6, 6, 6));

        for (ExperimentData exp : experiments) {
            Set<Integer> s = new LinkedHashSet<>();
            s.add(0);                       // 2D on by default
            compSel.put(exp, s);
        }

        plotPanel = new PsdPlotPanel();

        // ── parameter bar (North) ────────────────────────────────────────
        nFreqField = new JTextField("30", 4);
        logLogBox  = new JCheckBox("Log–log", true);
        JButton computeBtn = new JButton("Compute PSD");
        JButton saveBtn    = new JButton("Save PSD…");
        saveBtn.setToolTipText("Export the averaged 2D PSD curve per experiment to CSV");

        JPanel paramBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramBar.add(new JLabel("Freq. points:")); paramBar.add(nFreqField);
        paramBar.add(logLogBox);
        paramBar.add(computeBtn);
        paramBar.add(saveBtn);
        add(Toolbars.makeScrollable(paramBar), BorderLayout.NORTH);

        // ── plot toolbar (draw line) ─────────────────────────────────────
        JToggleButton drawLineBtn   = new JToggleButton("✏ Draw line", false);
        JButton       clearLinesBtn = new JButton("✕ Clear lines");
        for (AbstractButton b : new AbstractButton[]{drawLineBtn, clearLinesBtn}) {
            b.setFont(b.getFont().deriveFont(Font.PLAIN, 11f));
            b.setFocusPainted(false);
        }
        JPanel plotToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        plotToolbar.add(drawLineBtn);
        plotToolbar.add(clearLinesBtn);
        drawLineBtn .addActionListener(e -> plotPanel.setDrawMode(drawLineBtn.isSelected()));
        clearLinesBtn.addActionListener(e -> plotPanel.clearLines());

        JPanel plotArea = new JPanel(new BorderLayout(0, 0));
        plotToolbar.add(SvgExport.button(() -> plotPanel, "psd"));
        plotArea.add(plotToolbar, BorderLayout.NORTH);
        plotArea.add(plotPanel,   BorderLayout.CENTER);

        // ── split pane (Center) ──────────────────────────────────────────
        JScrollPane leftScroll = new JScrollPane(buildExperimentsPanel());
        leftScroll.setPreferredSize(new Dimension(190, 0));
        leftScroll.setBorder(BorderFactory.createTitledBorder("Experiments"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, plotArea);
        split.setDividerLocation(200);
        split.setResizeWeight(0.0);
        add(split, BorderLayout.CENTER);

        computeBtn.addActionListener(e -> recompute());
        saveBtn   .addActionListener(e -> savePsd());
        logLogBox .addActionListener(e -> { plotPanel.setLogScale(logLogBox.isSelected()); plotPanel.repaint(); });

        recompute();   // compute on open
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
            for (ExperimentData exp : experiments) compSel.get(exp).clear();
            allToggleButtons.forEach(b -> b.setSelected(false));
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

    private JPanel makeExpRow(ExperimentData exp) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

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

        Set<Integer> sel = compSel.get(exp);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1));
        JLabel lbl = new JLabel("Avg");
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 9f));
        lbl.setPreferredSize(new Dimension(28, 16));
        btnRow.add(lbl);
        boolean[] defaults = {true, false, false};
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
            btnRow.add(btn);
        }
        row.add(btnRow);
        return row;
    }

    // -----------------------------------------------------------------------
    // PSD computation
    // -----------------------------------------------------------------------

    private void recompute() {
        int nFreq = Math.max(4, Math.min(200, parseIntOr(nFreqField.getText(), 30)));

        // Longest trajectory → lowest resolvable frequency (1/maxLen)
        int maxLen = 0;
        for (ExperimentData exp : experiments)
            for (List<double[]> spots : exp.tracks.values())
                maxLen = Math.max(maxLen, spots.size());
        if (maxLen < 4) { psdByComp.clear(); plotPanel.repaint(); return; }

        double fMin = 1.0 / maxLen;
        double fMax = 0.5;                       // Nyquist (fs = 1)
        double logLo = Math.log(fMin), logHi = Math.log(fMax);

        // Bin centres (geometric)
        freqAxis = new double[nFreq];
        for (int b = 0; b < nFreq; b++)
            freqAxis[b] = Math.exp(logLo + (b + 0.5) / nFreq * (logHi - logLo));

        psdByComp.clear();
        for (ExperimentData exp : experiments) {
            double[][] sum = new double[3][nFreq];
            int[][]    cnt = new int[3][nFreq];

            for (List<double[]> rawSpots : exp.tracks.values()) {
                List<double[]> spots = new ArrayList<>(rawSpots);
                spots.sort(Comparator.comparingDouble(s -> s[0]));
                int n = spots.size();
                if (n < 4) continue;

                double[] x = new double[n], y = new double[n];
                for (int i = 0; i < n; i++) { x[i] = spots.get(i)[1]; y[i] = spots.get(i)[2]; }

                double[] px = periodogram(x);   // length n/2+1, freq k/n
                double[] py = periodogram(y);
                int half = n / 2;
                for (int k = 1; k <= half; k++) {          // skip DC (k=0)
                    double f = (double) k / n;
                    int b = (int) ((Math.log(f) - logLo) / (logHi - logLo) * nFreq);
                    if (b < 0) b = 0; if (b >= nFreq) b = nFreq - 1;
                    double vx = px[k], vy = py[k];
                    sum[0][b] += vx + vy; cnt[0][b]++;       // 2D
                    sum[1][b] += vx;      cnt[1][b]++;       // X
                    sum[2][b] += vy;      cnt[2][b]++;       // Y
                }
            }

            double[][] psd = new double[3][nFreq];
            for (int c = 0; c < 3; c++)
                for (int b = 0; b < nFreq; b++)
                    psd[c][b] = cnt[c][b] > 0 ? sum[c][b] / cnt[c][b] : Double.NaN;
            psdByComp.put(exp, psd);
        }

        plotPanel.setLogScale(logLogBox.isSelected());
        plotPanel.repaint();
    }

    /**
     * One-sided power spectral density of a single series, replicating
     * {@code scipy.signal.periodogram} defaults (fs=1, boxcar window,
     * detrend='constant', return_onesided, scaling='density').
     *
     * @return {@code double[N/2 + 1]}; element k is the PSD at frequency k/N.
     */
    static double[] periodogram(double[] series) {
        int N = series.length;
        double mean = 0;
        for (double v : series) mean += v;
        mean /= N;

        int M = N / 2 + 1;
        double[] p = new double[M];
        for (int k = 0; k < M; k++) {
            double re = 0, im = 0;
            double w = -2.0 * Math.PI * k / N;
            for (int t = 0; t < N; t++) {
                double xt = series[t] - mean;        // constant detrend
                double ang = w * t;
                re += xt * Math.cos(ang);
                im += xt * Math.sin(ang);
            }
            double val = (re * re + im * im) / N;    // |X|² / (fs · Σwin²),  Σwin²=N
            boolean nyquist = (N % 2 == 0 && k == M - 1);
            if (k != 0 && !nyquist) val *= 2.0;       // one-sided doubling
            p[k] = val;
        }
        return p;
    }

    private static int parseIntOr(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }

    // -----------------------------------------------------------------------
    // Save PSD curve
    // -----------------------------------------------------------------------

    /** Exports the averaged 2D PSD per experiment: columns experiment, frequency, psd. */
    private void savePsd() {
        if (freqAxis == null || psdByComp.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No PSD computed yet. Click «Compute PSD» first.",
                "Save PSD", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File f = CsvExport.chooseFile(this, "Save PSD curve");
        if (f == null) return;

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(f)))) {
            pw.println("experiment,frequency,psd");
            for (ExperimentData exp : experiments) {
                double[][] psd = psdByComp.get(exp);
                if (psd == null) continue;
                for (int i = 0; i < freqAxis.length; i++) {
                    double v = psd[0][i];              // 2D component
                    if (!Double.isFinite(v)) continue; // skip empty frequency bins
                    pw.printf("%s,%.8e,%.8e%n", CsvExport.name(exp), freqAxis[i], v);
                }
            }
            IJ.log("AnDi-J: PSD saved → " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not save file:\n" + ex.getMessage(),
                "Save PSD – error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ======================================================================
    // Plot canvas (log–log; draw-line slope tool; mirrors TmsdPlotPanel)
    // ======================================================================

    class PsdPlotPanel extends JPanel {

        private boolean logScale = true;
        private static final int ML = 70, MR = 22, MT = 22, MB = 52;

        // Draw-line state
        private final List<double[]> drawnLines = new ArrayList<>();  // {x1,y1,x2,y2} data coords
        private boolean drawMode = false, isDrawing = false;
        private final MovableLegend legend = new MovableLegend();
        private int linePx0, linePy0, linePx1, linePy1;

        // Saved render state for pixel↔data transforms
        private double savedXMin, savedXMax, savedYMin, savedYMax;
        private int    savedPx, savedPy, savedPw, savedPh;
        private boolean savedValid = false;

        PsdPlotPanel() {
            setBackground(new Color(240, 240, 240));
            setPreferredSize(new Dimension(560, 420));
            addMouseInteraction();
        }

        void setLogScale(boolean b) { this.logScale = b; }
        void setDrawMode(boolean on) {
            drawMode = on; isDrawing = false;
            setCursor(on ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                         : Cursor.getDefaultCursor());
        }
        void clearLines() { drawnLines.clear(); repaint(); }

        private void addMouseInteraction() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (legend.press(e.getX(), e.getY())) return;
                    if (!drawMode || !savedValid) return;
                    isDrawing = true;
                    linePx0 = linePx1 = e.getX(); linePy0 = linePy1 = e.getY();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (legend.dragTo(e.getX(), e.getY())) { repaint(); return; }
                    if (!isDrawing) return;
                    linePx1 = e.getX(); linePy1 = e.getY(); repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (legend.isDragging()) { legend.release(); return; }
                    if (!isDrawing) return;
                    isDrawing = false;
                    if (Math.abs(linePx1 - linePx0) > 4 || Math.abs(linePy1 - linePy0) > 4)
                        drawnLines.add(new double[]{
                            pixToDataX(linePx0), pixToDataY(linePy0),
                            pixToDataX(linePx1), pixToDataY(linePy1)});
                    repaint();
                }
                @Override public void mouseMoved(MouseEvent e) {
                    if (legend.over(e.getX(), e.getY()))
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    else
                        setCursor(drawMode ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                                           : Cursor.getDefaultCursor());
                }
            };
            addMouseListener(ma); addMouseMotionListener(ma);
        }

        private double pixToDataX(int px) {
            if (!savedValid || savedPw <= 0) return 0;
            double frac = (double)(px - savedPx) / savedPw;
            if (logScale) return Math.pow(10, Math.log10(savedXMin) + frac*(Math.log10(savedXMax)-Math.log10(savedXMin)));
            return savedXMin + frac * (savedXMax - savedXMin);
        }
        private double pixToDataY(int py) {
            if (!savedValid || savedPh <= 0) return 0;
            double frac = (double)(savedPy + savedPh - py) / savedPh;
            if (logScale) return Math.pow(10, Math.log10(savedYMin) + frac*(Math.log10(savedYMax)-Math.log10(savedYMin)));
            return savedYMin + frac * (savedYMax - savedYMin);
        }

        private double computeSlope(double x1, double y1, double x2, double y2) {
            if (logScale) {
                if (x1 <= 0 || x2 <= 0 || y1 <= 0 || y2 <= 0) return Double.NaN;
                double dlx = Math.log10(x2) - Math.log10(x1);
                return Math.abs(dlx) < 1e-12 ? Double.NaN
                       : (Math.log10(y2) - Math.log10(y1)) / dlx;
            }
            double dx = x2 - x1;
            return Math.abs(dx) < 1e-300 ? Double.NaN : (y2 - y1) / dx;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int px = ML, py = MT, pw = W - ML - MR, ph = H - MT - MB;
            if (pw < 20 || ph < 20) { g2.dispose(); return; }
            g2.setColor(Color.WHITE); g2.fillRect(px, py, pw, ph);

            double[] b = computeBounds();
            if (b == null) {
                savedValid = false;
                drawMsg(g2, "No visible data – enable a component or click «Compute PSD»", px, py, pw, ph);
            } else {
                double xMin = b[0], xMax = b[1], yMin = b[2], yMax = b[3];
                savedXMin = xMin; savedXMax = xMax; savedYMin = yMin; savedYMax = yMax;
                savedPx = px; savedPy = py; savedPw = pw; savedPh = ph; savedValid = true;

                drawGrid(g2, xMin, xMax, yMin, yMax, px, py, pw, ph);
                g2.setClip(px, py, pw, ph);
                drawCurves(g2, xMin, xMax, yMin, yMax, px, py, pw, ph);
                drawAnnotationSegments(g2, xMin, xMax, yMin, yMax, px, py, pw, ph);
                g2.setClip(null);
                drawTicks(g2, xMin, xMax, yMin, yMax, px, py, pw, ph);
                drawLegend(g2, px, py, pw, ph);
                drawSlopeLabels(g2, xMin, xMax, yMin, yMax, px, py, pw, ph);
            }
            g2.setColor(Color.BLACK); g2.setStroke(new BasicStroke(1f));
            g2.drawRect(px, py, pw, ph);
            drawAxisTitles(g2, px, py, pw, ph);
            g2.dispose();
        }

        // ── curves ────────────────────────────────────────────────────────
        private void drawCurves(Graphics2D g2, double xMin, double xMax, double yMin, double yMax,
                                 int px, int py, int pw, int ph) {
            if (freqAxis == null) return;
            for (ExperimentData exp : experiments) {
                double[][] psd = psdByComp.get(exp);
                if (psd == null) continue;
                for (int comp : compSel.getOrDefault(exp, Collections.emptySet())) {
                    g2.setColor(TmsdPanel.compColor(exp.color, comp));
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    drawPolyline(g2, freqAxis, psd[comp], xMin, xMax, yMin, yMax, px, py, pw, ph);
                }
            }
        }

        private void drawPolyline(Graphics2D g2, double[] xs, double[] ys,
                                   double xMin, double xMax, double yMin, double yMax,
                                   int px, int py, int pw, int ph) {
            if (xs == null || ys == null) return;
            List<Integer> xa = new ArrayList<>(), ya = new ArrayList<>();
            for (int i = 0; i < xs.length && i < ys.length; i++) {
                double x = xs[i], y = ys[i];
                if (Double.isNaN(y)) continue;
                if (logScale && (x <= 0 || y <= 0)) continue;
                xa.add(mapX(x, xMin, xMax, px, pw));
                ya.add(mapY(y, yMin, yMax, py, ph));
            }
            if (xa.size() < 2) return;
            int[] ax = new int[xa.size()], ay = new int[ya.size()];
            for (int i = 0; i < xa.size(); i++) { ax[i] = xa.get(i); ay[i] = ya.get(i); }
            g2.drawPolyline(ax, ay, ax.length);
        }

        // ── bounds ──────────────────────────────────────────────────────────
        private double[] computeBounds() {
            if (freqAxis == null) return null;
            double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE;
            double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
            boolean any = false;
            for (ExperimentData exp : experiments) {
                double[][] psd = psdByComp.get(exp);
                if (psd == null) continue;
                Set<Integer> sel = compSel.getOrDefault(exp, Collections.emptySet());
                if (sel.isEmpty()) continue;
                for (int comp : sel) {
                    for (int i = 0; i < freqAxis.length; i++) {
                        double x = freqAxis[i], y = psd[comp][i];
                        boolean okY = logScale ? (!Double.isNaN(y) && y > 0) : Double.isFinite(y);
                        boolean okX = logScale ? x > 0 : Double.isFinite(x);
                        if (okX && okY) {
                            xMin = Math.min(xMin, x); xMax = Math.max(xMax, x);
                            yMin = Math.min(yMin, y); yMax = Math.max(yMax, y);
                            any = true;
                        }
                    }
                }
            }
            if (!any) return null;
            if (logScale) {
                if (xMin == xMax) xMax = xMin * 10;
                if (yMin == yMax) yMax = yMin * 10;
                double dX = (Math.log10(xMax) - Math.log10(xMin)) * 0.05;
                double dY = (Math.log10(yMax) - Math.log10(yMin)) * 0.05;
                if (dX == 0) dX = 0.2; if (dY == 0) dY = 0.2;
                return new double[]{Math.pow(10, Math.log10(xMin)-dX), Math.pow(10, Math.log10(xMax)+dX),
                                    Math.pow(10, Math.log10(yMin)-dY), Math.pow(10, Math.log10(yMax)+dY)};
            }
            if (xMin == xMax) { xMin -= 0.05; xMax += 0.05; }
            if (yMin == yMax) { yMin -= Math.abs(yMin)*0.1+1e-12; yMax += Math.abs(yMax)*0.1+1e-12; }
            double padX = (xMax-xMin)*0.05, padY = (yMax-yMin)*0.05;
            return new double[]{xMin-padX, xMax+padX, Math.min(0, yMin-padY), yMax+padY};
        }

        // ── mapping ──────────────────────────────────────────────────────────
        private int mapX(double v, double xMin, double xMax, int ox, int w) {
            if (logScale) {
                double lo = Math.log10(xMin), hi = Math.log10(xMax);
                return ox + (int)((Math.log10(Math.max(v,1e-300)) - lo)/(hi-lo)*w);
            }
            return ox + (int)((v - xMin)/(xMax - xMin)*w);
        }
        private int mapY(double v, double yMin, double yMax, int oy, int h) {
            if (logScale) {
                double lo = Math.log10(yMin), hi = Math.log10(yMax);
                return oy + h - (int)((Math.log10(Math.max(v,1e-300)) - lo)/(hi-lo)*h);
            }
            return oy + h - (int)((v - yMin)/(yMax - yMin)*h);
        }

        // ── annotation lines + slope labels ─────────────────────────────────
        private void drawAnnotationSegments(Graphics2D g2, double xMin, double xMax, double yMin, double yMax,
                                             int px, int py, int pw, int ph) {
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{6f,4f}, 0f));
            g2.setColor(new Color(50,50,50));
            for (double[] ln : drawnLines)
                g2.drawLine(mapX(ln[0],xMin,xMax,px,pw), mapY(ln[1],yMin,yMax,py,ph),
                            mapX(ln[2],xMin,xMax,px,pw), mapY(ln[3],yMin,yMax,py,ph));
            if (isDrawing) g2.drawLine(linePx0, linePy0, linePx1, linePy1);
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawSlopeLabels(Graphics2D g2, double xMin, double xMax, double yMin, double yMax,
                                      int px, int py, int pw, int ph) {
            for (double[] ln : drawnLines) {
                double s = computeSlope(ln[0], ln[1], ln[2], ln[3]);
                if (!Double.isFinite(s)) continue;
                drawSlopeLabel(g2, (mapX(ln[0],xMin,xMax,px,pw)+mapX(ln[2],xMin,xMax,px,pw))/2,
                                   (mapY(ln[1],yMin,yMax,py,ph)+mapY(ln[3],yMin,yMax,py,ph))/2, s);
            }
            if (isDrawing) {
                double s = computeSlope(pixToDataX(linePx0), pixToDataY(linePy0),
                                        pixToDataX(linePx1), pixToDataY(linePy1));
                if (Double.isFinite(s))
                    drawSlopeLabel(g2, (linePx0+linePx1)/2, (linePy0+linePy1)/2, s);
            }
        }

        private void drawSlopeLabel(Graphics2D g2, int cx, int cy, double slope) {
            String text = String.format("slope = %.2f", slope);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(text), th = fm.getHeight(), pad = 3;
            int bx = cx - tw/2 - pad, by = cy - th - pad - 4;
            g2.setColor(new Color(255,255,220,220)); g2.fillRoundRect(bx, by, tw+pad*2, th+pad, 5, 5);
            g2.setColor(new Color(120,120,80)); g2.setStroke(new BasicStroke(0.6f));
            g2.drawRoundRect(bx, by, tw+pad*2, th+pad, 5, 5);
            g2.setColor(new Color(40,40,40)); g2.drawString(text, bx+pad, by+pad+fm.getAscent()-1);
        }

        // ── grid / ticks / titles / legend ──────────────────────────────────
        private void drawGrid(Graphics2D g2, double xMin, double xMax, double yMin, double yMax,
                               int px, int py, int pw, int ph) {
            g2.setStroke(new BasicStroke(0.5f));
            if (logScale) {
                logGrid(g2, xMin, xMax, true,  xMin, xMax, yMin, yMax, px, py, pw, ph);
                logGrid(g2, yMin, yMax, false, xMin, xMax, yMin, yMax, px, py, pw, ph);
            } else {
                g2.setColor(new Color(220,220,220));
                double xs = PlotUtils.niceStep(xMax-xMin, 6);
                for (double t = Math.ceil(xMin/xs)*xs; t <= xMax+xs*0.01; t += xs)
                    g2.drawLine(mapX(t,xMin,xMax,px,pw), py, mapX(t,xMin,xMax,px,pw), py+ph);
                double ys = PlotUtils.niceStep(yMax-yMin, 5);
                for (double t = Math.floor(yMin/ys)*ys; t <= yMax+ys*0.01; t += ys) {
                    if (t < yMin) continue;
                    g2.drawLine(px, mapY(t,yMin,yMax,py,ph), px+pw, mapY(t,yMin,yMax,py,ph));
                }
            }
            g2.setStroke(new BasicStroke(1f));
        }
        private void logGrid(Graphics2D g2, double vMin, double vMax, boolean isX,
                              double xMin, double xMax, double yMin, double yMax,
                              int px, int py, int pw, int ph) {
            int e0 = (int)Math.floor(Math.log10(vMin)), e1 = (int)Math.ceil(Math.log10(vMax));
            for (int e = e0; e <= e1; e++) for (int m : new int[]{1,2,5}) {
                double val = m*Math.pow(10,e);
                if (val < vMin || val > vMax) continue;
                boolean major = m == 1;
                g2.setColor(major ? new Color(200,200,200) : new Color(230,230,230));
                g2.setStroke(new BasicStroke(major ? 0.6f : 0.4f));
                if (isX) g2.drawLine(mapX(val,xMin,xMax,px,pw), py, mapX(val,xMin,xMax,px,pw), py+ph);
                else     g2.drawLine(px, mapY(val,yMin,yMax,py,ph), px+pw, mapY(val,yMin,yMax,py,ph));
            }
        }

        private void drawTicks(Graphics2D g2, double xMin, double xMax, double yMin, double yMax,
                                int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            if (logScale) {
                logTicks(g2, fm, xMin, xMax, true,  xMin, xMax, yMin, yMax, px, py, pw, ph);
                logTicks(g2, fm, yMin, yMax, false, xMin, xMax, yMin, yMax, px, py, pw, ph);
            } else {
                double xs = PlotUtils.niceStep(xMax-xMin, 6);
                for (double t = Math.ceil(xMin/xs)*xs; t <= xMax+xs*0.01; t += xs) {
                    int p = mapX(t,xMin,xMax,px,pw);
                    g2.drawLine(p, py+ph, p, py+ph+5);
                    String l = fmtLinear(t); g2.drawString(l, p-fm.stringWidth(l)/2, py+ph+17);
                }
                double ys = PlotUtils.niceStep(yMax-yMin, 5);
                for (double t = Math.floor(yMin/ys)*ys; t <= yMax+ys*0.01; t += ys) {
                    if (t < yMin) continue;
                    int p = mapY(t,yMin,yMax,py,ph);
                    g2.drawLine(px-5, p, px, p);
                    String l = fmtLinear(t); g2.drawString(l, px-6-fm.stringWidth(l), p+fm.getAscent()/2-1);
                }
            }
        }
        private void logTicks(Graphics2D g2, FontMetrics fm, double vMin, double vMax, boolean isX,
                               double xMin, double xMax, double yMin, double yMax,
                               int px, int py, int pw, int ph) {
            int e0 = (int)Math.floor(Math.log10(vMin)), e1 = (int)Math.ceil(Math.log10(vMax));
            for (int e = e0; e <= e1; e++) for (int m = 1; m <= 9; m++) {
                double val = m*Math.pow(10,e);
                if (val < vMin || val > vMax) continue;
                boolean major = m == 1; int len = major ? 5 : 3;
                if (isX) {
                    int p = mapX(val,xMin,xMax,px,pw);
                    g2.drawLine(p, py+ph, p, py+ph+len);
                    if (major) { String l = fmtLog(val); g2.drawString(l, p-fm.stringWidth(l)/2, py+ph+17); }
                } else {
                    int p = mapY(val,yMin,yMax,py,ph);
                    g2.drawLine(px-len, p, px, p);
                    if (major) { String l = fmtLog(val); g2.drawString(l, px-6-fm.stringWidth(l), p+fm.getAscent()/2-1); }
                }
            }
        }

        private void drawLegend(Graphics2D g2, int px, int py, int pw, int ph) {
            List<int[]> entries = new ArrayList<>();
            for (int ei = 0; ei < experiments.size(); ei++) {
                ExperimentData exp = experiments.get(ei);
                if (!psdByComp.containsKey(exp)) continue;
                for (int c : compSel.getOrDefault(exp, Collections.emptySet())) entries.add(new int[]{ei, c});
            }
            if (entries.isEmpty()) { legend.clear(); return; }
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int lineH = 18, pad = 6, maxW = 0;
            for (int[] e : entries)
                maxW = Math.max(maxW, fm.stringWidth(experiments.get(e[0]).name + " (" + COMP_LABELS[e[1]] + ")"));
            int boxW = maxW + 30 + pad*2, boxH = entries.size()*lineH + pad*2;
            Point lp = legend.layout(px + pw - boxW - 6, py + 6, boxW, boxH, px, py, pw, ph);
            int lx = lp.x, ly = lp.y;
            g2.setColor(new Color(255,255,255,210)); g2.fillRoundRect(lx, ly, boxW, boxH, 6, 6);
            g2.setColor(new Color(180,180,180)); g2.setStroke(new BasicStroke(0.7f)); g2.drawRoundRect(lx, ly, boxW, boxH, 6, 6);
            for (int i = 0; i < entries.size(); i++) {
                ExperimentData exp = experiments.get(entries.get(i)[0]);
                int comp = entries.get(i)[1];
                int ry = ly + pad + (i+1)*lineH - 3;
                g2.setColor(TmsdPanel.compColor(exp.color, comp));
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(lx+pad, ry-4, lx+pad+18, ry-4);
                g2.setStroke(new BasicStroke(1f)); g2.setColor(Color.BLACK);
                g2.drawString(exp.name + " (" + COMP_LABELS[comp] + ")", lx+pad+22, ry);
            }
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawAxisTitles(Graphics2D g2, int px, int py, int pw, int ph) {
            g2.setColor(Color.BLACK); g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();
            String xt = "Frequency (1/frames)";
            g2.drawString(xt, px + (pw - fm.stringWidth(xt))/2, py+ph+MB-8);
            String yt = "PSD";
            AffineTransform orig = g2.getTransform();
            g2.translate(12, py + ph/2.0); g2.rotate(-Math.PI/2);
            g2.drawString(yt, -fm.stringWidth(yt)/2, fm.getAscent()/2);
            g2.setTransform(orig);
        }

        private void drawMsg(Graphics2D g2, String msg, int px, int py, int pw, int ph) {
            g2.setColor(new Color(160,160,160)); g2.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, px+(pw-fm.stringWidth(msg))/2, py+ph/2+fm.getAscent()/2);
        }

        private String fmtLog(double v) {
            if (v <= 0) return "0";
            int e = (int)Math.floor(Math.log10(v));
            if (e >= -3 && e <= 4) {
                if (v >= 1) return String.valueOf(Math.round(v));
                return String.format("%.3g", v).replaceAll("0+$","").replaceAll("\\.$","");
            }
            return String.format("1e%d", e);
        }
        private String fmtLinear(double v) {
            if (v == 0) return "0";
            double abs = Math.abs(v);
            if (abs >= 0.001 && abs < 10000)
                return String.format("%.4g", v).replaceAll("0+$","").replaceAll("\\.$","");
            return String.format("%.2e", v).replace("e+0","e").replace("e-0","e-").replace("e+","e");
        }
    }
}
