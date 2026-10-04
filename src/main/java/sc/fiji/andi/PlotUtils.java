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
