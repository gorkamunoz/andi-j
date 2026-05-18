package sc.fiji.andi;

import ij.plugin.PlugIn;
import javax.swing.*;
import java.awt.*;

/**
 * Entry-point plugin for multi-file trajectory analysis.
 * Opens (or brings to front) the persistent DataManagerWindow.
 *
 * Registered in plugins.config as: Plugins > AnDi > Trajectory Analysis
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

    /** Singleton window – persists for the lifetime of the ImageJ session. */
    private static DataManagerWindow dataManager;

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> {
            if (dataManager == null || !dataManager.isDisplayable())
                dataManager = new DataManagerWindow();
            dataManager.showWindow();
        });
    }
}
