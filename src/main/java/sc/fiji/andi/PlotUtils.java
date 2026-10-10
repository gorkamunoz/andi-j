package sc.fiji.andi;

/** Axis helpers shared by the plot panels. */
final class PlotUtils {

    private PlotUtils() {}

    /** A "nice" tick step (1, 2, 2.5 or 5 × 10ⁿ) giving about {@code target} ticks over {@code range}. */
    static double niceStep(double range, int target) {
        if (range <= 0) return 1;
        double rough = range / target;
        int exp = (int) Math.floor(Math.log10(rough));
        double base = Math.pow(10, exp);
        for (double m : new double[]{1, 2, 2.5, 5, 10})
            if (m * base >= rough) return m * base;
        return base * 10;
    }

    /**
     * Tick positions between {@code uMin} and {@code uMax} in axis coordinates: nice steps
     * on a linear axis (about {@code target} ticks); on a log axis, where the coordinate is
     * log₁₀ of the value, decades, or 1-2-5 × 10ⁿ when the range spans few decades.
     */
    static double[] axisTicks(double uMin, double uMax, boolean log, int target) {
        java.util.List<Double> t = new java.util.ArrayList<>();
        if (!log) {
            double step = niceStep(uMax - uMin, target);
            for (double v = Math.ceil(uMin / step) * step; v <= uMax + step * 0.01; v += step)
                t.add(v);
        } else {
            double decades = uMax - uMin;
            double[] mults = decades <= 2 ? new double[]{1, 2, 5} : new double[]{1};
            int every = decades > 8 ? (int) Math.ceil(decades / 6) : 1;
            for (int k = (int) Math.floor(uMin); k <= (int) Math.ceil(uMax); k++) {
                if (Math.floorMod(k, every) != 0) continue;
                for (double m : mults) {
                    double u = k + Math.log10(m);
                    if (u >= uMin - 1e-9 && u <= uMax + 1e-9) t.add(u);
                }
            }
        }
        double[] r = new double[t.size()];
        for (int i = 0; i < r.length; i++) r[i] = t.get(i);
        return r;
    }

    /** Tick label: plain notation for 0.001 ≤ |v| &lt; 10000, scientific otherwise. */
    static String fmtTick(double v) {
        return fmtTick(v, 0.001, 10000);
    }

    /** Tick label: plain notation for {@code minAbs} ≤ |v| &lt; {@code maxAbs}, scientific otherwise. */
    static String fmtTick(double v, double minAbs, double maxAbs) {
        if (v == 0) return "0";
        double abs = Math.abs(v);
        if (abs >= minAbs && abs < maxAbs)
            return String.format("%.4g", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return String.format("%.2e", v).replace("e+0", "e").replace("e-0", "e-").replace("e+", "e");
    }
}
