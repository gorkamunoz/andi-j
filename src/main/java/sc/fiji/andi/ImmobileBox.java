package sc.fiji.andi;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The "Show immobile" checkbox of the segmentation plots.
 *
 * <p>While immobile data is hidden (box unticked and the plot has immobile segments)
 * the box has a soft amber highlight. After each plot update it first pulses for a few
 * cycles, then stays steady. Once the user ticks any of these boxes, none of them pulses
 * again for the rest of the Fiji session; the steady highlight remains.</p>
 */
final class ImmobileBox extends JCheckBox {

    private static final int   PULSE_MS    = 1500;   // one pulse
    private static final int   PULSES      = 3;      // pulses after each plot update
    private static final int   ALPHA_DIM   = 25;     // glow opacity range while pulsing
    private static final int   ALPHA_PEAK  = 75;
    private static final int   ALPHA_STEADY = 45;
    private static final Color GLOW        = new Color(255, 170, 0);

    /** Set once any box is ticked; stops the pulsing everywhere for the session. */
    private static boolean ticked;
    private static final Set<ImmobileBox> BOXES =
            Collections.newSetFromMap(new WeakHashMap<ImmobileBox, Boolean>());

    private boolean present;    // the plot has immobile data
    private long    pulseStart;
    private final Timer timer = new Timer(40, e -> tick());

    ImmobileBox() {
        super("Show immobile", false);
        setOpaque(false);
        setToolTipText("Include the immobile segments (or their frames) in the plot");
        BOXES.add(this);
        addActionListener(e -> {
            if (isSelected()) {
                ticked = true;
                for (ImmobileBox b : BOXES) b.timer.stop();
            }
            repaint();
        });
    }

    /** Called after each plot update: whether the plotted data has immobile segments. */
    void setImmobilePresent(boolean present) {
        this.present = present;
        if (present && !isSelected() && !ticked) {
            pulseStart = System.currentTimeMillis();
            timer.start();
        } else {
            timer.stop();
        }
        repaint();
    }

    private void tick() {
        if (System.currentTimeMillis() - pulseStart >= (long) PULSE_MS * PULSES) timer.stop();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (present && !isSelected()) {
            int alpha = ALPHA_STEADY;
            if (timer.isRunning()) {
                double phase = (System.currentTimeMillis() - pulseStart) % PULSE_MS / (double) PULSE_MS;
                double level = 0.5 - 0.5 * Math.cos(2 * Math.PI * phase);
                alpha = (int) (ALPHA_DIM + (ALPHA_PEAK - ALPHA_DIM) * level);
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), alpha));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
