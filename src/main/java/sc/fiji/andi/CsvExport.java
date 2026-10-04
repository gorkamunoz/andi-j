package sc.fiji.andi;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;

/**
 * Shared pieces of the CSV exports. Every per-track export starts with the columns
 * {@code experiment, track_id}; frame columns hold the real {@code FRAME} of the input.
 */
final class CsvExport {

    private CsvExport() {}

    /** Asks for a CSV file to write; adds {@code .csv} if missing. Null if cancelled. */
    static File chooseFile(Component parent, String title) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        fc.setDialogTitle(title);
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv"))
            f = new File(f.getParent(), f.getName() + ".csv");
        return f;
    }

    /** An experiment name as a CSV field (commas would split it, so they become semicolons). */
    static String name(ExperimentData exp) {
        return exp.name.replace(',', ';');
    }
}
