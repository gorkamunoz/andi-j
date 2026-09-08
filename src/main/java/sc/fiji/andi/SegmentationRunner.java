package sc.fiji.andi;

import ai.onnxruntime.OrtException;

import java.io.IOException;
import java.util.*;

/**
 * Runs segmentation for a {@link SegModelConfig}, filling each experiment's
 * {@link ExperimentData#stepAlpha}, {@link ExperimentData#stepLogD} and
 * {@link ExperimentData#segments}.
 *
 * <ul>
 *   <li><b>Default</b> – the bundled batched {@link SegPredictor}.</li>
 *   <li><b>Combined</b> – one custom ONNX providing α, log₁₀(D) &amp; cp.</li>
 *   <li><b>Per-output</b> – a custom ONNX per output; missing outputs come from
 *       the default model (run per-track). Custom cp is treated as a 0/1 signal;
 *       the default cp as a probability (peak-picked).</li>
 * </ul>
 */
public final class SegmentationRunner {

    private SegmentationRunner() {}

    public static void run(List<ExperimentData> exps, SegModelConfig cfg,
                           SegPredictor.ProgressListener tick)
            throws OrtException, IOException {

        if (cfg.useDefault) {
            try (SegPredictor def = SegPredictor.fromResources()) {
                for (ExperimentData exp : exps) def.computeForExperiment(exp, tick);
            }
            return;
        }

        if (cfg.combined) {
            if (cfg.combinedFile == null)
                throw new IOException("Load / Combined selected but no model was chosen.");
            try (CustomSegPredictor m = new CustomSegPredictor(cfg.combinedFile, CustomSegPredictor.Kind.COMBINED)) {
                runMixed(exps, tick, m, null, null, null, null, null);
            }
            return;
        }

        // Per-output: default fills any output without a custom model.
        SegPredictor def = cfg.needsDefault() ? SegPredictor.fromResources() : null;
        CustomSegPredictor ca  = cfg.alphaFile  != null ? new CustomSegPredictor(cfg.alphaFile,  CustomSegPredictor.Kind.ALPHA)     : null;
        CustomSegPredictor cd  = cfg.dFile      != null ? new CustomSegPredictor(cfg.dFile,      CustomSegPredictor.Kind.LOGD)      : null;
        CustomSegPredictor cc  = cfg.cpFile     != null ? new CustomSegPredictor(cfg.cpFile,     CustomSegPredictor.Kind.CP)        : null;
        CustomSegPredictor cph = cfg.phenomFile != null ? new CustomSegPredictor(cfg.phenomFile, CustomSegPredictor.Kind.MODELTYPE) : null;
        try {
            runMixed(exps, tick, null, def, ca, cd, cc, cph);
        } finally {
            if (def != null) def.close();
            if (ca  != null) ca.close();
            if (cd  != null) cd.close();
            if (cc  != null) cc.close();
            if (cph != null) cph.close();
        }
    }

    // -----------------------------------------------------------------------

    private static void runMixed(List<ExperimentData> exps, SegPredictor.ProgressListener tick,
                                 CustomSegPredictor combined, SegPredictor def,
                                 CustomSegPredictor ca, CustomSegPredictor cd, CustomSegPredictor cc,
                                 CustomSegPredictor cph)
            throws OrtException {

        for (ExperimentData exp : exps) {
            Map<Integer, double[]>      outA = new LinkedHashMap<>();
            Map<Integer, double[]>      outD = new LinkedHashMap<>();
            Map<Integer, List<Segment>> outS = new LinkedHashMap<>();

            for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
                int id = e.getKey();
                List<double[]> spots = e.getValue();
                int T = spots.size();
                if (T < 2) {
                    outA.put(id, new double[0]);
                    outD.put(id, new double[0]);
                    outS.put(id, Collections.<Segment>emptyList());
                    if (tick != null) tick.trackDone();
                    continue;
                }

                double[][] xy = new double[T][2];
                for (int t = 0; t < T; t++) { xy[t][0] = spots.get(t)[1]; xy[t][1] = spots.get(t)[2]; }

                double[] a, d, cp;
                int[] state;
                if (combined != null) {
                    CustomSegPredictor.Out o = combined.run(xy);
                    a = o.alpha; d = o.logD; cp = o.cp; state = o.state;
                } else {
                    SegPredictor.TrackOut base = (def != null) ? def.computeTrack(spots) : null;
                    a = (ca != null) ? ca.run(xy).alpha : (base != null ? base.alpha  : null);
                    d = (cd != null) ? cd.run(xy).logD  : (base != null ? base.logD   : null);
                    cp = (cc != null) ? cc.run(xy).cp   : (base != null ? base.cpProb : null);
                    state = (cph != null) ? cph.run(xy).state : (base != null ? base.state : null);
                }

                // Defensive defaults for a mis-shaped custom output.
                if (a == null) { a = new double[T]; }
                if (d == null) { d = new double[T]; Arrays.fill(d, Double.NaN); }
                int n = Math.min(T, Math.min(a.length, d.length));
                a = crop(a, n);
                d = crop(d, n);

                int[] cps = (cp == null) ? new int[0] : SegPredictor.thresholdChangePoints(cp);
                cps = interior(cps, n);

                outA.put(id, a);
                outD.put(id, d);
                outS.put(id, SegPredictor.segmentsFrom(cps, a, d, state));
                if (tick != null) tick.trackDone();
            }

            exp.stepAlpha = outA;
            exp.stepLogD  = outD;
            exp.segments  = outS;
        }
    }

    private static double[] crop(double[] v, int n) {
        if (v.length == n) return v;
        double[] r = new double[n];
        System.arraycopy(v, 0, r, 0, Math.min(n, v.length));
        return r;
    }

    /** Keeps only change points strictly inside (0, n). */
    private static int[] interior(int[] cps, int n) {
        int c = 0;
        for (int v : cps) if (v > 0 && v < n) c++;
        int[] r = new int[c];
        int k = 0;
        for (int v : cps) if (v > 0 && v < n) r[k++] = v;
        return r;
    }
}
