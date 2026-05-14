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
    public final Map<Integer, double[]> tamsdPerTrack = new LinkedHashMap<>();
    public double[] avgTamsd;

    // -- computed D coefficients (null until computeDiffusionCoefficients() called) --
    // Maps trackId -> { D_2D, D_X, D_Y }
    public Map<Integer, double[]> dCoeffs;

    // -- display state (toggled by the UI buttons) --
    public boolean showAverage = true;
    public boolean showAll     = false;

    public ExperimentData(String name,
                          Map<Integer, List<double[]>> tracks,
                          Color color,
                          double estimatedInterval) {
        this.name              = name;
        this.tracks            = tracks;
        this.color             = color;
        this.estimatedInterval = estimatedInterval;
    }

    public boolean hasTamsd() { return lags != null && !tamsdPerTrack.isEmpty(); }
    public boolean hasD()     { return dCoeffs != null && !dCoeffs.isEmpty(); }

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

    // ------------------------------------------------------------------
    // tMSD computation
    // ------------------------------------------------------------------

    /**
     * (Re)computes tMSD for all tracks using the given lag parameters.
     *
     * @param minL          smallest frame lag
     * @param maxL          largest  frame lag
     * @param n             number of lag values
     * @param logSpaced     true = log-spaced lags, false = linear
     * @param frameInterval real time per frame (used only to build tAxis)
     */
    public void computeTamsd(int minL, int maxL, int n,
                             boolean logSpaced, double frameInterval) {
        this.lags = makeLags(minL, maxL, n, logSpaced);
        this.tAxis = new double[lags.length];
        for (int i = 0; i < lags.length; i++) tAxis[i] = lags[i] * frameInterval;

        tamsdPerTrack.clear();
        for (Map.Entry<Integer, List<double[]>> e : tracks.entrySet())
            tamsdPerTrack.put(e.getKey(), computeSingleTamsd(e.getValue(), lags));

        // Average across tracks at each lag (ignoring NaN)
        avgTamsd = new double[lags.length];
        Arrays.fill(avgTamsd, Double.NaN);
        for (int li = 0; li < lags.length; li++) {
            double sum = 0; int count = 0;
            for (double[] vals : tamsdPerTrack.values())
                if (!Double.isNaN(vals[li])) { sum += vals[li]; count++; }
            if (count > 0) avgTamsd[li] = sum / count;
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
     * Units: μm² / (real time unit given by frameInterval).
     *
     * @param tLags         frame lags to use (e.g. {1, 2})
     * @param frameInterval real time per frame
     */
    public void computeDiffusionCoefficients(int[] tLags, double frameInterval) {
        dCoeffs = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> e : tracks.entrySet())
            dCoeffs.put(e.getKey(), computeSingleD(e.getValue(), tLags, frameInterval));
    }

    /** Returns {D_2D, D_X, D_Y} for one trajectory. */
    private static double[] computeSingleD(List<double[]> spots, int[] tLags, double frameInterval) {
        Map<Integer, double[]> fm = new HashMap<>(spots.size() * 2);
        for (double[] s : spots) fm.put((int) s[0], new double[]{s[1], s[2]});

        double[] tamsd2D = new double[tLags.length];
        double[] tamsdX  = new double[tLags.length];
        double[] tamsdY  = new double[tLags.length];
        Arrays.fill(tamsd2D, Double.NaN);
        Arrays.fill(tamsdX,  Double.NaN);
        Arrays.fill(tamsdY,  Double.NaN);

        for (int li = 0; li < tLags.length; li++) {
            double s2D = 0, sX = 0, sY = 0; int n = 0;
            for (Map.Entry<Integer, double[]> e : fm.entrySet()) {
                double[] p2 = fm.get(e.getKey() + tLags[li]);
                if (p2 != null) {
                    double dx = p2[0] - e.getValue()[0];
                    double dy = p2[1] - e.getValue()[1];
                    s2D += dx*dx + dy*dy; sX += dx*dx; sY += dy*dy; n++;
                }
            }
            if (n > 0) { tamsd2D[li] = s2D/n; tamsdX[li] = sX/n; tamsdY[li] = sY/n; }
        }

        // slope of linear fit TAMSD vs t_lag (in frames), then D = slope / (2*ndim*interval)
        double slope2D = linearSlope(tLags, tamsd2D);
        double slopeX  = linearSlope(tLags, tamsdX);
        double slopeY  = linearSlope(tLags, tamsdY);
        double fi = frameInterval > 0 ? frameInterval : 1.0;
        return new double[]{
            slope2D / (4.0 * fi),   // 2D: ndim=2
            slopeX  / (2.0 * fi),   // X:  ndim=1
            slopeY  / (2.0 * fi)    // Y:  ndim=1
        };
    }

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

    /** Gap-aware tMSD for a single trajectory. */
    private static double[] computeSingleTamsd(List<double[]> spots, int[] lags) {
        Map<Integer, double[]> fm = new HashMap<>(spots.size() * 2);
        for (double[] s : spots) fm.put((int) s[0], new double[]{s[1], s[2]});

        double[] result = new double[lags.length];
        Arrays.fill(result, Double.NaN);
        for (int li = 0; li < lags.length; li++) {
            double sum = 0; int count = 0;
            for (Map.Entry<Integer, double[]> e : fm.entrySet()) {
                double[] p2 = fm.get(e.getKey() + lags[li]);
                if (p2 != null) {
                    double dx = p2[0] - e.getValue()[0];
                    double dy = p2[1] - e.getValue()[1];
                    sum += dx * dx + dy * dy;
                    count++;
                }
            }
            if (count > 0) result[li] = sum / count;
        }
        return result;
    }
}
