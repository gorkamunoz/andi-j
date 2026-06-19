package sc.fiji.andi;

/**
 * Tunable parameters for the change-point segmentation
 * ({@link ChangePointDetector}).  Defaults mirror the Python reference
 * ({@code ruptures.KernelCPD} with a linear kernel and {@code pen=1}).
 */
public class SegmentationParams {

    /**
     * Penalty added per change point.  Higher → fewer, longer segments;
     * lower → more, shorter segments.  (Same role as {@code pen} in ruptures.)
     */
    public double penalty = 1.0;

    /** Minimum number of frames a segment may contain. */
    public int minSize = 2;

    /**
     * Grid step for candidate change points (ruptures {@code jump}).
     * 1 = every frame is a candidate (most accurate); larger = faster, coarser.
     */
    public int jump = 1;

    /**
     * If true, rescale log₁₀(D) to [0, 1] using the experiment's D range before
     * stacking it with α for change-point detection (matches the reference).
     * If false, raw log₁₀(D) is used.
     */
    public boolean rescaleD = true;

    public SegmentationParams copy() {
        SegmentationParams p = new SegmentationParams();
        p.penalty  = penalty;
        p.minSize  = minSize;
        p.jump     = jump;
        p.rescaleD = rescaleD;
        return p;
    }
}
