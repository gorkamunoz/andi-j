package sc.fiji.andi;

import ai.onnxruntime.OrtException;

import java.io.IOException;
import java.util.*;

/**
 * Runs segmentation for a {@link SegModelConfig}, filling each experiment's
 * {@link ExperimentData#stepAlpha}, {@link ExperimentData#stepLogD},
 * {@link ExperimentData#stepState} and {@link ExperimentData#segments}.
 *
 * <ul>
 *   <li><b>Default</b> – the bundled {@link SegPredictor}.</li>
 *   <li><b>Combined</b> – one custom ONNX providing α, log₁₀(D), cp and the
 *       diffusive state (plus, optionally, seg_* segments).</li>
 *   <li><b>Per-output</b> – a custom ONNX per output; α, log₁₀(D) and cp without a
 *       custom model come from the bundled one.</li>
 * </ul>
 * Custom outputs are used verbatim: the α clip of the bundled model applies only
 * to outputs it computes itself.
 * Change points are the interior frames where cp ≥ 0.5. Trajectories run in
 * parallel, {@link SegPredictor#MAX_PARALLEL_RUNS} at a time.
 */
public final class SegmentationRunner {

    private SegmentationRunner() {}

    public static void run(List<ExperimentData> exps, SegModelConfig cfg,
                           MlPredictor.ProgressListener tick)
            throws OrtException, IOException {

        if (cfg.useDefault) {
            try (SegPredictor def = SegPredictor.fromResources(cfg.useModelSegments)) {
                for (ExperimentData exp : exps) def.computeForExperiment(exp, tick);
            }
            return;
        }

        if (cfg.combined) {
            if (cfg.combinedFile == null)
                throw new IOException("Load / Combined selected but no model was chosen.");
            try (CustomSegPredictor m = new CustomSegPredictor(
                    cfg.combinedFile, CustomSegPredictor.Kind.COMBINED, cfg.useModelSegments)) {
                runMixed(exps, tick, m, null, null, null, null, null);
            }
            return;
        }

        // Per-output: default fills any output without a custom model.
        SegPredictor def = cfg.needsDefault() ? SegPredictor.fromResources(false) : null;
        CustomSegPredictor ca  = cfg.alphaFile  != null ? new CustomSegPredictor(cfg.alphaFile,  CustomSegPredictor.Kind.ALPHA)     : null;
        CustomSegPredictor cd  = cfg.dFile      != null ? new CustomSegPredictor(cfg.dFile,      CustomSegPredictor.Kind.LOGD)      : null;
        CustomSegPredictor cc  = cfg.cpFile     != null ? new CustomSegPredictor(cfg.cpFile,     CustomSegPredictor.Kind.CP)        : null;
        CustomSegPredictor cph = cfg.stateFile != null ? new CustomSegPredictor(cfg.stateFile, CustomSegPredictor.Kind.STATE) : null;
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

    /** One track's results. */
    private static final class TrackResult {
        double[] alpha, logD;
        int[] state;
        List<Segment> segments;
    }

    private static void runMixed(List<ExperimentData> exps, MlPredictor.ProgressListener tick,
                                 CustomSegPredictor combined, SegPredictor def,
                                 CustomSegPredictor ca, CustomSegPredictor cd, CustomSegPredictor cc,
                                 CustomSegPredictor cph)
            throws OrtException {

        for (ExperimentData exp : exps) {
            Map<Integer, TrackResult> results = ParallelTracks.map(exp.tracks,
                    spots -> runTrack(spots, combined, def, ca, cd, cc, cph),
                    tick, SegPredictor.MAX_PARALLEL_RUNS);

            Map<Integer, double[]>      outA  = new LinkedHashMap<>();
            Map<Integer, double[]>      outD  = new LinkedHashMap<>();
            Map<Integer, int[]>         outSt = new LinkedHashMap<>();
            Map<Integer, List<Segment>> outS  = new LinkedHashMap<>();
            for (Map.Entry<Integer, TrackResult> e : results.entrySet()) {
                TrackResult r = e.getValue();
                outA.put(e.getKey(), r.alpha);
                outD.put(e.getKey(), r.logD);
                outSt.put(e.getKey(), r.state);
                outS.put(e.getKey(), r.segments);
            }

            exp.stepAlpha = outA;
            exp.stepLogD  = outD;
            exp.stepState = outSt;
            exp.segments  = outS;
        }
    }

    /** Runs the configured model(s) on one track. */
    private static TrackResult runTrack(List<double[]> spots, CustomSegPredictor combined,
                                        SegPredictor def, CustomSegPredictor ca,
                                        CustomSegPredictor cd, CustomSegPredictor cc,
                                        CustomSegPredictor cph) throws OrtException {
        TrackResult r = new TrackResult();
        int T = spots.size();
        if (T < 2) {
            r.alpha = new double[0];
            r.logD = new double[0];
            r.state = new int[0];
            r.segments = Collections.<Segment>emptyList();
            return r;
        }

        float[][] xy = OnnxModels.positions(spots);

        double[] a, d, cp;
        int[] state;
        List<Segment> modelSegs = null;
        if (combined != null) {
            CustomSegPredictor.Out o = combined.run(xy);
            a = o.alpha; d = o.logD; cp = o.cp; state = o.state;
            modelSegs = o.segments;
        } else {
            SegPredictor.TrackOut base = (def != null) ? def.computeTrack(spots) : null;
            a = (ca != null) ? ca.run(xy).alpha : (base != null ? base.alpha  : null);
            d = (cd != null) ? cd.run(xy).logD  : (base != null ? base.logD   : null);
            cp = (cc != null) ? cc.run(xy).cp   : (base != null ? base.cpProb : null);
            state = (cph != null) ? cph.run(xy).state : (base != null ? base.state : null);
            if (base != null) modelSegs = base.segments;
        }

        // Every per-frame output has length T (the predictors fail otherwise), and α,
        // log D and cp always come from a custom model or the bundled one.
        int n = T;
        int[] cps = interior(SegPredictor.thresholdChangePoints(cp), n);

        r.alpha = a;
        r.logD = d;
        r.state = state != null ? state : new int[n];
        r.segments = modelSegs != null ? modelSegs : SegPredictor.segmentsFrom(cps, a, d, state);
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
