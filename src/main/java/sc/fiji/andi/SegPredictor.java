package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.util.*;

/**
 * Runs the bundled segmentation model <b>andi-j-seg-latest</b> to estimate, for
 * every trajectory, the per-frame anomalous exponent α, per-frame log₁₀(D), the
 * per-frame diffusive state, <em>and</em> the change-point positions (from the
 * model's own change-point head).
 *
 * <h3>Model contract</h3>
 * The bundled model follows the same contract as user-supplied models (see the
 * <i>ML Models</i> wiki page), so the plugin does no pre- or post-processing of
 * the signal itself:
 * <ul>
 *   <li>Input {@code (B, T, 2)} float32 — raw x,y positions, one row per frame.
 *       {@code T} is dynamic, so no padding is needed.</li>
 *   <li>Outputs {@code alpha}, {@code logD}, {@code cp} and {@code model_type},
 *       each {@code (B, T)}.</li>
 * </ul>
 * {@code logD} is already log₁₀(D) and is stored verbatim; {@code cp} is a 0/1
 * per-frame flag; {@code model_type} is the diffusive-state class index
 * (0 = immobile, 1 = confined, 2 = free diffusion, 3 = directed).
 *
 * <p>Batch and length axes are both dynamic, so trajectories of equal length are
 * grouped and predicted in a single call.</p>
 *
 * <h3>Output</h3>
 * α is stored in {@link ExperimentData#stepAlpha}, log₁₀(D) in
 * {@link ExperimentData#stepLogD}, and the derived {@link Segment}s in
 * {@link ExperimentData#segments}.
 */
public class SegPredictor implements Closeable {

    /** Classpath resource for the bundled ONNX model. */
    public static final String DEFAULT_RESOURCE = "/models/andi-j-seg-latest.onnx";

    /** α is clipped to [0, {@value #ALPHA_MAX}]. */
    private static final double ALPHA_MAX = 1.999;

    /** Number of AnDi diffusive-state classes:
     *  0=immobile, 1=confined, 2=free diffusion, 3=directed. */
    private static final int STATE_CLASSES = 4;

    /** Batching caps so one inference call stays a reasonable size. */
    private static final int MAX_BATCH          = 64;       // trajectories per call
    private static final int MAX_BATCH_ELEMENTS = 400_000;  // rows × T per call

    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;
    private final String         alphaName;
    private final String         logDName;
    private final String         cpName;
    private final String         stateName;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public static SegPredictor fromResources() throws OrtException, IOException {
        return new SegPredictor(loadResource(DEFAULT_RESOURCE));
    }

    public SegPredictor(File modelFile) throws OrtException, IOException {
        this(readFile(modelFile));
    }

    public SegPredictor(byte[] modelBytes) throws OrtException {
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelBytes, OnnxSessions.gpuIfAvailable());
        inputName = session.getInputNames().iterator().next();

        // Outputs are exported in the order: alpha, logD, cp, model_type.
        List<String> outs = new ArrayList<>(session.getOutputNames());
        alphaName = pickOutput(outs, "alpha",      0);
        logDName  = pickOutput(outs, "logD",       1);
        cpName    = pickOutput(outs, "cp",         2);
        stateName = pickOutput(outs, "model_type", 3);
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

    /** One eligible track: its id, length T and (T, 2) positions. */
    private static final class Track {
        final int id, T;
        final float[][] xy;   // (T, 2)
        Track(int id, int T, float[][] xy) { this.id = id; this.T = T; this.xy = xy; }
    }

    /**
     * Runs the model on every track of {@code exp} and fills
     * {@link ExperimentData#stepAlpha}, {@link ExperimentData#stepLogD} and
     * {@link ExperimentData#segments}. Tracks with fewer than 2 frames get
     * empty arrays and no segments.
     *
     * <p>Trajectories are grouped by length so that many run in a single batched
     * inference call.</p>
     */
    public void computeForExperiment(ExperimentData exp, ProgressListener listener)
            throws OrtException {
        Map<Integer, double[]>      outAlpha = new LinkedHashMap<>();
        Map<Integer, double[]>      outLogD  = new LinkedHashMap<>();
        Map<Integer, List<Segment>> outSegs  = new LinkedHashMap<>();

        // Short tracks get empty output; the rest are bucketed by length so each
        // batch tensor is rectangular.
        Map<Integer, List<Track>> byLen = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
            int            id    = e.getKey();
            List<double[]> spots = e.getValue();
            int T = spots.size();
            if (T < 2) {
                outAlpha.put(id, new double[0]);
                outLogD.put(id,  new double[0]);
                outSegs.put(id,  Collections.<Segment>emptyList());
                if (listener != null) listener.trackDone();
                continue;
            }
            byLen.computeIfAbsent(T, k -> new ArrayList<>())
                 .add(new Track(id, T, positions(spots)));
        }

