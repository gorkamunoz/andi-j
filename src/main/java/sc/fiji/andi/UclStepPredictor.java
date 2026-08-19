package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.util.*;

/**
 * Runs the UCL-Net (UNet3+) segmentation model — exported to ONNX by
 * {@code convert_step_v0814_to_onnx.py} — to estimate, for every trajectory,
 * the per-frame anomalous exponent α, per-frame log₁₀(D), <em>and</em> the
 * change-point positions (from the model's own change-point head).
 *
 * <h3>Input preprocessing</h3>
 * The model consumes the <em>displacement</em> trajectory, not positions or
 * polar features:
 * <ol>
 *   <li>Δx[i] = x[i+1] − x[i], Δy[i] = y[i+1] − y[i]; the last step is padded
 *       with zeros so the displacement array keeps length T.</li>
 *   <li>The length is zero-padded up to the next multiple of {@value #LEN_MULTIPLE}
 *       (the network has 5 pooling levels, so it only accepts lengths divisible
 *       by 32); the extra steps are cropped off the outputs.</li>
 *   <li>The tensor is laid out channel-first: {@code (B, 2, Tpad)} with
 *       channel 0 = Δx, channel 1 = Δy.</li>
 * </ol>
 * The exported model is the plain backbone (dynamic batch and length), so many
 * trajectories run in one call: they are grouped by padded length and batched.
 * Test-time augmentation is optional ({@code useTta}); when on, each trajectory's
 * 8 rotated/flipped views are added as extra batch rows and their outputs
 * averaged, reproducing {@code predict_trajectory(use_tta=True)}; when off, a
 * single view runs (≈8× faster).
 *
 * <h3>Output</h3>
 * The model returns per-step {@code cp} (change-point probability), {@code kac}
 * = [α, D, state] and {@code model_type}. For a track with T frames we keep the
 * first T steps and store α in {@link ExperimentData#stepAlpha}, log₁₀(D) in
 * {@link ExperimentData#stepLogD}, and the derived {@link Segment}s in
 * {@link ExperimentData#segments}. Change points are peaks of the cp signal
 * (height ≥ {@value #CP_MIN_HEIGHT}, ≥ {@value #CP_DISTANCE} apart, excluding
 * the first/last two frames), matching the Python {@code find_changepoints}.
 */
public class UclStepPredictor implements Closeable {

    /** Classpath resource for the bundled ONNX model. */
    public static final String DEFAULT_RESOURCE = "/models/ucl_step.onnx";

    /** Required divisor of the input sequence length (5 pooling levels → 2^5). */
    private static final int LEN_MULTIPLE = 32;

    /** Change-point peak-detection parameters (mirror the Python reference). */
    private static final double CP_MIN_HEIGHT = 0.25;
    private static final int    CP_DISTANCE   = 2;

    /**
     * Output bounds. α is clipped to [0, {@value #ALPHA_MAX}] exactly like
     * predict_trajectory(). For D we keep the reference's [D_MIN, D_MAX] window
     * but treat the D_MIN floor as "undefined" (NaN) rather than log₁₀(1e-12) =
     * −12 — see {@link #computeForExperiment}.
     */
    private static final double ALPHA_MAX = 1.999;
    private static final double D_MIN     = 1e-12;
    private static final double D_MAX     = 1e6;

    /**
     * The 8 test-time-augmentation views as (cos, sin, x-flip) of the 90° symmetry
     * group — 4 rotations × 2 flips. Each view maps a displacement (Δx, Δy) to
     * {@code (Δx·c − Δy·s)·sx , Δx·s + Δy·c}, matching predict_trajectory().
     */
    private static final double[][] TTA_VIEWS = {
        { 1,  0,  1}, { 1,  0, -1},
        { 0,  1,  1}, { 0,  1, -1},
        {-1,  0,  1}, {-1,  0, -1},
        { 0, -1,  1}, { 0, -1, -1},
    };

    /** Batching caps so one inference call stays a reasonable size. */
    private static final int MAX_BATCH          = 64;       // trajectories per call
    private static final int MAX_BATCH_ELEMENTS = 400_000;  // rows × Tpad per call

    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;
    private final String         cpName;
    private final String         kacName;
    private final String         mtName;    // model_type head (may be null)
    /** Whether to average the 8 TTA views (higher accuracy, ~8× slower). */
    private final boolean        useTta;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public static UclStepPredictor fromResources(boolean useTta)
            throws OrtException, IOException {
        return new UclStepPredictor(loadResource(DEFAULT_RESOURCE), useTta);
    }

