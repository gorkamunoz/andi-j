package sc.fiji.andi;

import ij.IJ;
import org.apache.batik.dom.GenericDOMImplementation;
import org.apache.batik.svggen.SVGGraphics2D;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;

/**
 * Exports a plot to a vector <b>SVG</b> file.
 *
 * <p>Every plot in AnDi-J is custom-painted with plain Graphics2D primitives
 * (lines, rectangles, strings), so the export simply replays the component's
 * own painting into an SVG-recording {@link Graphics2D}: the result is true
 * vector art that can be edited in Illustrator or Inkscape, and no plot has to
 * know anything about SVG.</p>
 *
 * <p>Rendering goes through {@link Component#printAll(Graphics)} rather than
 * {@code paint}, because printing bypasses Swing's double buffering — painting
 * normally could route the drawing through an off-screen image and embed a
 * bitmap in the SVG instead of vector shapes.</p>
 *
 * <p>Batik is a {@code provided} dependency: Fiji already ships it, so nothing
 * is bundled. If it is somehow missing at runtime the user gets a plain message
 * rather than a stack trace.</p>
 */
final class SvgExport {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";

    /** Remembered across saves, like the model/CSV choosers. */
    private static File lastDir = null;

    private SvgExport() {}

    /**
     * A "Save plot" button that writes the plot — including any sub-plots it
     * contains — to an SVG file.
     *
     * <p>The plot is supplied lazily: several tabs build their toolbar before
     * the plot components exist, and the supplier is only read when the button
     * is actually clicked.</p>
     *
     * @param plot        yields the component to render; may be a single canvas
     *                    or a container holding several sub-plots
     * @param defaultName suggested file name, without the extension
     */
    static JButton button(java.util.function.Supplier<Component> plot, String defaultName) {
        JButton b = new JButton("Save plot");
        b.setToolTipText("Export this plot as an SVG vector image");
        b.addActionListener(e -> save(b, plot.get(), defaultName));
        return b;
    }

    /** Asks for a file and writes the plot to it. */
    static void save(Component parent, Component plot, String defaultName) {
        if (plot == null || plot.getWidth() <= 0 || plot.getHeight() <= 0) {
            JOptionPane.showMessageDialog(parent,
                "Nothing to save yet — the plot has not been drawn.",
                "Save plot", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("SVG images", "svg"));
        fc.setDialogTitle("Save plot as SVG");
        if (lastDir != null && lastDir.isDirectory()) fc.setCurrentDirectory(lastDir);
        fc.setSelectedFile(new File(defaultName + ".svg"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".svg"))
            f = new File(f.getParent(), f.getName() + ".svg");
        lastDir = f.getParentFile();

        try {
            write(plot, f);
            IJ.log("AnDi-J: plot saved → " + f.getAbsolutePath());
        } catch (NoClassDefFoundError err) {
            JOptionPane.showMessageDialog(parent,
                "SVG export needs the Batik library, which ships with Fiji but\n"
                + "could not be found in this installation.\n\n"
                + "Details: " + err.getMessage(),
                "Save plot – error", JOptionPane.ERROR_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                "Could not save the plot:\n" + ex.getMessage(),
                "Save plot – error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Replays the component's painting into an SVG document and writes it out. */
    private static void write(Component plot, File f) throws IOException {
        DOMImplementation dom = GenericDOMImplementation.getDOMImplementation();
        Document document = dom.createDocument(SVG_NS, "svg", null);

        SVGGraphics2D g2 = new SVGGraphics2D(document);
        g2.setSVGCanvasSize(new Dimension(plot.getWidth(), plot.getHeight()));

        // Plots assume they paint onto their own background; printAll() keeps
        // the drawing vector rather than blitting an off-screen buffer.
        plot.printAll(g2);

        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            g2.stream(out, true);   // true = use CSS style attributes
        }
    }
}
