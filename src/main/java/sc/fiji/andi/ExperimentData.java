package sc.fiji.andi;

import java.awt.Color;
import java.util.*;

/**
 * Holds one loaded CSV file: its trajectories, the colour assigned to it,
 * and the computed tMSD results.
 */
public class ExperimentData {

    // -- identity --
    public final String name;
    public final Map<Integer, List<double[]>> tracks; // trackId -> [{frame,x,y}]
    public final Color  color;
    public final double estimatedInterval;

    // -- computed tMSD (null until computeTamsd() is called) --
    public int[]    lags;
    public double[] tAxis;

    /** Per-component averages: [0]=2D, [1]=X, [2]=Y;  second dim = lag index. */
    public double[][]               avgTamsdComp;
    /** Per-track per-component tMSD: trackId → [comp][lagIdx],  comp 0=2D 1=X 2=Y. */
    public Map<Integer, double[][]> tamsdByComp = new LinkedHashMap<>();

    // -- computed D coefficients (null until computeDiffusionCoefficients() called) --
    // Maps trackId -> { D_2D, D_X, D_Y }
    public Map<Integer, double[]> dCoeffs;

    // -- computed alpha coefficients (null until computeAnomalousExponents() called) --
    // Maps trackId -> { alpha_2D, alpha_X, alpha_Y }
    public Map<Integer, double[]> alphaCoeffs;

    // -- per-frame predictions from andi-j-seg-latest (null until segmentation runs) --
    /** Per-frame anomalous exponent: trackId → double[T]. Null until segmentation runs. */
    public Map<Integer, double[]> stepAlpha;
    /** Per-frame log₁₀(D): trackId → double[T]. Null until segmentation runs. */
    public Map<Integer, double[]> stepLogD;
    /** Per-frame diffusive-state class: trackId → int[T]. Null until segmentation runs. */
    public Map<Integer, int[]> stepState;

    // -- ensemble MSD (null until computeEmsd() is called) --
    /**
     * Ensemble MSD per component: [0]=2D, [1]=X, [2]=Y; second dim = lag index.
     * eMSD(Δ) = mean over all tracks of |r(t₀+Δ) − r(t₀)|².
     */
    public double[][] emsdComp;

    // -- predicted diffusion-model class (null until an analysis model runs) --
    /**
     * Predicted diffusion model per track: trackId → model index into
     * {@link DiffusionModelPanel#MODEL_NAMES} (0=ATTM, 1=CTRW, 2=FBM, 3=LW, 4=SBM).
     * 2-D only.
     */
    public Map<Integer, Integer> diffModel;

    public boolean hasDiffModel() { return diffModel != null && !diffModel.isEmpty(); }

    // -- change-point segments (filled by the segmentation model) --
    /** Per-track list of segments from change-point detection on the per-frame predictions. */
    public Map<Integer, List<Segment>> segments;

    public boolean hasSegments() { return segments != null && !segments.isEmpty(); }

    public ExperimentData(String name,
                          Map<Integer, List<double[]>> tracks,
                          Color color,
                          double estimatedInterval) {
        this.name              = name;
        this.tracks            = tracks;
        this.color             = color;
        this.estimatedInterval = estimatedInterval;
    }

    public boolean hasTamsd()            { return lags != null && !tamsdByComp.isEmpty(); }
    public boolean hasEmsd()             { return emsdComp != null; }
    public boolean hasD()                { return dCoeffs     != null && !dCoeffs.isEmpty(); }
    public boolean hasAlpha()            { return alphaCoeffs != null && !alphaCoeffs.isEmpty(); }
    public boolean hasStepPredictions()  { return stepAlpha   != null && !stepAlpha.isEmpty(); }

    /** Average D for one component across all tracks. component: 0=2D 1=X 2=Y */
    public double avgD(int component) {
        if (!hasD()) return Double.NaN;
        double sum = 0; int n = 0;
        for (double[] d : dCoeffs.values()) {
            double v = d[component];
            if (Double.isFinite(v)) { sum += v; n++; }
        }
        return n > 0 ? sum / n : Double.NaN;
    }

