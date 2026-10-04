package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Display units of one analysis window, shared by all its tabs.
 *
 * <p>Computations always run in the units of the input: positions as in the CSV
 * and time in frames. Each experiment carries its calibration
 * ({@link ExperimentData#spaceUm}: μm per position unit, {@link ExperimentData#frameUs}:
 * μs per frame; null when unknown). This class converts results to the units chosen
 * in the <b>Units</b> window. When the experiments have no calibration, the units are
 * fixed to px and frames.</p>
 */
final class Units {

    /** Time units, with their size in μs ({@link #FRAMES}: one frame). */
    enum Time {
        FRAMES("frames", Double.NaN), NS("ns", 1e-3), US("μs", 1), MS("ms", 1e3), S("s", 1e6);
        final String label; final double us;
        Time(String label, double us) { this.label = label; this.us = us; }
        @Override public String toString() { return label; }
    }

    /** Space units, with their size in μm ({@link #PX}: one position unit of the input). */
    enum Space {
        PX("px", Double.NaN), NM("nm", 1e-3), UM("μm", 1), MM("mm", 1e3), M("m", 1e6);
        final String label; final double um;
        Space(String label, double um) { this.label = label; this.um = um; }
        @Override public String toString() { return label; }
    }

    /** Whether every experiment has a spatial calibration / a frame interval. */
    final boolean spaceCalibrated, timeCalibrated;
    private Time  time;
    private Space space;
    private final List<Runnable> listeners = new ArrayList<>();

    /**
     * Units for {@code experiments}, which the Data Manager guarantees are either all
     * calibrated or all uncalibrated (separately for space and time).
     */
    Units(List<ExperimentData> experiments) {
        boolean sp = !experiments.isEmpty(), tm = !experiments.isEmpty();
        for (ExperimentData e : experiments) {
            sp &= e.spaceUm != null;
            tm &= e.frameUs != null;
        }
        spaceCalibrated = sp;
        timeCalibrated  = tm;
        space = sp ? Space.UM : Space.PX;
        time  = tm ? Time.S   : Time.FRAMES;
    }

    Time  time()  { return time; }
    Space space() { return space; }

    /** Calls {@code r} whenever the units change. */
    void addListener(Runnable r) { listeners.add(r); }

    /** Factor from the input's position unit of {@code e} to the displayed space unit. */
    double length(ExperimentData e) {
        return (space == Space.PX || e.spaceUm == null) ? 1.0 : e.spaceUm / space.um;
    }

    /** Factor from frames of {@code e} to the displayed time unit. */
    double time(ExperimentData e) {
        return (time == Time.FRAMES || e.frameUs == null) ? 1.0 : e.frameUs / time.us;
    }

    /** Factor from a diffusion coefficient in (position unit)²/frame to the displayed units. */
    double diffusion(ExperimentData e) {
        double l = length(e);
        return l * l / time(e);
    }

    /** Displayed unit of D, e.g. {@code μm²/s}. */
    String diffusionLabel() { return space.label + "²/" + timeLabel(); }

    /** Displayed unit of an MSD, e.g. {@code μm²}. */
    String areaLabel() { return space.label + "²"; }

    /** Displayed time unit; "frame" in the singular inside compound units. */
    String timeLabel() { return time == Time.FRAMES ? "frame" : time.label; }

    /** Displayed time unit as a standalone label, e.g. {@code frames} or {@code s}. */
    String timeAxisLabel() { return time.label; }

    /** ASCII version of a unit label, for CSV headers ({@code μ} → {@code u}, {@code ²} → {@code 2}). */
    static String ascii(String label) {
        return label.replace("μ", "u").replace("²", "2");
    }

    // -----------------------------------------------------------------------
    // Units window
    // -----------------------------------------------------------------------

    /** A "Units" button that opens the Units window of this window's tabs. */
    JButton button() {
        JButton b = new JButton("Units");
        b.setToolTipText("Choose the time and space units of the plots and exports");
        b.addActionListener(e -> showDialog(b));
        return b;
    }

    private void showDialog(Component parent) {
        JComboBox<Time> timeBox = new JComboBox<>(timeCalibrated
                ? Time.values() : new Time[]{Time.FRAMES});
        JComboBox<Space> spaceBox = new JComboBox<>(spaceCalibrated
                ? new Space[]{Space.NM, Space.UM, Space.MM, Space.M} : new Space[]{Space.PX});
        timeBox.setSelectedItem(time);
        spaceBox.setSelectedItem(space);
        timeBox.setEnabled(timeCalibrated);
        spaceBox.setEnabled(spaceCalibrated);

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; p.add(new JLabel("Time:"), c);
        c.gridx = 1;              p.add(timeBox, c);
        c.gridx = 0; c.gridy = 1; p.add(new JLabel("Space:"), c);
        c.gridx = 1;              p.add(spaceBox, c);
        if (!timeCalibrated || !spaceCalibrated) {
            JLabel msg = new JLabel("(For changing units, first set them in the Data Manager)");
            msg.setFont(msg.getFont().deriveFont(Font.ITALIC, 11f));
            msg.setForeground(new Color(110, 110, 110));
            c.gridx = 0; c.gridy = 2; c.gridwidth = 2; p.add(msg, c);
        }

        int ok = JOptionPane.showConfirmDialog(parent, p, "Units",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        Time  t = (Time)  timeBox.getSelectedItem();
        Space s = (Space) spaceBox.getSelectedItem();
        if (t == time && s == space) return;
        time = t; space = s;
        for (Runnable r : listeners) r.run();
    }
}
