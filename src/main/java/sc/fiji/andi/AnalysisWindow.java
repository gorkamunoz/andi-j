package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Main analysis window – a JFrame with a tabbed pane.
 * Only the "TMSD Visualization" tab is functional; the others are placeholders.
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
            makePlaceholder("Anomalous exponent analysis – coming soon."));
        tabs.addTab("Full analysis",
            makePlaceholder("Full analysis – coming soon."));

        setContentPane(tabs);
        setSize(980, 660);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

    private static JPanel makePlaceholder(String message) {
        JPanel p = new JPanel(new BorderLayout());
        JLabel lbl = new JLabel(message, SwingConstants.CENTER);
        lbl.setFont(lbl.getFont().deriveFont(Font.ITALIC, 15f));
        lbl.setForeground(new Color(150, 150, 150));
        p.add(lbl, BorderLayout.CENTER);
        return p;
    }
}