        for (Map.Entry<Integer, List<Track>> bucket : byLen.entrySet()) {
            int         T      = bucket.getKey();
            List<Track> tracks = bucket.getValue();

            // Trajectories per call: cap by both the count and rows×T elements.
            int maxTraj = Math.max(1, Math.min(MAX_BATCH,
                    MAX_BATCH_ELEMENTS / Math.max(1, T)));

            for (int off = 0; off < tracks.size(); off += maxTraj) {
                int nb = Math.min(maxTraj, tracks.size() - off);

                float[][][] in = new float[nb][][];
                for (int j = 0; j < nb; j++) in[j] = tracks.get(off + j).xy;

                Out out = runBatch(in, nb, T);

                for (int j = 0; j < nb; j++) {
                    Track tr = tracks.get(off + j);
                    double[] alpha = new double[T], logD = new double[T], cp = new double[T];
                    int[] state = new int[T];
                    for (int t = 0; t < T; t++) {
                        alpha[t] = clamp(out.alpha[j][t], 0.0, ALPHA_MAX);
                        // logD comes out of the model already as log₁₀(D); store it
                        // verbatim, mapping anything non-finite to NaN so it is
                        // neither plotted nor averaged into a segment mean.
                        double ld = out.logD[j][t];
                        logD[t]  = Double.isFinite(ld) ? ld : Double.NaN;
                        cp[t]    = out.cp[j][t];
                        state[t] = stateClass(out.state[j][t]);
                    }
                    outAlpha.put(tr.id, alpha);
                    outLogD.put(tr.id,  logD);
                    outSegs.put(tr.id,  buildSegments(cp, alpha, logD, state));
                    if (listener != null) listener.trackDone();
                }
            }
        }

