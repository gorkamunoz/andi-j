package sc.fiji.andi;

/**
 * Encodes a time-lag specification for tMSD fitting.
 *
 * Two flavours:
 *   fixed      – a fixed array of integer lags, the same for every trajectory.
 *   fractional – lags [minLag, minLag+1, …, floor(frac × traj_length)],
 *                computed per-trajectory so that short trajectories
 *                automatically get a shorter maximum lag.
 *
 * Created by {@link DiffusionCoeffPanel#parseTLags(String)}.
 * Used by {@link ExperimentData#computeDiffusionCoefficients(LagSpec, double)}
 * and     {@link ExperimentData#computeAnomalousExponents(LagSpec)}.
 */
public class LagSpec {

    public final boolean fractional;
    public final int     minLag;
    public final int[]   fixedLags;   // non-null when !fractional
    public final double  frac;        // in (0,1), used when fractional

    private LagSpec(int[] fixedLags) {
        this.fractional = false;
        this.minLag     = fixedLags.length > 0 ? fixedLags[0] : 1;
        this.fixedLags  = fixedLags;
        this.frac       = 0;
    }

    private LagSpec(int minLag, double frac) {
        this.fractional = true;
        this.minLag     = Math.max(1, minLag);
        this.frac       = frac;
        this.fixedLags  = null;
    }

    /** Fixed set of lags, identical for every trajectory. */
    public static LagSpec fixed(int[] lags) {
        return new LagSpec(lags);
    }

    /**
     * Per-trajectory fractional lag: lags are [minLag, …, floor(frac × traj_length)].
     * @param minLag  smallest lag (≥ 1)
     * @param frac    fraction of trajectory length used as the maximum lag (0 < frac < 1)
     */
    public static LagSpec fractional(int minLag, double frac) {
        return new LagSpec(minLag, frac);
    }

    /**
     * Returns the lag array to use for one trajectory of the given length
     * (number of detected spots, not frame span).
     */
    public int[] lagsFor(int trajLength) {
        if (!fractional) return fixedLags;
        // max(4, floor(frac × length)), but never less than minLag
        int maxLag = Math.max(minLag, Math.max(4, (int)(frac * trajLength)));
        int[] lags = new int[maxLag - minLag + 1];
        for (int i = 0; i < lags.length; i++) lags[i] = minLag + i;
        return lags;
    }

    @Override
    public String toString() {
        if (fractional) return "[" + minLag + ", " + frac + "]";
        if (fixedLags == null || fixedLags.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fixedLags.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(fixedLags[i]);
        }
        return sb.append(']').toString();
    }
}
