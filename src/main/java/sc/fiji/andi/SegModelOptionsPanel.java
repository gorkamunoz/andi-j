package sc.fiji.andi;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;

/**
 * "ML model options" panel for the segmentation Data Manager.
 *
 * <p>Lets the user run the bundled <b>Default</b> model or <b>Load</b> custom
 * ONNX model(s) — either one <b>Combined</b> file (α, log₁₀(D) &amp; change
 * points) or one file <b>Per output</b>, where any output left unset falls back
 * to the default. See {@code Custom-Segmentation-Models.md} for the I/O contract.</p>
 */
public class SegModelOptionsPanel extends JPanel {

    private static File lastDir = null;

    private final JRadioButton defaultRadio = new JRadioButton("Default", true);
    private final JRadioButton loadRadio    = new JRadioButton("Load");
    private final JCheckBox    modelSegsBox = new JCheckBox("Model segments", true);

    private final JRadioButton combinedRadio  = new JRadioButton("Combined model", true);
    private final JRadioButton perOutputRadio = new JRadioButton("Per output");

    private final JButton chooseCombined = new JButton("Choose model…");
    private final JLabel  combinedLabel  = status("No model chosen");
    private File combinedFile;

    private final JButton chooseAlpha = new JButton("Choose…");
    private final JLabel  alphaLabel  = status("default");
    private File alphaFile;
    private final JButton chooseD = new JButton("Choose…");
    private final JLabel  dLabel   = status("default");
    private File dFile;
    private final JButton chooseCp = new JButton("Choose…");
    private final JLabel  cpLabel  = status("default");
    private File cpFile;
    private final JButton choosePhenom = new JButton("Choose…");
    private final JLabel  phenomLabel  = status("default");
    private File phenomFile;

    private final JPanel loadPanel;      // shown only when "Load" is selected
    private final JPanel combinedCard;
    private final JPanel perOutputCard;

    public SegModelOptionsPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("ML model options"));

        ButtonGroup src = new ButtonGroup();
        src.add(defaultRadio); src.add(loadRadio);

        modelSegsBox.setToolTipText("Use the model's own seg_* outputs for segment-level values");

        JPanel row1 = leftRow();
        row1.add(new JLabel("Model:"));
        row1.add(defaultRadio);
        row1.add(loadRadio);
        row1.add(Box.createHorizontalStrut(10));
        row1.add(modelSegsBox);
        row1.add(DiffusionCoeffPanel.makeHintLabel(
            "<html><b>Segment-level predictions</b><br>"
            + "Off: the plugin derives segments from the change-point<br>"
            + "output and summarises each one itself.<br>"
            + "On: the segment values the model reports in its<br>"
            + "seg_* outputs are used verbatim.</html>"));
        add(row1);

        ButtonGroup layout = new ButtonGroup();
        layout.add(combinedRadio); layout.add(perOutputRadio);

        JPanel modeRow = leftRow();
        modeRow.add(new JLabel("Load:"));
        modeRow.add(combinedRadio);
        modeRow.add(perOutputRadio);

        combinedCard = leftRow();
        combinedCard.add(chooseCombined);
        combinedCard.add(combinedLabel);

        perOutputCard = new JPanel();
        perOutputCard.setLayout(new BoxLayout(perOutputCard, BoxLayout.Y_AXIS));
        perOutputCard.add(outRow("α (exponent)",     chooseAlpha,  alphaLabel));
        perOutputCard.add(outRow("D (coefficient)",  chooseD,      dLabel));
        perOutputCard.add(outRow("Change points",    chooseCp,     cpLabel));
        perOutputCard.add(outRow("Diffusive state",   choosePhenom, phenomLabel));
        JLabel hint = new JLabel("Outputs left as “default” use the bundled model.");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 10f));
        hint.setForeground(new Color(120, 120, 120));
        JPanel hintRow = leftRow(); hintRow.add(hint);
        perOutputCard.add(hintRow);

        loadPanel = new JPanel();
        loadPanel.setLayout(new BoxLayout(loadPanel, BoxLayout.Y_AXIS));
        loadPanel.add(modeRow);
        loadPanel.add(combinedCard);
        loadPanel.add(perOutputCard);
        add(loadPanel);

        // ── wiring ────────────────────────────────────────────────────────
        defaultRadio.addActionListener(e -> refresh());
        loadRadio.addActionListener(e -> refresh());
        combinedRadio.addActionListener(e -> refresh());
        perOutputRadio.addActionListener(e -> refresh());

        chooseCombined.addActionListener(e -> {
            File f = pick("Choose combined segmentation model");
            if (f != null) { combinedFile = f; setFile(combinedLabel, f); }
        });
        chooseAlpha.addActionListener(e -> {
            File f = pick("Choose α (exponent) model");
            if (f != null) { alphaFile = f; setFile(alphaLabel, f); }
        });
        chooseD.addActionListener(e -> {
            File f = pick("Choose D (coefficient) model");
            if (f != null) { dFile = f; setFile(dLabel, f); }
        });
        chooseCp.addActionListener(e -> {
            File f = pick("Choose change-point model");
            if (f != null) { cpFile = f; setFile(cpLabel, f); }
        });
        choosePhenom.addActionListener(e -> {
            File f = pick("Choose diffusive-state model");
            if (f != null) { phenomFile = f; setFile(phenomLabel, f); }
        });

        refresh();
    }

    // -----------------------------------------------------------------------

    /** The current model selection. */
    public SegModelConfig getConfig() {
        boolean segs = modelSegsBox.isSelected();
        if (defaultRadio.isSelected()) return SegModelConfig.defaultModel(segs);
        if (combinedRadio.isSelected()) return SegModelConfig.combined(combinedFile, segs);
        return SegModelConfig.perOutput(alphaFile, dFile, cpFile, phenomFile);
    }

    // -----------------------------------------------------------------------

    private void refresh() {
        boolean load = loadRadio.isSelected();
        loadPanel.setVisible(load);
        if (load) {
            boolean comb = combinedRadio.isSelected();
            combinedCard.setVisible(comb);
            perOutputCard.setVisible(!comb);
        }
        revalidate();
        repaint();
    }

    private File pick(String title) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("ONNX models", "onnx"));
        fc.setDialogTitle(title);
        if (lastDir != null && lastDir.isDirectory()) fc.setCurrentDirectory(lastDir);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        File f = fc.getSelectedFile();
        lastDir = f.getParentFile();
        return f;
    }

    private void setFile(JLabel label, File f) {
        label.setText(truncate(f.getName(), 22));
        label.setToolTipText(f.getAbsolutePath());
        label.setForeground(new Color(0, 120, 0));
        refresh();
    }

    private static JPanel leftRow() { return new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1)); }

    private static JPanel outRow(String name, JButton btn, JLabel label) {
        JPanel row = leftRow();
        JLabel n = new JLabel(name + ":");
        n.setPreferredSize(new Dimension(110, n.getPreferredSize().height));
        row.add(n);
        row.add(btn);
        row.add(label);
        return row;
    }

    private static JLabel status(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(130, 130, 130));
        return l;
    }

    private static String truncate(String name, int max) {
        return name.length() <= max ? name : name.substring(0, Math.max(1, max - 1)) + "…";
    }
}