        exp.stepAlpha = outAlpha;
        exp.stepLogD  = outLogD;
        exp.segments  = outSegs;
    }

    /** Rounds a raw model state value to a class in [0, STATE_CLASSES−1]. */
    static int stateClass(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(STATE_CLASSES - 1, (int) Math.round(v)));
    }

    /** Per-frame default-model outputs for a single track. */
    public static final class TrackOut {
        public double[] alpha, logD, cpProb;
        public int[]    state;   // per-frame diffusive-state class (may be null)
    }

    /**
     * Runs the default model on one track and returns its per-frame α, log₁₀(D),
     * change-point flags and diffusive state. Used by {@link SegmentationRunner}
     * to fill outputs the user did not supply a custom model for.
     */
    public TrackOut computeTrack(List<double[]> spots) throws OrtException {
        TrackOut o = new TrackOut();
        int T = spots.size();
        if (T < 2) {
            o.alpha = new double[0]; o.logD = new double[0]; o.cpProb = new double[0];
            return o;
        }

        Out out = runBatch(new float[][][]{ positions(spots) }, 1, T);

        double[] alpha = new double[T], logD = new double[T], cp = new double[T];
        int[] state = new int[T];
        for (int t = 0; t < T; t++) {
            alpha[t] = clamp(out.alpha[0][t], 0.0, ALPHA_MAX);
            double ld = out.logD[0][t];
            logD[t]  = Double.isFinite(ld) ? ld : Double.NaN;
            cp[t]    = out.cp[0][t];
            state[t] = stateClass(out.state[0][t]);
        }
        o.alpha = alpha; o.logD = logD; o.cpProb = cp; o.state = state;
        return o;
    }

    /** Extracts the (T, 2) position matrix from a track's spot list. */
    private static float[][] positions(List<double[]> spots) {
        int T = spots.size();
        float[][] xy = new float[T][2];
        for (int t = 0; t < T; t++) {
            xy[t][0] = (float) spots.get(t)[1];
            xy[t][1] = (float) spots.get(t)[2];
        }
        return xy;
    }

    // -----------------------------------------------------------------------
    // Change points → segments
    // -----------------------------------------------------------------------

    /** Builds segments from the model's 0/1 change-point flags. */
    static List<Segment> buildSegments(double[] cp, double[] alpha, double[] logD, int[] state) {
        return segmentsFrom(thresholdChangePoints(cp), alpha, logD, state);
    }

    /**
     * Change points from a per-frame 0/1 flag signal: every interior frame where
     * {@code cp >= 0.5} starts a new segment. Frame 0 and the last frame are
     * ignored — they are trajectory ends, not motion changes.
     *
     * <p>This is the contract every segmentation model follows, bundled or
     * user-supplied: one flag per boundary, at the first frame of the new
     * segment.</p>
     */
    static int[] thresholdChangePoints(double[] cp) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i < cp.length - 1; i++) if (cp[i] >= 0.5) out.add(i);
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /**
     * Builds segments from explicit change-point indices: interval means of
     * α / logD, plus the majority per-frame diffusive state (−1 if {@code state}
     * is null or has no valid class in the interval).
     */
    static List<Segment> segmentsFrom(int[] cps, double[] alpha, double[] logD, int[] state) {
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
            segs.add(new Segment(i, j - i, aMean, dMean, majorityState(state, i, j)));
        }
        return segs;
    }

    /** Most frequent valid diffusive state in [i, j); −1 if none. */
    private static int majorityState(int[] state, int i, int j) {
        if (state == null) return -1;
        int[] counts = new int[STATE_CLASSES];
        int any = 0;
        for (int t = i; t < j && t < state.length; t++) {
            int c = state[t];
            if (c >= 0 && c < STATE_CLASSES) { counts[c]++; any++; }
        }
        if (any == 0) return -1;
        int best = 0;
        for (int k = 1; k < STATE_CLASSES; k++) if (counts[k] > counts[best]) best = k;
        return best;
    }

    // -----------------------------------------------------------------------
    // ONNX Runtime inference
    // -----------------------------------------------------------------------

    /** One batched forward pass: each field is {@code (rows, T)}. */
    private static final class Out {
        float[][] alpha, logD, cp, state;
    }

    /**
     * Runs one batched forward pass over {@code (rows, T, 2)} positions and
     * returns all four per-frame outputs. A single run yields every output.
     */
    private Out runBatch(float[][][] input, int rows, int T) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            Out o = new Out();
            o.alpha = as2d(res, alphaName, rows, T);
            o.logD  = as2d(res, logDName,  rows, T);
            o.cp    = as2d(res, cpName,    rows, T);
            o.state = asState(res, stateName, rows, T);
            return o;
        }
    }

    /**
     * Reads the diffusive-state output. The contract allows either the class
     * index per frame ({@code (rows, T)}) or class probabilities
     * ({@code (rows, T, K)}, taken as a per-frame {@code argmax}).
     */
    private static float[][] asState(OrtSession.Result res, String name, int rows, int T)
            throws OrtException {
        OnnxValue outVal = res.get(name)
                .orElseThrow(() -> new OrtException(
                        "Output '" + name + "' not found in the andi-j-seg-latest model"));
        Object val = outVal.getValue();

        if (val instanceof float[][][]) {
            float[][][] v = (float[][][]) val;
            if (v.length > 0 && v[0].length > 0 && v[0][0].length > 1) {
                float[][] cls = new float[v.length][];
                for (int r = 0; r < v.length; r++) {
                    cls[r] = new float[v[r].length];
                    for (int t = 0; t < v[r].length; t++) {
                        float[] p = v[r][t];
                        int best = 0;
                        for (int k = 1; k < p.length; k++) if (p[k] > p[best]) best = k;
                        cls[r][t] = best;
                    }
                }
                return cls;
            }
        }
        return as2d(res, name, rows, T);   // (rows, T) or (rows, T, 1)
    }

    /**
     * Reads a per-frame output as {@code (rows, T)}. Accepts {@code (rows, T)}
     * and {@code (rows, T, 1)} layouts.
     */
    private static float[][] as2d(OrtSession.Result res, String name, int rows, int T)
            throws OrtException {
        OnnxValue outVal = res.get(name)
                .orElseThrow(() -> new OrtException(
                        "Output '" + name + "' not found in the andi-j-seg-latest model"));
        Object val = outVal.getValue();

        if (val instanceof float[][]) return (float[][]) val;

        if (val instanceof float[][][]) {              // (rows, T, 1)
            float[][][] v = (float[][][]) val;
            float[][] flat = new float[v.length][];
            for (int r = 0; r < v.length; r++) {
                flat[r] = new float[v[r].length];
                for (int t = 0; t < v[r].length; t++)
                    flat[r][t] = v[r][t].length > 0 ? v[r][t][0] : Float.NaN;
            }
            return flat;
        }
        throw new OrtException("Unexpected output type for '" + name
                + "': " + (val == null ? "null" : val.getClass().getName()));
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static byte[] loadResource(String path) throws IOException {
        InputStream is = SegPredictor.class.getResourceAsStream(path);
        if (is == null)
            throw new FileNotFoundException(
                    "Bundled model andi-j-seg-latest not found: " + path + "\n"
                    + "Copy the model into src/main/resources/models/ and rebuild.");
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
        if (!AndiLog.isDebug()) return;
        AndiLog.debug("=== andi-j-seg-latest ===");
        for (NodeInfo ni : session.getInputInfo().values())
            AndiLog.debug("  INPUT  " + ni.getName()
                    + "  shape=" + Arrays.toString(((TensorInfo) ni.getInfo()).getShape()));
        for (NodeInfo ni : session.getOutputInfo().values())
            AndiLog.debug("  OUTPUT " + ni.getName()
                    + "  shape=" + Arrays.toString(((TensorInfo) ni.getInfo()).getShape()));
    }

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) {}
    }
}
