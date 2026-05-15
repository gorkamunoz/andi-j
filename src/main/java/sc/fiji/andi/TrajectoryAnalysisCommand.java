package sc.fiji.andi;

import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry-point plugin for multi-file trajectory analysis.
 * Opens a file chooser (multiple CSV files allowed), parses each one,
 * pre-computes tMSD with defaults, then opens the analysis window.
 *
 * Registered in plugins.config as: Plugins > AnDi > Trajectory Analysis
 */
public class TrajectoryAnalysisCommand implements PlugIn {

    /** Tab10 colour palette – one colour per loaded experiment. */
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

    @Override
    public void run(String arg) {

        // 1. Multi-file chooser (Swing JFileChooser supports multi-select)
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        chooser.setDialogTitle("Select Experiments CSV");
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;

        File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) return;

        // 2. Parse each file into an ExperimentData
        List<ExperimentData> experiments = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            try {
                TrajectoryIO.ParseResult parsed = TrajectoryIO.parseCsv(f);
                // Tag = filename without extension
                String name = f.getName().replaceFirst("\\.[^.]+$", "");
                Color  color = PALETTE[i % PALETTE.length];
                experiments.add(
                    new ExperimentData(name, parsed.tracks, color, parsed.estimatedInterval));
                IJ.log("AnDi: loaded \"" + name + "\" – " +
                        parsed.tracks.size() + " track(s)");
            } catch (IOException e) {
                IJ.error("AnDi – Load error",
                    "Could not load " + f.getName() + ":\n" + e.getMessage());
            }
        }
        if (experiments.isEmpty()) return;

        // 3. Pre-compute tMSD with sensible defaults so results appear immediately
        int maxLen = experiments.stream()
            .flatMap(e -> e.tracks.values().stream())
            .mapToInt(List::size).max().orElse(4);
        int    defaultMaxLag  = Math.max(1, maxLen / 4);
        double defaultInterval = experiments.get(0).estimatedInterval;

        // Default alpha lags: [1, 2, ..., min(20, defaultMaxLag)]
        int alphaMaxLag = Math.min(20, defaultMaxLag);
        int[] alphaLags = new int[alphaMaxLag];
        for (int i = 0; i < alphaMaxLag; i++) alphaLags[i] = i + 1;

        for (ExperimentData exp : experiments) {
            exp.computeTamsd(1, defaultMaxLag, 15, false, defaultInterval);
            exp.computeDiffusionCoefficients(new int[]{1, 2}, defaultInterval);
            exp.computeAnomalousExponents(alphaLags);
        }

        // 4. Open the analysis window on the Swing EDT
        final List<ExperimentData> exps     = experiments;
        final int    initMaxLag  = defaultMaxLag;
        final double initInterval = defaultInterval;
        SwingUtilities.invokeLater(() -> {
            AnalysisWindow win = new AnalysisWindow(exps, initMaxLag, initInterval);
            win.setVisible(true);
        });
    }
}
