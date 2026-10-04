package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Main window for the Trajectory Segmentation feature.
 * Opens as a JFrame with a tabbed pane.
 *
 * Current tabs:
 *   1. "Visualization" – per-frame α and log₁₀(D) time-series for 16 tracks
 *
 * A "Manage Data ▶" button floats on the tab bar (same pattern as
 * {@link AnalysisWindow}) so the user can return to load more files.
 */
public class TrajectorySegmentationWindow extends JFrame {

    public TrajectorySegmentationWindow(List<ExperimentData> experiments,
                                        DataManagerWindow    dataManager) {
        super("AnDi-J – Trajectory Segmentation");

        final JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Visualization",    new SegVisualizationPanel(experiments));
        tabs.addTab("D vs α",           new SegDvsAlphaPanel(experiments));
        tabs.addTab("Segment Stats",    new SegStatsPanel(experiments));
        tabs.addTab("Diffusive state",  new DiffusiveStatePanel(experiments));

        // ── "Manage Data" button overlaid on the tab bar ──────────────────
        final JButton manageBtn = new JButton("Manage Data ▶");
        manageBtn.setFont(manageBtn.getFont().deriveFont(11f));
        manageBtn.setToolTipText("Return to the data manager to load or re-tag files");
        manageBtn.addActionListener(e -> dataManager.showWindow());

        JPanel content = new JPanel(null) {
            @Override
            public void doLayout() {
                int w = getWidth(), h = getHeight();
                tabs.setBounds(0, 0, w, h);
                Dimension bs = manageBtn.getPreferredSize();
                manageBtn.setBounds(w - bs.width - 6, 3, bs.width, bs.height);
            }
        };
        content.add(tabs);
        content.add(manageBtn);
        content.setComponentZOrder(manageBtn, 0);

        setContentPane(content);
        setSize(980, 720);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }
}
