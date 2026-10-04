package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Main analysis window – a JFrame with a tabbed pane.
 * Tabs: TMSD Visualization, Diffusion coefficient, Anomalous exponent,
 *       D vs α scatter, Diffusion Model, Turning Angles, PSD.
 *
 * A "Manage Data" button floats at the right end of the tab bar so it
 * sits on the same horizontal line as the tab headers.
 */
public class AnalysisWindow extends JFrame {

    public AnalysisWindow(List<ExperimentData> experiments,
                          int initMaxLag,
                          DataManagerWindow dataManager) {
        super("AnDi-J – Trajectory Analysis");

        // Build the panels that receive tagged populations first, so the
        // D vs α tab can push new populations to them via a callback.
        final DiffusionModelPanel    diffusionPanel = new DiffusionModelPanel(experiments);
        final AnglesPanel            anglesPanel     = new AnglesPanel(experiments);
        final AnomalousExponentPanel anomalousPanel  = new AnomalousExponentPanel(experiments);

        // The andi-j-analysis-latest model predicts α AND the diffusion model in
        // one pass, so running ML in either tab updates both. Sync the other
        // tab and inform the user (so the change isn't silent).
        anomalousPanel.setOnMlComputed(() -> {
            diffusionPanel.refreshBars();
            JOptionPane.showMessageDialog(this,
                "<html>ML prediction complete.<br><br>Both the <b>Anomalous exponent</b> "
              + "(ML) and <b>Diffusion Model</b> tabs were updated.</html>",
                "AnDi-J – ML prediction", JOptionPane.INFORMATION_MESSAGE);
        });
        diffusionPanel.setOnModelsComputed(() -> {
            anomalousPanel.syncToMlResults();
            JOptionPane.showMessageDialog(this,
                "<html>ML prediction complete.<br><br>Both the <b>Diffusion Model</b> and "
              + "<b>Anomalous exponent</b> (switched to ML) tabs were updated.</html>",
                "AnDi-J – ML prediction", JOptionPane.INFORMATION_MESSAGE);
        });

        // Sink: a population created in D vs α appears as a new dataset in the
        // Turning Angles and Diffusion Model tabs.
        final java.util.function.Consumer<ExperimentData> populationSink = pop -> {
            diffusionPanel.addPopulation(pop);
            anglesPanel.addPopulation(pop);
        };

        final JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("TMSD Visualization",
            new TmsdPanel(experiments, initMaxLag));
        tabs.addTab("Diffusion coefficient",
            new DiffusionCoeffPanel(experiments));
        tabs.addTab("Anomalous exponent", anomalousPanel);
        tabs.addTab("D vs α",
            new FullAnalysisPanel(experiments, populationSink));
        tabs.addTab("Diffusion Model", diffusionPanel);
        tabs.addTab("Turning Angles",  anglesPanel);
        tabs.addTab("PSD",             new PsdPanel(experiments));

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
