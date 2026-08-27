package sc.fiji.andi;

import java.util.*;

/**
 * Change-point detection on the per-frame model predictions, producing one set
 * of {@link Segment}s per trajectory.
 *
 * <h3>Algorithm</h3>
 * Ports the Python reference (which used {@code ruptures.KernelCPD} with a
 * <em>linear</em> kernel).  For the linear kernel the cost of a segment equals
 * the within-segment sum of squared deviations from the mean, so the problem
 * reduces to a classic multivariate change-in-mean detection.  We solve it
 * exactly by dynamic programming with an added penalty per change point:
 *
 * <pre>
 *   minimise   Σ_segments cost(segment)  +  penalty · (#change points)
 *   cost([i,j)) = Σ_t ||v_t||²  −  ||Σ_t v_t||² / (j − i)
 * </pre>
 *
 * The signal {@code v_t} stacks the (optionally rescaled) log₁₀(D) and α
 * predictions, exactly as in the reference:
 * <pre>
 *   v_t = [ (logD_t − D_min)/(D_max − D_min) , α_t ]      (rescaleD = true)
 *   v_t = [ logD_t , α_t ]                                (rescaleD = false)
 * </pre>
 * Trajectories with no D prediction fall back to a 1-D signal of α alone.
 *
 * <p>{@code minSize} and {@code jump} constrain segment length and the grid of
 * candidate change points (same meaning as in ruptures).  Per-frame arrays are
 * short, so the O(n²) dynamic program is comfortably fast.</p>
 */
public final class ChangePointDetector {

    private ChangePointDetector() {}

    /**
     * Runs segmentation for every track in {@code exp} and stores the result
     * in {@link ExperimentData#segments}.
     */
    public static void computeForExperiment(ExperimentData exp, SegmentationParams p) {
        if (!exp.hasStepPredictions()) { exp.segments = new LinkedHashMap<>(); return; }

        // Global D range for rescaling (across all tracks of this experiment)
        double dMin = Double.MAX_VALUE, dMax = -Double.MAX_VALUE;
        if (exp.stepLogD != null)
            for (double[] ld : exp.stepLogD.values())
                if (ld != null)
                    for (double v : ld)
                        if (Double.isFinite(v)) { dMin = Math.min(dMin, v); dMax = Math.max(dMax, v); }
        boolean haveRange = dMax > dMin;

        Map<Integer, List<Segment>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, double[]> e : exp.stepAlpha.entrySet()) {
            int      id = e.getKey();
            double[] a  = e.getValue();
            double[] ld = exp.stepLogD != null ? exp.stepLogD.get(id) : null;
            if (a == null) { out.put(id, Collections.<Segment>emptyList()); continue; }
            int n = (ld != null) ? Math.min(a.length, ld.length) : a.length;
            out.put(id, segmentOne(a, ld, n, p, dMin, dMax, haveRange));
        }
        exp.segments = out;
    }

    // -----------------------------------------------------------------------

    static List<Segment> segmentOne(double[] a, double[] ld, int n,
                                    SegmentationParams p,
                                    double dMin, double dMax, boolean haveRange) {
        List<Segment> segs = new ArrayList<>();
        if (n <= 0) return segs;

        int     minSize  = Math.max(1, p.minSize);
        int     jump     = Math.max(1, p.jump);
        double  pen      = p.penalty;
        boolean has2d    = ld != null;
        boolean rescale  = has2d && haveRange && p.rescaleD;
        double  dRange   = dMax - dMin;
        int     dim      = has2d ? 2 : 1;

        // Build signal v[t][dim]
        double[][] v = new double[n][dim];
        for (int t = 0; t < n; t++) {
            if (has2d) {
                double df = rescale ? (ld[t] - dMin) / dRange : ld[t];
                v[t][0] = df;
                v[t][1] = a[t];
            } else {
                v[t][0] = a[t];
            }
        }

        // Prefix sums for O(1) segment cost
        double[][] s1 = new double[n + 1][dim];
        double[]   s2 = new double[n + 1];
        for (int k = 1; k <= n; k++) {
            double sq = 0;
            for (int d = 0; d < dim; d++) {
                s1[k][d] = s1[k - 1][d] + v[k - 1][d];
                sq += v[k - 1][d] * v[k - 1][d];
            }
            s2[k] = s2[k - 1] + sq;
        }

        int[] cuts = detectCuts(n, dim, s1, s2, minSize, jump, pen);

        // Build segments between consecutive cut positions
        for (int c = 0; c + 1 < cuts.length; c++) {
            int i = cuts[c], j = cuts[c + 1];
            double aMean = 0;
            for (int t = i; t < j; t++) aMean += a[t];
            aMean /= (j - i);
            double dMean = Double.NaN;
            if (has2d) {
                double s = 0; int cnt = 0;
                for (int t = i; t < j; t++) if (Double.isFinite(ld[t])) { s += ld[t]; cnt++; }
                dMean = cnt > 0 ? s / cnt : Double.NaN;
            }
            segs.add(new Segment(i, j - i, aMean, dMean));
        }
        return segs;
    }

    /**
     * Exact DP over admissible cut positions; returns the breakpoint list
     * including the endpoints 0 and n (so consecutive pairs are segments).
     */
    private static int[] detectCuts(int n, int dim, double[][] s1, double[] s2,
                                    int minSize, int jump, double pen) {
        if (n < 2 * minSize) return new int[]{0, n};

        // Candidate positions: multiples of jump, plus 0 and n
        TreeSet<Integer> posSet = new TreeSet<>();
        posSet.add(0);
        posSet.add(n);
        for (int k = 0; k <= n; k += jump) posSet.add(k);
        Integer[] pos = posSet.toArray(new Integer[0]);

        double[] F    = new double[n + 1];
        int[]    prev = new int[n + 1];
        Arrays.fill(F, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        F[0] = -pen;

        for (int bj = 0; bj < pos.length; bj++) {
            int j = pos[bj];
            if (j == 0) continue;
            for (int bi = 0; bi < bj; bi++) {
                int i = pos[bi];
                if (j - i < minSize) continue;
                if (F[i] == Double.POSITIVE_INFINITY) continue;
                double c = F[i] + cost(i, j, dim, s1, s2) + pen;
                if (c < F[j]) { F[j] = c; prev[j] = i; }
            }
        }

        if (F[n] == Double.POSITIVE_INFINITY || prev[n] < 0)
            return new int[]{0, n};   // fallback: single segment

        // Backtrack
        ArrayList<Integer> cuts = new ArrayList<>();
        int t = n;
        while (t > 0) { cuts.add(t); t = prev[t]; }
        cuts.add(0);
        Collections.reverse(cuts);
        int[] out = new int[cuts.size()];
        for (int k = 0; k < cuts.size(); k++) out[k] = cuts.get(k);
        return out;
    }

    /** Linear-kernel segment cost: Σ‖v‖² − ‖Σv‖² / (j−i). */
    private static double cost(int i, int j, int dim, double[][] s1, double[] s2) {
        double sq = s2[j] - s2[i];
        double t = 0;
        for (int d = 0; d < dim; d++) {
            double sd = s1[j][d] - s1[i][d];
            t += sd * sd;
        }
        return sq - t / (j - i);
    }
}
