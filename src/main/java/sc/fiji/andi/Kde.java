package sc.fiji.andi;

/** One-dimensional Gaussian kernel density estimate, as drawn in the marginal strips. */
final class Kde {

    private Kde() {}

    /** Density of {@code data} at each point of {@code eval}, with bandwidth {@code bw}. */
    static double[] gaussianKde1D(double[] data, double[] eval, double bw) {
        double[] result = new double[eval.length];
        if (data.length == 0 || bw <= 0) return result;
        double c = 1.0 / (data.length * bw * Math.sqrt(2.0 * Math.PI));
        for (int i = 0; i < eval.length; i++) {
            double s = 0;
            for (double d : data) { double u = (eval[i] - d) / bw; s += Math.exp(-0.5 * u * u); }
            result[i] = s * c;
        }
        return result;
    }

    /** Silverman's rule-of-thumb bandwidth, 1.06 σ n^(−1/5) (1 for fewer than 2 points or σ ≈ 0). */
    static double silvermanBw(double[] data) {
        if (data.length < 2) return 1.0;
        double mean = 0;
        for (double d : data) mean += d;
        mean /= data.length;
        double var = 0;
        for (double d : data) { double dd = d - mean; var += dd * dd; }
        double std = Math.sqrt(var / data.length);
        return std < 1e-10 ? 1.0 : 1.06 * std * Math.pow(data.length, -0.2);
    }
}
