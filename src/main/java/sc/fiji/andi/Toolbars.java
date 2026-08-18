package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Small UI helper for tab toolbars.
 *
 * <p>{@link #makeScrollable(JComponent)} wraps a horizontal toolbar so it never
 * wraps its controls to a hidden second row when the window is narrow: the
 * content scrolls horizontally inside a viewport, flanked by ‹ / › buttons that
 * appear only when the toolbar is wider than the available width.</p>
 */
public final class Toolbars {

    private Toolbars() {}

    /** Amount (px) each ‹ / › click scrolls the toolbar. */
    private static final int STEP = 80;

    public static JComponent makeScrollable(final JComponent content) {
        final JViewport vp = new JViewport();
        vp.setView(content);
        vp.setOpaque(false);

        final JButton left  = arrowButton("‹");
        final JButton right = arrowButton("›");

        final Runnable clampAndArrows = new Runnable() {
            @Override public void run() {
                int maxX = Math.max(0, content.getPreferredSize().width - vp.getWidth());
                Point p = vp.getViewPosition();
                if (p.x > maxX) { p.x = maxX; vp.setViewPosition(p); }
                boolean overflow = maxX > 0;
                left.setVisible(overflow);
                right.setVisible(overflow);
            }
        };

        left.addActionListener(e -> {
            Point p = vp.getViewPosition();
            p.x = Math.max(0, p.x - STEP);
            vp.setViewPosition(p);
            clampAndArrows.run();
        });
        right.addActionListener(e -> {
            int maxX = Math.max(0, content.getPreferredSize().width - vp.getWidth());
            Point p = vp.getViewPosition();
            p.x = Math.min(maxX, p.x + STEP);
            vp.setViewPosition(p);
            clampAndArrows.run();
        });

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(left,  BorderLayout.WEST);
        wrap.add(vp,    BorderLayout.CENTER);
        wrap.add(right, BorderLayout.EAST);

        vp.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { clampAndArrows.run(); }
        });
        SwingUtilities.invokeLater(clampAndArrows);
        return wrap;
    }

    private static JButton arrowButton(String glyph) {
        JButton b = new JButton(glyph);
        b.setMargin(new Insets(0, 4, 0, 4));
        b.setFocusPainted(false);
        b.setToolTipText("Scroll the toolbar");
        return b;
    }
}
