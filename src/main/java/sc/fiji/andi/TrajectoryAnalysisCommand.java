package sc.fiji.andi;

import ij.plugin.PlugIn;
import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Entry-point plugin for multi-file trajectory analysis.
 * Opens (or brings to front) the persistent DataManagerWindow.
 *
 * Registered in plugins.config as: Plugins > AnDi-J > Trajectory Analysis
 */
public class TrajectoryAnalysisCommand implements PlugIn {

    /** Tab10 colour palette – one colour per experiment tag. */
    static final Color[] PALETTE = {
        new Color( 31, 119, 180),
        new Color(255, 127,  14),
        new Color( 44, 160,  44),
        new Color(214,  39,  40),
        new Color(148, 103, 189),
        new Color(140,  86,  75),
        new Color(227, 119, 194),
        new Color(127, 127, 127),
        new Color(188, 189,  34),
        new Color( 23, 190, 207),
    };

    /** Singleton data-manager – persists for the lifetime of the ImageJ session. */
    private static DataManagerWindow dataManager;

    /** The currently open analysis window (may be null or disposed). */
    private static AnalysisWindow analysisWindow;

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> {
            if (dataManager == null || !dataManager.isDisplayable())
                dataManager = new DataManagerWindow(createLauncher());
            dataManager.showWindow();
        });
    }

    // -----------------------------------------------------------------------

    private static DataManagerWindow.AnalysisLauncher createLauncher() {
        return (exps, maxLag, spaceUnit, dm) -> {
            // Pre-compute tMSD, D and α with the tabs' initial field values
            // (min lag 1, max lag maxLag, linear lags, frame interval 1).
            TmsdPanel.compute(exps, 1, maxLag, false, 1.0);
            LagSpec dSpec     = DiffusionCoeffPanel.parseTLags(DiffusionCoeffPanel.DEFAULT_LAGS);
            LagSpec alphaSpec = DiffusionCoeffPanel.parseTLags(AnomalousExponentPanel.DEFAULT_LAGS);
            for (ExperimentData exp : exps) {
                exp.computeDiffusionCoefficients(dSpec, 1.0);
                exp.computeAnomalousExponents(alphaSpec);
            }

            // Open / refresh AnalysisWindow
            final List<ExperimentData> finalExps = exps;
            SwingUtilities.invokeLater(() -> {
                if (analysisWindow != null && analysisWindow.isDisplayable())
                    analysisWindow.dispose();
                analysisWindow = new AnalysisWindow(finalExps, maxLag, spaceUnit, dm);
                analysisWindow.setVisible(true);
            });
        };
    }
}