    /** Average α for one component across all tracks. component: 0=2D 1=X 2=Y */
    public double avgAlpha(int component) {
        if (!hasAlpha()) return Double.NaN;
        double sum = 0; int n = 0;
        for (double[] a : alphaCoeffs.values()) {
            double v = a[component];
            if (Double.isFinite(v)) { sum += v; n++; }
        }
        return n > 0 ? sum / n : Double.NaN;
    }

    // ------------------------------------------------------------------
    // tMSD computation  (all three components computed together)
    // ------------------------------------------------------------------

    /**
     * (Re)computes tMSD for all tracks using the given lag parameters.
     * Stores results for all three components (2D, X, Y) in {@link #tamsdByComp}
     * and {@link #avgTamsdComp}.
     *
     * @param minL          smallest frame lag
     * @param maxL          largest  frame lag
     * @param n             number of lag values
     * @param logSpaced     true = log-spaced lags, false = linear
     * @param frameInterval real time per frame (used only to build tAxis)
     */
    public void computeTamsd(int minL, int maxL, int n,
                             boolean logSpaced, double frameInterval) {
        this.lags  = makeLags(minL, maxL, n, logSpaced);
        this.tAxis = new double[lags.length];
        for (int i = 0; i < lags.length; i++) tAxis[i] = lags[i] * frameInterval;

        tamsdByComp.clear();
        for (Map.Entry<Integer, List<double[]>> e : tracks.entrySet())
            tamsdByComp.put(e.getKey(), computeSingleTamsdAllComp(e.getValue(), lags));

        // Average across tracks per component
        avgTamsdComp = new double[3][lags.length];
        for (int c = 0; c < 3; c++) {
            Arrays.fill(avgTamsdComp[c], Double.NaN);
            for (int li = 0; li < lags.length; li++) {
                double sum = 0; int count = 0;
                for (double[][] vals : tamsdByComp.values())
                    if (!Double.isNaN(vals[c][li])) { sum += vals[c][li]; count++; }
                if (count > 0) avgTamsdComp[c][li] = sum / count;
            }
        }

        // Keep eMSD in sync with taMSD — always recompute with the new lags
        computeEmsd();
    }

    // ------------------------------------------------------------------
    // Ensemble MSD
    // ------------------------------------------------------------------

    /**
     * Computes the ensemble MSD for the lag array already stored in
     * {@link #lags} (call {@link #computeTamsd} first).
     *
     * <p>For each lag Δ:
     * <pre>  eMSD(Δ) = mean_{tracks} |r(t₀+Δ) − r(t₀)|²</pre>
     * where t₀ is the first observed frame of each track.
     * Tracks that do not have a localisation at t₀+Δ are excluded from
     * the average at that lag (gap-aware).
     *
     * <p>Results are stored in {@link #emsdComp}:
     * component 0 = 2D, 1 = X, 2 = Y.
     */
    public void computeEmsd() {
        if (lags == null) return;   // must call computeTamsd first
        int nLags = lags.length;

        double[] sum2D = new double[nLags];
        double[] sumX  = new double[nLags];
        double[] sumY  = new double[nLags];
        int[]    cnt   = new int[nLags];

        for (List<double[]> spots : tracks.values()) {
            if (spots.isEmpty()) continue;

            // Origin: position at the first observed frame
            double x0     = spots.get(0)[1];
            double y0     = spots.get(0)[2];
            int    frame0 = (int) spots.get(0)[0];

            // Build a frame → position map for gap-aware lookup
            Map<Integer, double[]> fm = new HashMap<>(spots.size() * 2);
            for (double[] s : spots) fm.put((int) s[0], new double[]{s[1], s[2]});

            for (int li = 0; li < nLags; li++) {
                double[] p = fm.get(frame0 + lags[li]);
                if (p == null) continue;
                double dx = p[0] - x0;
                double dy = p[1] - y0;
                sum2D[li] += dx * dx + dy * dy;
                sumX[li]  += dx * dx;
                sumY[li]  += dy * dy;
                cnt[li]++;
            }
        }

        emsdComp = new double[3][nLags];
        for (int li = 0; li < nLags; li++) {
            if (cnt[li] > 0) {
                emsdComp[0][li] = sum2D[li] / cnt[li];
                emsdComp[1][li] = sumX[li]  / cnt[li];
                emsdComp[2][li] = sumY[li]  / cnt[li];
            } else {
                emsdComp[0][li] = Double.NaN;
                emsdComp[1][li] = Double.NaN;
                emsdComp[2][li] = Double.NaN;
            }
        }
    }

