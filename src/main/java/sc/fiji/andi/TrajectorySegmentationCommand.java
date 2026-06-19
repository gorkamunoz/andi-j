package sc.fiji.andi;

import ai.onnxruntime.OrtException;
import ij.plugin.PlugIn;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Entry-point plugin for the Trajectory Segmentation feature.
 * Opens (or brings to front) the persistent data-manager window configured
 * for segmentation:  on "Perform Analysis" it runs the STEP models and
 * opens {@link TrajectorySegmentationWindow}.
 *
 * Registered in plugins.config as: Plugins > AnDi > Trajectory Segmentation
 */
public class TrajectorySegmentationCommand implements PlugIn {

    /** Singleton data-manager – persists for the ImageJ session lifetime. */
    private static DataManagerWindow segDataManager;

    /** The currently open segmentation window (may be null or disposed). */
    private static JFrame segWindow;

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> {
            if (segDataManager == null || !segDataManager.isDisplayable())
                segDataManager = new DataManagerWindow(createLauncher());
            segDataManager.showWindow();
        });
    }

    // -----------------------------------------------------------------------

    private static DataManagerWindow.AnalysisLauncher createLauncher() {
        return (exps, maxLag, interval, dm) -> {

            // ── Progress dialog (modal, dismissed when the worker finishes) ─
            JDialog prog = new JDialog(dm, "AnDi – Running STEP Predictions", true);
            JPanel pp = new JPanel(new BorderLayout(12, 12));
            pp.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));

            int nTracks = exps.stream()
                              .mapToInt(e -> e.tracks.size())
                              .sum();
            pp.add(new JLabel(
                "<html>Running STEP predictions on <b>" + nTracks
                + "</b> track(s) across <b>" + exps.size()
                + "</b> experiment(s)…</html>"), BorderLayout.NORTH);

            final int totalTracks = Math.max(1, nTracks);
            JProgressBar bar = new JProgressBar(0, totalTracks);
            bar.setStringPainted(true);
            bar.setString("0 / " + nTracks);
            bar.setPreferredSize(new Dimension(320, 20));
            pp.add(bar, BorderLayout.CENTER);

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
                    try (StepPredictor predictor = StepPredictor.fromResources()) {
                        StepPredictor.ProgressListener tick = () -> {
                            int d = ++done[0];
                            SwingUtilities.invokeLater(() -> {
                                bar.setValue(d);
                                bar.setString(d + " / " + nTracks);
                            });
                        };
                        for (ExperimentData exp : exps)
                            predictor.computeForExperiment(exp, tick);
                    } catch (OrtException | java.io.IOException ex) {
                        error = ex;
                    }
                    return null;
                }

                @Override
                protected void done() {
                    prog.dispose();   // close modal → control returns below

                    if (error != null) {
                        JOptionPane.showMessageDialog(dm,
                            "<html>Could not run STEP predictions:<br><br>"
                            + "<tt>" + error.getMessage().replace("\n", "<br>")
                            + "</tt></html>",
                            "AnDi – STEP Error",
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