    public UclStepPredictor(File modelFile, boolean useTta)
            throws OrtException, IOException {
        this(readFile(modelFile), useTta);
    }

    public UclStepPredictor(byte[] modelBytes, boolean useTta) throws OrtException {
        this.useTta = useTta;
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelBytes, OnnxSessions.gpuIfAvailable());
        inputName = session.getInputNames().iterator().next();

        // Outputs are exported in the order: cp, kac, model_type.
        List<String> outs = new ArrayList<>(session.getOutputNames());
        cpName  = pickOutput(outs, "cp",  0);
        kacName = pickOutput(outs, "kac", 1);
        mtName  = (outs.size() >= 3) ? pickOutput(outs, "model_type", 2) : null;
        printInfo();
    }

    private static String pickOutput(List<String> outs, String preferred, int fallbackIdx) {
        for (String o : outs) if (o.equalsIgnoreCase(preferred)) return o;
        return outs.get(Math.min(fallbackIdx, outs.size() - 1));
    }


    // -----------------------------------------------------------------------
    // Public inference
    // -----------------------------------------------------------------------

    public void computeForExperiment(ExperimentData exp) throws OrtException {
        computeForExperiment(exp, null);
    }

    /** Notified once after each track is processed (for progress reporting). */
    public interface ProgressListener { void trackDone(); }

    /** One eligible track: its id, real length T, and (T−1) displacement pairs. */
    private static final class Track {
        final int id, T;
        final double[] dx, dy;   // length T−1
        Track(int id, int T, double[] dx, double[] dy) { this.id = id; this.T = T; this.dx = dx; this.dy = dy; }
    }

    /**
     * Runs the model on every track of {@code exp} and fills
     * {@link ExperimentData#stepAlpha}, {@link ExperimentData#stepLogD} and
     * {@link ExperimentData#segments}. Tracks with fewer than 2 frames get
     * empty arrays and no segments.
     *
     * <p>Trajectories are zero-padded to a multiple of 32 and grouped by padded
     * length so many run in a single batched inference call. When TTA is enabled
     * each trajectory contributes 8 rotated views (extra batch rows) whose
     * outputs are averaged, reproducing predict_trajectory(use_tta=True).</p>
     */
    public void computeForExperiment(ExperimentData exp, ProgressListener listener)
            throws OrtException {
        Map<Integer, double[]>        outAlpha  = new LinkedHashMap<>();
        Map<Integer, double[]>        outLogD   = new LinkedHashMap<>();
        Map<Integer, List<Segment>>   outSegs   = new LinkedHashMap<>();
        Map<Integer, Integer>         outPhenom = new LinkedHashMap<>();

        // Preprocess: short tracks get empty output; the rest become displacement
        // pairs bucketed by padded length so each batch tensor is rectangular.
        Map<Integer, List<Track>> byPad = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
            int            id    = e.getKey();
            List<double[]> spots = e.getValue();
            int T = spots.size();
            if (T < 2) {
                outAlpha.put(id, new double[0]);
                outLogD.put(id,  new double[0]);
                outSegs.put(id,  Collections.<Segment>emptyList());
                outPhenom.put(id, -1);
                if (listener != null) listener.trackDone();
                continue;
            }
            double[] dx = new double[T - 1], dy = new double[T - 1];
            for (int i = 0; i < T - 1; i++) {
                dx[i] = spots.get(i + 1)[1] - spots.get(i)[1];
                dy[i] = spots.get(i + 1)[2] - spots.get(i)[2];
            }
            byPad.computeIfAbsent(padLength(T), k -> new ArrayList<>())
                 .add(new Track(id, T, dx, dy));
        }

        int views = useTta ? TTA_VIEWS.length : 1;

        for (Map.Entry<Integer, List<Track>> bucket : byPad.entrySet()) {
            int         Tpad   = bucket.getKey();
            List<Track> tracks = bucket.getValue();

            // Trajectories per call: cap by both the count and rows×Tpad elements.
            int maxTraj = Math.max(1, Math.min(MAX_BATCH,
                    MAX_BATCH_ELEMENTS / Math.max(1, views * Tpad)));

            for (int off = 0; off < tracks.size(); off += maxTraj) {
                int nb   = Math.min(maxTraj, tracks.size() - off);
                int rows = nb * views;

                // Build (rows, 2, Tpad): row r = trajectory j × view v.
                float[][][] in = new float[rows][2][Tpad];
                for (int j = 0; j < nb; j++) {
                    Track tr = tracks.get(off + j);
                    for (int v = 0; v < views; v++) {
                        double[] w = useTta ? TTA_VIEWS[v] : null;
                        float[] cx = in[j * views + v][0];
                        float[] cy = in[j * views + v][1];
                        for (int i = 0; i < tr.T - 1; i++) {
                            double ddx = tr.dx[i], ddy = tr.dy[i];
                            if (w == null) { cx[i] = (float) ddx; cy[i] = (float) ddy; }
                            else {
                                cx[i] = (float) ((ddx * w[0] - ddy * w[1]) * w[2]);
                                cy[i] = (float) ( ddx * w[1] + ddy * w[0]);
                            }
                        }
                    }
                }

                float[][][] cp  = new float[rows][][];
                float[][][] kac = new float[rows][][];
                float[][][] mt  = (mtName != null) ? new float[rows][][] : null;
                runBatch(in, cp, kac, mt);

                for (int j = 0; j < nb; j++) {
                    Track tr = tracks.get(off + j);
                    int   T  = tr.T;
                    double[] alpha = new double[T], logD = new double[T], cpProb = new double[T];
                    for (int t = 0; t < T; t++) {
                        double cpv = 0, av = 0, dv = 0;
                        for (int v = 0; v < views; v++) {
                            int r = j * views + v;
                            cpv += cp[r][t][0];
                            av  += kac[r][t][0];
                            dv  += kac[r][t][1];
                        }
                        cpv /= views; av /= views; dv /= views;

                        alpha[t]  = clamp(av, 0.0, ALPHA_MAX);
                        // The model's D head is a raw, unbounded linear output that
                        // legitimately dips to ≤ 0 at (near-)immobile / uncertain
                        // points. The Python reference clips D to [1e-12, 1e6] and
                        // shows it on a *linear* axis, where ≈0 is invisible; taking
                        // log₁₀ of that floor would paint spurious −12 spikes. We keep
                        // every physically meaningful D (log₁₀ of a positive value)
                        // but mark non-positive D as undefined (NaN), so it is neither
                        // plotted nor averaged into a segment's mean log₁₀(D).
                        logD[t]   = (Double.isFinite(dv) && dv > D_MIN)
                                  ? Math.log10(Math.min(dv, D_MAX)) : Double.NaN;
                        cpProb[t] = cpv;
                    }
                    outAlpha.put(tr.id, alpha);
                    outLogD.put(tr.id,  logD);
                    outSegs.put(tr.id,  buildSegments(cpProb, alpha, logD));
                    outPhenom.put(tr.id, (mt != null)
                            ? phenomClass(mt, j * views, views, T) : -1);
                    if (listener != null) listener.trackDone();
                }
            }
        }

        exp.stepAlpha   = outAlpha;
        exp.stepLogD    = outLogD;
        exp.segments    = outSegs;
        exp.phenomModel = outPhenom;
    }

    /**
     * Aggregates the per-frame model-type softmax over frames and TTA views into
     * one class index (argmax of the summed probabilities), as the Python
     * reference does ({@code model_type_prob.mean(axis=0).argmax()}).
     *
     * @param mt      model-type output {@code [rows][Tpad][K]}
     * @param row0    first row for this track (view 0)
     * @param views   number of TTA views for this track
     * @param T       real (unpadded) track length
     */
    static int phenomClass(float[][][] mt, int row0, int views, int T) {
        if (mt[row0] == null || mt[row0].length == 0) return -1;
        int K = mt[row0][0].length;
        double[] acc = new double[K];
        for (int t = 0; t < T; t++)
            for (int v = 0; v < views; v++) {
                float[] row = mt[row0 + v][t];
                for (int k = 0; k < K; k++) acc[k] += row[k];
            }
        return argmax(acc);
    }

    static int argmax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return a.length == 0 ? -1 : best;
    }

    /** Per-frame default-model outputs for a single track (cropped to its length T). */
    public static final class TrackOut {
        public double[] alpha, logD, cpProb;
        public int      phenom = -1;   // aggregated phenomenological-model class (−1 = none)
    }

    /**
     * Runs the default model on one track and returns its per-frame α, log₁₀(D)
     * and change-point probability. Used by {@link SegmentationRunner} to fill
     * outputs the user did not supply a custom model for.
     */
    public TrackOut computeTrack(List<double[]> spots) throws OrtException {
        TrackOut o = new TrackOut();
        int T = spots.size();
        if (T < 2) { o.alpha = new double[0]; o.logD = new double[0]; o.cpProb = new double[0]; return o; }

        double[] dx = new double[T - 1], dy = new double[T - 1];
        for (int i = 0; i < T - 1; i++) {
            dx[i] = spots.get(i + 1)[1] - spots.get(i)[1];
            dy[i] = spots.get(i + 1)[2] - spots.get(i)[2];
        }
        int Tpad = padLength(T);
        int views = useTta ? TTA_VIEWS.length : 1;
        float[][][] in = new float[views][2][Tpad];
        for (int v = 0; v < views; v++) {
            double[] w = useTta ? TTA_VIEWS[v] : null;
            float[] cx = in[v][0], cy = in[v][1];
            for (int i = 0; i < T - 1; i++) {
                double ddx = dx[i], ddy = dy[i];
                if (w == null) { cx[i] = (float) ddx; cy[i] = (float) ddy; }
                else {
                    cx[i] = (float) ((ddx * w[0] - ddy * w[1]) * w[2]);
                    cy[i] = (float) ( ddx * w[1] + ddy * w[0]);
                }
            }
        }
        float[][][] cp = new float[views][][], kac = new float[views][][];
        float[][][] mt = (mtName != null) ? new float[views][][] : null;
        runBatch(in, cp, kac, mt);

        double[] alpha = new double[T], logD = new double[T], cpProb = new double[T];
        for (int t = 0; t < T; t++) {
            double cpv = 0, av = 0, dv = 0;
            for (int v = 0; v < views; v++) { cpv += cp[v][t][0]; av += kac[v][t][0]; dv += kac[v][t][1]; }
            cpv /= views; av /= views; dv /= views;
            alpha[t]  = clamp(av, 0.0, ALPHA_MAX);
            logD[t]   = (Double.isFinite(dv) && dv > D_MIN) ? Math.log10(Math.min(dv, D_MAX)) : Double.NaN;
            cpProb[t] = cpv;
        }
        o.alpha = alpha; o.logD = logD; o.cpProb = cpProb;
        o.phenom = (mt != null) ? phenomClass(mt, 0, views, T) : -1;
        return o;
    }

    // -----------------------------------------------------------------------
    // Change-point → segments
    // -----------------------------------------------------------------------

    /** Builds segments by peak-picking the default model's cp probability. */
    static List<Segment> buildSegments(double[] cpProb, double[] alpha, double[] logD) {
        return segmentsFrom(findChangePoints(cpProb), alpha, logD);
    }

    /** Change points from a custom 0/1 (thresholded) per-frame cp signal. */
    static int[] thresholdChangePoints(double[] cp) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i < cp.length - 1; i++) if (cp[i] >= 0.5) out.add(i);
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Builds segments (interval means of α / logD) from explicit change-point indices. */
    static List<Segment> segmentsFrom(int[] cps, double[] alpha, double[] logD) {
        int T = alpha.length;

        // Boundaries 0, cp1, cp2, …, T  → segments between consecutive pairs.
        int[] bounds = new int[cps.length + 2];
        bounds[0] = 0;
        System.arraycopy(cps, 0, bounds, 1, cps.length);
        bounds[bounds.length - 1] = T;

        List<Segment> segs = new ArrayList<>();
        for (int c = 0; c + 1 < bounds.length; c++) {
            int i = bounds[c], j = bounds[c + 1];
            if (j <= i) continue;
            double aMean = 0;
            for (int t = i; t < j; t++) aMean += alpha[t];
            aMean /= (j - i);
            double s = 0; int cnt = 0;
            for (int t = i; t < j; t++) if (Double.isFinite(logD[t])) { s += logD[t]; cnt++; }
            double dMean = cnt > 0 ? s / cnt : Double.NaN;
            segs.add(new Segment(i, j - i, aMean, dMean));
        }
        return segs;
    }

    /**
     * Detects change-point indices from per-step probabilities, mirroring the
     * Python {@code find_changepoints}: local-maximum peaks with height ≥
     * {@value #CP_MIN_HEIGHT}, at least {@value #CP_DISTANCE} apart (keeping the
     * higher when closer), then dropping peaks within 2 frames of either end.
     */
    static int[] findChangePoints(double[] p) {
        int n = p.length;

        // 1) Candidate local maxima above the height threshold.
        List<Integer> cand = new ArrayList<>();
        for (int i = 1; i < n - 1; i++)
            if (p[i] >= CP_MIN_HEIGHT && p[i] > p[i - 1] && p[i] >= p[i + 1])
                cand.add(i);

        // 2) Enforce minimum distance, keeping the highest-probability peaks.
        cand.sort((a, b) -> Double.compare(p[b], p[a]));
        boolean[] removed = new boolean[n];
        List<Integer> kept = new ArrayList<>();
        for (int idx : cand) {
            if (removed[idx]) continue;
            kept.add(idx);
            for (int j = Math.max(0, idx - CP_DISTANCE + 1);
                 j <= Math.min(n - 1, idx + CP_DISTANCE - 1); j++)
                if (j != idx) removed[j] = true;
        }

        // 3) Drop peaks too close to either end, then return sorted indices.
        List<Integer> out = new ArrayList<>();
        for (int idx : kept) if (idx > 2 && idx < n - 2) out.add(idx);
        Collections.sort(out);
        int[] res = new int[out.size()];
        for (int i = 0; i < res.length; i++) res[i] = out.get(i);
        return res;
    }

    // -----------------------------------------------------------------------
    // ONNX Runtime inference
    // -----------------------------------------------------------------------

    /**
     * Runs one batched forward pass and copies the {@code cp}, {@code kac} (and,
     * when {@code mtOut != null} and the model has one, {@code model_type})
     * outputs — all {@code (rows, Tpad, C)} — into the caller's arrays. A single
     * run yields every output; the model computes them together.
     */
    private void runBatch(float[][][] input, float[][][] cpOut, float[][][] kacOut,
                          float[][][] mtOut) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            float[][][] cp  = as3d(res, cpName);
            float[][][] kac = as3d(res, kacName);
            System.arraycopy(cp,  0, cpOut,  0, cp.length);
            System.arraycopy(kac, 0, kacOut, 0, kac.length);
            if (mtOut != null && mtName != null) {
                float[][][] mt = as3d(res, mtName);
                System.arraycopy(mt, 0, mtOut, 0, mt.length);
            }
        }
    }

    private static float[][][] as3d(OrtSession.Result res, String name) throws OrtException {
        OnnxValue outVal = res.get(name)
                .orElseThrow(() -> new OrtException(
                        "Output '" + name + "' not found in UCL-Net model"));
        Object val = outVal.getValue();
        if (val instanceof float[][][]) return (float[][][]) val;
        throw new OrtException("Unexpected output type for '" + name
                + "': " + (val == null ? "null" : val.getClass().getName()));
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static int padLength(int T) {
        int p = ((T + LEN_MULTIPLE - 1) / LEN_MULTIPLE) * LEN_MULTIPLE;
        return Math.max(LEN_MULTIPLE, p);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static byte[] loadResource(String path) throws IOException {
        InputStream is = UclStepPredictor.class.getResourceAsStream(path);
        if (is == null)
            throw new FileNotFoundException(
                    "Segmentation model resource not found: " + path + "\n"
                    + "Run ML_analysis/pythons_for_plugin/convert_step_v0814_to_onnx.py,\n"
                    + "copy ucl_step.onnx to src/main/resources/models/, and rebuild.");
        try { return readAllBytes(is); } finally { is.close(); }
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) { return readAllBytes(fis); }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = is.read(chunk)) != -1) buf.write(chunk, 0, len);
        return buf.toByteArray();
    }

    private void printInfo() throws OrtException {
        System.err.println("[UclStepPredictor] === UCL-Net (UNet3+) ===");
        for (NodeInfo ni : session.getInputInfo().values())
            System.err.println("[UclStepPredictor]  INPUT  " + ni.getName()
                    + "  shape=" + Arrays.toString(((TensorInfo) ni.getInfo()).getShape()));
        for (NodeInfo ni : session.getOutputInfo().values())
            System.err.println("[UclStepPredictor]  OUTPUT " + ni.getName()
                    + "  shape=" + Arrays.toString(((TensorInfo) ni.getInfo()).getShape()));
    }

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) {}
    }
}
