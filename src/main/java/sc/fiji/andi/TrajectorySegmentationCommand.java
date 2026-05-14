package sc.fiji.andi;

import ij.IJ;
import ij.plugin.PlugIn;

/**
 * Placeholder for the Trajectory Segmentation feature.
 * Registered in plugins.config as: Plugins > AnDi > Trajectory Segmentation
 */
public class TrajectorySegmentationCommand implements PlugIn {

    @Override
    public void run(String arg) {
        IJ.showMessage("AnDi – Trajectory Segmentation",
            "Trajectory Segmentation is not yet implemented.\n\nComing soon!");
    }
}
