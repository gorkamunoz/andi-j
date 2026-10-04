package sc.fiji.andi;

/**
 * One change-point segment of a trajectory's per-frame predictions.
 *
 * <p>Indices refer to positions in the per-frame prediction arrays
 * ({@link ExperimentData#stepAlpha} / {@link ExperimentData#stepLogD}),
 * which run 0 … (T−1) for a track of T frames.</p>
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
    /** Diffusive-state class over the segment (majority of per-frame states),
     *  index into {@link DiffusiveStatePanel#STATE_NAMES}; −1 if unavailable. */
    public final int state;

    public Segment(int start, int length, double alpha, double logD) {
        this(start, length, alpha, logD, -1);
    }

    public Segment(int start, int length, double alpha, double logD, int state) {
        this.start  = start;
        this.length = length;
        this.alpha  = alpha;
        this.logD   = logD;
        this.state  = state;
    }

    /** Linear diffusion coefficient D = 10^logD (NaN if logD is NaN). */
    public double d() { return Double.isNaN(logD) ? Double.NaN : Math.pow(10, logD); }
}