    // ------------------------------------------------------------------
    // Diffusion coefficient  (mirrors andi_datasets.msd_analysis.get_diff_coeff)
    // ------------------------------------------------------------------

    /**
     * Computes the diffusion coefficient for every track by fitting a line to
     * the TAMSD vs t_lag curve (same algorithm as andi_datasets get_diff_coeff):
     *   slope = linear fit of TAMSD vs t_lag (in frames)
     *   D = slope / (2 * ndim * frameInterval)
     *
     * Stores results in {@link #dCoeffs}: trackId → {D_2D, D_X, D_Y}
     * Units: (position unit)² / (time unit given by frameInterval).
     *
     * @param lagSpec       frame lags to use, fixed or a fraction of each track's length
     * @param frameInterval real time per frame
     */
    public void computeDiffusionCoefficients(LagSpec lagSpec, double frameInterval) {
        dCoeffs = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> e : tracks.entrySet()) {
            int[] lags = lagSpec.lagsFor(e.getValue().size());
            dCoeffs.put(e.getKey(), computeSingleD(e.getValue(), lags, frameInterval));
        }
    }

    /** Returns {D_2D, D_X, D_Y} for one trajectory. */
    private static double[] computeSingleD(List<double[]> spots, int[] tLags, double frameInterval) {
        double[][] tamsd = computeSingleTamsdAllComp(spots, tLags);

        // slope of linear fit TAMSD vs t_lag (in frames), then D = slope / (2*ndim*interval)
        double slope2D = linearSlope(tLags, tamsd[0]);
        double slopeX  = linearSlope(tLags, tamsd[1]);
        double slopeY  = linearSlope(tLags, tamsd[2]);
        double fi = frameInterval > 0 ? frameInterval : 1.0;
        return new double[]{
            slope2D / (4.0 * fi),   // 2D: ndim=2
            slopeX  / (2.0 * fi),   // X:  ndim=1
            slopeY  / (2.0 * fi)    // Y:  ndim=1
        };
    }

    // ------------------------------------------------------------------
    // Anomalous exponent  (mirrors andi_datasets.analysis.msd_analysis.get_exponent)
    // ------------------------------------------------------------------

    /**
     * Computes the anomalous exponent for every track via a log-log linear fit of
     * the TAMSD curve (same algorithm as andi_datasets get_exponent):
     *   alpha = slope of log(TAMSD) vs log(t_lag)
     *
     * Stores results in {@link #alphaCoeffs}: trackId → {alpha_2D, alpha_X, alpha_Y}
     * α ∈ [0, 2]: 1 = normal diffusion, <1 = subdiffusion, >1 = superdiffusion.
     *
     * @param lagSpec frame lags to use, fixed or a fraction of each track's length
     */
    public void computeAnomalousExponents(LagSpec lagSpec) {
        alphaCoeffs = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> e : tracks.entrySet()) {
            int[] lags = lagSpec.lagsFor(e.getValue().size());
            alphaCoeffs.put(e.getKey(), computeSingleAlpha(e.getValue(), lags));
        }
    }

    /** Returns {alpha_2D, alpha_X, alpha_Y} for one trajectory. */
    private static double[] computeSingleAlpha(List<double[]> spots, int[] tLags) {
        double[][] tamsd = computeSingleTamsdAllComp(spots, tLags);
        return new double[]{
            logLogSlope(tLags, tamsd[0]),
            logLogSlope(tLags, tamsd[1]),
            logLogSlope(tLags, tamsd[2])
        };
    }

    /**
     * Returns the anomalous exponent estimated from a log-log fit of the
     * ensemble MSD curve for the given component (0=2D, 1=X, 2=Y).
     *
     * <p>For non-ergodic processes (e.g. CTRW) this gives a more accurate
     * estimate of α than averaging per-track taMSD exponents, because the
     * eMSD curve retains the correct anomalous scaling.</p>
     *
     * @return the fitted α, or {@code NaN} if eMSD is not available
     */
    public double avgAlphaFromEmsd(int component) {
        if (!hasEmsd() || lags == null) return Double.NaN;
        return logLogSlope(lags, emsdComp[component]);
    }

    /**
     * Slope of log-log linear fit: log(y) = alpha * log(x) + b.
     * Returns alpha (NaN if fewer than 2 valid strictly-positive points).
     */
    static double logLogSlope(int[] xs, double[] ys) {
        double sx = 0, sy = 0, sxy = 0, sx2 = 0; int n = 0;
        for (int i = 0; i < xs.length && i < ys.length; i++) {
            if (!Double.isFinite(ys[i]) || ys[i] <= 0 || xs[i] <= 0) continue;
            double lx = Math.log(xs[i]);
            double ly = Math.log(ys[i]);
            sx += lx; sy += ly; sxy += lx * ly; sx2 += lx * lx; n++;
        }
        if (n < 2) return Double.NaN;
        double det = n * sx2 - sx * sx;
        return Math.abs(det) < 1e-12 ? Double.NaN : (n * sxy - sx * sy) / det;
    }

    // ------------------------------------------------------------------

    /** Slope of least-squares linear fit y = a*x + b; returns a (NaN if < 2 valid points). */
    private static double linearSlope(int[] xs, double[] ys) {
        double sx = 0, sy = 0, sxy = 0, sx2 = 0; int n = 0;
        for (int i = 0; i < xs.length && i < ys.length; i++) {
            if (Double.isNaN(ys[i])) continue;
            sx += xs[i]; sy += ys[i]; sxy += (double)xs[i]*ys[i]; sx2 += (double)xs[i]*xs[i]; n++;
        }
        if (n < 2) return Double.NaN;
        double det = n * sx2 - sx * sx;
        return Math.abs(det) < 1e-12 ? Double.NaN : (n * sxy - sx * sy) / det;
    }

    // ------------------------------------------------------------------
    // Static helpers
    // ------------------------------------------------------------------

    public static int[] makeLags(int minL, int maxL, int n, boolean logSpaced) {
        minL = Math.max(1, minL);
        maxL = Math.max(minL, maxL);
        n    = Math.max(2, n);
        TreeSet<Integer> set = new TreeSet<>();
        if (logSpaced && maxL > minL) {
            double lo = Math.log(minL), hi = Math.log(maxL);
            double step = (hi - lo) / (n - 1);
            for (int i = 0; i < n; i++)
                set.add(Math.max(1, (int) Math.round(Math.exp(lo + i * step))));
        } else {
            double step = (double)(maxL - minL) / (n - 1);
            for (int i = 0; i < n; i++)
                set.add(Math.max(1, (int) Math.round(minL + i * step)));
        }
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Gap-aware tMSD for a single trajectory, all three components at once.
     * Returns [comp][lagIdx] where comp 0=2D, 1=X, 2=Y.
     */
    private static double[][] computeSingleTamsdAllComp(List<double[]> spots, int[] lags) {
        Map<Integer, double[]> fm = new HashMap<>(spots.size() * 2);
        for (double[] s : spots) fm.put((int) s[0], new double[]{s[1], s[2]});

        double[][] result = new double[3][lags.length];
        for (double[] r : result) Arrays.fill(r, Double.NaN);

        for (int li = 0; li < lags.length; li++) {
            double s2D = 0, sX = 0, sY = 0; int cnt = 0;
            for (Map.Entry<Integer, double[]> e : fm.entrySet()) {
                double[] p2 = fm.get(e.getKey() + lags[li]);
                if (p2 != null) {
                    double dx = p2[0] - e.getValue()[0];
                    double dy = p2[1] - e.getValue()[1];
                    s2D += dx*dx + dy*dy; sX += dx*dx; sY += dy*dy; cnt++;
                }
            }
            if (cnt > 0) { result[0][li] = s2D/cnt; result[1][li] = sX/cnt; result[2][li] = sY/cnt; }
        }
        return result;
    }
}
