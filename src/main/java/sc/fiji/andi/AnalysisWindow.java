package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Main analysis window – a JFrame with a tabbed pane.
 * Tabs: TMSD Visualization, Diffusion coefficient, Anomalous exponent,
 *       D vs α scatter, Turning Angles.
 *
 * A "Manage Data" button floats at the right end of the tab bar so it
 * sits on the same horizontal line as the tab headers.
 */
public class AnalysisWindow extends JFrame {

    public AnalysisWindow(List<ExperimentData> experiments,
                          int initMaxLag, double initInterval,
                          DataManagerWindow dataManager) {
        super("AnDi – Trajectory Analysis");

        final JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("TMSD Visualization",
            new TmsdPanel(experiments, initMaxLag, initInterval));
        tabs.addTab("Diffusion coefficient",
            new DiffusionCoeffPanel(experiments, initInterval));
        tabs.addTab("Anomalous exponent",
            new AnomalousExponentPanel(experiments));
        tabs.addTab("D vs α",
            new FullAnalysisPanel(experiments, initInterval));
        tabs.addTab("Turning Angles",
            new AnglesPanel(experiments));

        // ── "Manage Data" button overlaid on the tab bar ──────────────────
        final JButton manageBtn = new JButton("Manage Data ▶");
        manageBtn.setFont(manageBtn.getFont().deriveFont(11f));
        manageBtn.setToolTipText("Return to the data manager to load or re-tag files");
        manageBtn.addActionListener(e -> dataManager.showWindow());

        // A null-layout panel: tabs fill it completely, button floats on top
        // at the right edge of the tab bar. doLayout() keeps it there on resize.
        JPanel content = new JPanel(null) {
            @Override
            public void doLayout() {
                int w = getWidth(), h = getHeight();
                tabs.setBounds(0, 0, w, h);
                Dimension bs = manageBtn.getPreferredSize();
                // y = 3 puts the button vertically centred inside the typical tab-bar height
                manageBtn.setBounds(w - bs.width - 6, 3, bs.width, bs.height);
            }
        };
        content.add(tabs);
        content.add(manageBtn);
        // z-order 0 = front: button receives mouse events before the tabbedPane
        content.setComponentZOrder(manageBtn, 0);

        setContentPane(content);
        setSize(980, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }
}
