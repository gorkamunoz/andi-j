package sc.fiji.andi;

/**
 * One change-point segment of a trajectory's per-frame predictions.
 *
 * <p>Indices refer to positions in the per-frame STEP prediction arrays
 * ({@link ExperimentData#stepAlpha} / {@link ExperimentData#stepLogD}),
 * which run 0 … (T−2) for a track of T frames.</p>
 */
public class Segment {

    /** 0-based start index into the per-frame arrays. */
    public final int start;
    /** Number of frames in the segment. */
    public final int length;
    /** Mean predicted anomalous exponent α over the segment. */
    public final double alpha;
    /** Mean predicted log₁₀(D) over the segment (NaN if D unavailable). */
    public final double logD;

    public Segment(int start, int length, double alpha, double logD) {
        this.start  = start;
        this.length = length;
        this.alpha  = alpha;
        this.logD   = logD;
    }

    /** Linear diffusion coefficient D = 10^logD (NaN if logD is NaN). */
    public double d() { return Double.isNaN(logD) ? Double.NaN : Math.pow(10, logD); }
}
