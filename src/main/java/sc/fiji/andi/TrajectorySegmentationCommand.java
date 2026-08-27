package sc.fiji.andi;

import ai.onnxruntime.OrtException;
import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry-point plugin for the Trajectory Segmentation feature.
 * Opens (or brings to front) the persistent data-manager window configured
 * for segmentation:  on "Perform Analysis" it runs the andi-j-seg-v0 model and
 * opens {@link TrajectorySegmentationWindow}.
 *
 * Registered in plugins.config as: Plugins > AnDi-J > Trajectory Segmentation
 */
public class TrajectorySegmentationCommand implements PlugIn {

    /** Singleton data-manager – persists for the ImageJ session lifetime. */
    private static DataManagerWindow segDataManager;

    /** The currently open segmentation window (may be null or disposed). */
    private static JFrame segWindow;

    /**
     * Session cache of segmentation predictions, keyed by a content signature of
     * the experiment (name + TTA flag + all track data). Lets repeated
     * "Perform Analysis" runs on an already-analysed dataset skip inference.
     */
    private static final Map<String, Cached> CACHE = new HashMap<>();

    /** Cached per-experiment predictions. */
    private static final class Cached {
        final Map<Integer, double[]>            stepAlpha;
        final Map<Integer, double[]>            stepLogD;
        final Map<Integer, List<Segment>>       segments;
        Cached(Map<Integer, double[]> a, Map<Integer, double[]> d,
               Map<Integer, List<Segment>> s) {
            stepAlpha = a; stepLogD = d; segments = s;
        }
    }

    /**
     * Content signature identifying a dataset + model selection, so an identical
     * re-analysis is served from {@link #CACHE}. Uses 64-bit FNV-1a over the
     * experiment name, the model config and every track's id / frame / x / y.
     */
    private static String signature(ExperimentData exp, SegModelConfig cfg) {
        long h = 1469598103934665603L;   // FNV offset basis
        h = mix(h, exp.name.hashCode());
        h = mix(h, cfg.key().hashCode());
        h = mix(h, exp.tracks.size());
        for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
            h = mix(h, e.getKey());
            List<double[]> spots = e.getValue();
            h = mix(h, spots.size());
            for (double[] s : spots) {
                h = mix(h, Double.hashCode(s[0]));   // frame
                h = mix(h, Double.hashCode(s[1]));   // x
                h = mix(h, Double.hashCode(s[2]));   // y
            }
        }
        return Long.toHexString(h);
    }

    private static long mix(long h, long v) { h ^= v; h *= 1099511628211L; return h; }

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> {
            if (segDataManager == null || !segDataManager.isDisplayable())
                segDataManager = new DataManagerWindow(createLauncher(), true);
            segDataManager.showWindow();
        });
    }

    // -----------------------------------------------------------------------

    private static DataManagerWindow.AnalysisLauncher createLauncher() {
        return (exps, maxLag, interval, dm) -> {

            final SegModelConfig cfg = dm.getSegModelConfig();

            // ── Progress dialog (modal, dismissed when the worker finishes) ─
            JDialog prog = new JDialog(dm, "AnDi-J – Running Segmentation Predictions", true);
            JPanel pp = new JPanel(new BorderLayout(12, 12));
            pp.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));

            int nTracks = exps.stream()
                              .mapToInt(e -> e.tracks.size())
                              .sum();
            pp.add(new JLabel(
                "<html>Running segmentation predictions on <b>" + nTracks
                + "</b> track(s) across <b>" + exps.size()
                + "</b> experiment(s)…</html>"), BorderLayout.NORTH);

            final int totalTracks = Math.max(1, nTracks);
            JProgressBar bar = new JProgressBar(0, totalTracks);
            bar.setStringPainted(true);
            bar.setString("0 / " + nTracks);
            bar.setPreferredSize(new Dimension(320, 20));
            pp.add(bar, BorderLayout.CENTER);

            final boolean[] aborted = {false};
            JButton abortBtn = new JButton("Abort");
            abortBtn.setToolTipText("Stop the inference (results so far are discarded)");
            abortBtn.addActionListener(e -> {
                aborted[0] = true;
                abortBtn.setEnabled(false);
                abortBtn.setText("Aborting…");
            });
            JPanel abortRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
            abortRow.add(abortBtn);
            pp.add(abortRow, BorderLayout.SOUTH);

            prog.setContentPane(pp);
            prog.setResizable(false);
            prog.pack();
            prog.setLocationRelativeTo(dm);

            // ── Background worker ──────────────────────────────────────────
            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {

                private Exception error;

                private final int[] done = {0};

                @Override
                protected Void doInBackground() {
                    SegPredictor.ProgressListener tick = () -> {
                        if (aborted[0]) throw new java.util.concurrent.CancellationException();
                        int d = ++done[0];
                        SwingUtilities.invokeLater(() -> {
                            bar.setValue(d);
                            bar.setString(d + " / " + nTracks);
                        });
                    };

                    try {
                        // Serve cached experiments; run the model only for the rest.
                        List<ExperimentData> toCompute = new ArrayList<>();
                        for (ExperimentData exp : exps) {
                            Cached cached = CACHE.get(signature(exp, cfg));
                            if (cached != null) {
                                exp.stepAlpha = cached.stepAlpha;
                                exp.stepLogD  = cached.stepLogD;
                                exp.segments  = cached.segments;
                                for (int k = 0; k < exp.tracks.size(); k++) tick.trackDone();
                            } else {
                                toCompute.add(exp);
                            }
                        }
                        if (!toCompute.isEmpty()) {
                            SegmentationRunner.run(toCompute, cfg, tick);
                            for (ExperimentData exp : toCompute)
                                CACHE.put(signature(exp, cfg),
                                          new Cached(exp.stepAlpha, exp.stepLogD, exp.segments));
                        }
                    } catch (OrtException | java.io.IOException ex) {
                        error = ex;
                    } catch (java.util.concurrent.CancellationException ignored) {
                        // handled via the aborted[0] flag in done()
                    }
                    return null;
                }

                @Override
                protected void done() {
                    prog.dispose();   // close modal → control returns below

                    if (aborted[0]) {
                        IJ.log("AnDi-J: segmentation aborted by user.");
                        return;
                    }
                    if (error != null) {
                        JOptionPane.showMessageDialog(dm,
                            "<html>Could not run segmentation predictions:<br><br>"
                            + "<tt>" + error.getMessage().replace("\n", "<br>")
                            + "</tt></html>",
                            "AnDi-J – Segmentation Error",
                            JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    final List<ExperimentData> finalExps = exps;
                    SwingUtilities.invokeLater(() -> {
                        if (segWindow != null && segWindow.isDisplayable())
                            segWindow.dispose();
                        segWindow = new TrajectorySegmentationWindow(finalExps, dm);
                        segWindow.setVisible(true);
                    });
                }
            };

            worker.execute();
            prog.setVisible(true);   // blocks EDT via modal event loop until disposed
        };
    }
}
