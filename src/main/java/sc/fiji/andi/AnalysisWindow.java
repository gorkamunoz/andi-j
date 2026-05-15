package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Main analysis window – a JFrame with a tabbed pane.
 * Tabs: TMSD Visualization, Diffusion coefficient, Anomalous exponent, Full analysis (α vs D scatter).
 */
public class AnalysisWindow extends JFrame {

    public AnalysisWindow(List<ExperimentData> experiments,
                          int initMaxLag, double initInterval) {
        super("AnDi – Trajectory Analysis");

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("TMSD Visualization",
            new TmsdPanel(experiments, initMaxLag, initInterval));
        tabs.addTab("Diffusion coefficient",
            new DiffusionCoeffPanel(experiments, initInterval));
        tabs.addTab("Anomalous exponent",
            new AnomalousExponentPanel(experiments));
        tabs.addTab("Full analysis",
            new FullAnalysisPanel(experiments, initInterval));

        setContentPane(tabs);
        setSize(980, 660);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

}
