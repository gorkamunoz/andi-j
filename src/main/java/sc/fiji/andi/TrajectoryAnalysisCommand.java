package sc.fiji.andi;

import ij.IJ;
import ij.plugin.PlugIn;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
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

        // 1b. Ask for optional trajectory-length filter
        int trajLength = askTrajLength();  // -1 means "no filter"

        // 2. Parse each file into an ExperimentData
        List<ExperimentData> experiments = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            try {
                TrajectoryIO.ParseResult parsed = TrajectoryIO.parseCsv(f);
                // Apply traj_length filter if requested
                if (trajLength > 0) applyTrajLengthFilter(parsed.tracks, trajLength);
                if (parsed.tracks.isEmpty()) {
                    IJ.log("AnDi: skipping \"" + f.getName() +
                           "\" – no tracks survive the length filter (N=" + trajLength + ")");
                    continue;
                }
                // Tag = filename without extension
                String name  = f.getName().replaceFirst("\\.[^.]+$", "");
                Color  color = PALETTE[i % PALETTE.length];
                experiments.add(
                    new ExperimentData(name, parsed.tracks, color, parsed.estimatedInterval));
                IJ.log("AnDi: loaded \"" + name + "\" – " +
                        parsed.tracks.size() + " track(s)" +
                        (trajLength > 0 ? " (length-filtered to " + trajLength + ")" : ""));
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
        // If a traj_length filter was applied every track has exactly trajLength spots;
        // use max(X-2, 4) as the default max lag.  Otherwise fall back to maxLen/4.
        int defaultMaxLag = (trajLength > 0)
            ? Math.max(trajLength - 2, 4)
            : Math.max(1, maxLen / 4);
        double defaultInterval = experiments.get(0).estimatedInterval;

        // Default alpha lags: fractional [1, 0.1] (= [1 .. max(4, floor(0.1 * traj_len))])
        LagSpec alphaSpec = LagSpec.fractional(1, 0.1);

        for (ExperimentData exp : experiments) {
            exp.computeTamsd(1, defaultMaxLag, 15, false, defaultInterval);
            exp.computeDiffusionCoefficients(new int[]{1, 2}, defaultInterval);
            exp.computeAnomalousExponents(alphaSpec);
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

    // -----------------------------------------------------------------------
    // Trajectory-length helpers
    // -----------------------------------------------------------------------

    /**
     * Shows a small dialog asking for the trajectory-length filter value.
     * Returns the entered integer (≥ 1) if the user typed a number, or -1 if
     * the field was left blank / the user clicked Cancel.
     */
    private static int askTrajLength() {
        JTextField field = new JTextField(6);
        field.setToolTipText("Leave blank for no filtering");
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.add(new JLabel("Trajectory length N (blank = no filter):"));
        panel.add(field);

        int result = JOptionPane.showConfirmDialog(
            null, panel, "Set trajectory length",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return -1;
        String text = field.getText().trim();
        if (text.isEmpty()) return -1;
        try {
            int n = Integer.parseInt(text);
            return n >= 1 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Applies the trajectory-length filter in place:
     * – removes tracks that have fewer than {@code n} detected spots;
     * – truncates tracks that have more than {@code n} spots to the first
     *   {@code n} spots (sorted ascending by frame number).
     */
    private static void applyTrajLengthFilter(
            Map<Integer, List<double[]>> tracks, int n) {
        Iterator<Map.Entry<Integer, List<double[]>>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<double[]>> entry = it.next();
            List<double[]> spots = entry.getValue();
            if (spots.size() < n) {
                it.remove();
            } else if (spots.size() > n) {
                // Sort by frame (column 0), keep first n
                spots.sort(Comparator.comparingDouble(s -> s[0]));
                entry.setValue(new ArrayList<>(spots.subList(0, n)));
            }
        }
    }
}
