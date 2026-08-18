package sc.fiji.andi;

import java.awt.Point;

/**
 * Reusable draggable-legend state for custom-painted plots.
 *
 * <p>A plot keeps one instance, calls {@link #layout} once per paint (passing
 * the default anchor and the legend box size) to obtain the top-left corner to
 * draw at, and forwards mouse events to {@link #press}/{@link #dragTo}/
 * {@link #release}. The legend can then be dragged anywhere inside the plot,
 * exactly like the D-vs-α scatter in Trajectory Analysis.</p>
 */
final class MovableLegend {

    private Integer offX = null, offY = null;   // user position (top-left); null = default anchor
    private int lx, ly, lw, lh;                 // bounds recorded at last layout (hit-testing)
    private boolean dragging;
    private int grabDX, grabDY;

    /**
     * Computes the legend's top-left for this paint — the user-dragged position
     * if set (clamped to the plot area), otherwise {@code (defaultX, defaultY)}
     * — and records the box bounds for hit-testing.
     */
    Point layout(int defaultX, int defaultY, int boxW, int boxH,
                 int px, int py, int pw, int ph) {
        int x = (offX == null) ? defaultX : Math.max(px, Math.min(offX, px + pw - boxW));
        int y = (offY == null) ? defaultY : Math.max(py, Math.min(offY, py + ph - boxH));
        lx = x; ly = y; lw = boxW; lh = boxH;
        return new Point(x, y);
    }

    /** Marks the legend as not present (no hit area) — call when there is nothing to draw. */
    void clear() { lw = 0; lh = 0; }

    /** True if (x, y) is inside the last-painted legend box. */
    boolean over(int x, int y) {
        return lw > 0 && x >= lx && x <= lx + lw && y >= ly && y <= ly + lh;
    }

    /** Starts dragging if (x, y) is over the legend; returns true if grabbed. */
    boolean press(int x, int y) {
        if (over(x, y)) { dragging = true; grabDX = x - lx; grabDY = y - ly; return true; }
        return false;
    }

    /** Updates the position while dragging; returns true if it consumed the event. */
    boolean dragTo(int x, int y) {
        if (!dragging) return false;
        offX = x - grabDX; offY = y - grabDY;
        return true;
    }

    void release() { dragging = false; }
    boolean isDragging() { return dragging; }
}
