package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.util.*;

/**
 * Runs the bundled segmentation model <b>andi-j-seg-latest</b>, one trajectory per
 * ONNX run, {@link #MAX_PARALLEL_RUNS} trajectories at a time ({@link ParallelTracks}).
 *
 * <h3>Model contract</h3>
 * The bundled model follows the same contract as user-supplied models (see the
 * <i>ML Models</i> wiki page): the raw trajectory goes in, with no preprocessing.
 * Two rules apply to its outputs only, never to custom models: α is clipped to
 * [0, {@value #ALPHA_MAX}] (NaN → 0), and frames and segments with
 * α &lt; {@value #IMMOBILE_ALPHA_MAX} are classified as immobile.
 *
 * <p><b>Required</b> outputs, each {@code (1, T)} — the input is {@code (1, T, 2)}
 * raw x,y positions:</p>
 * <ul>
 *   <li>{@code alpha} — anomalous exponent per frame</li>
 *   <li>{@code logD} — log₁₀(D) per frame</li>
 *   <li>{@code cp} — change-point flag per frame, 0 or 1</li>
 *   <li>{@code diffusive_state} — class index, or {@code (1, T, 4)} probabilities</li>
 * </ul>
 *
 * <p><b>Optional</b> segment-level outputs, each {@code (1, S)}:
 * {@code seg_start}, {@code seg_length}, {@code seg_alpha}, {@code seg_logD},
 * {@code seg_state}. When the model provides them and they are enabled, they
 * replace the segments the plugin derives from {@code cp}.</p>
 */
public class SegPredictor implements Closeable {

    /** Classpath resource for the bundled ONNX model. */
    public static final String DEFAULT_RESOURCE = "/models/andi-j-seg-latest.onnx";

    /** α is clipped to [0, {@value #ALPHA_MAX}]. */
    private static final double ALPHA_MAX = 1.999;

    /**
     * Frames whose predicted α falls below this are classified as immobile
     * (state 0) regardless of what the model reports, to compensate for
     * under-detection of trapping.
     */
    static final double IMMOBILE_ALPHA_MAX = 0.1;

    /**
     * Segmentation runs executed at once. Each run of the bundled model holds its
     * activations (about 0.3 GB for a 1000-frame trajectory), so the cap bounds
     * memory; beyond 4 the speed-up is small.
     */
    static final int MAX_PARALLEL_RUNS = 4;

    /** Number of AnDi diffusive-state classes:
     *  0=immobile, 1=confined, 2=free diffusion, 3=directed. */
    static final int STATE_CLASSES = 4;

    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;
    private final List<String>   outNames;
    /** Use the model's own seg_* outputs when it provides them. */
    private final boolean        useModelSegments;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public static SegPredictor fromResources(boolean useModelSegments)
            throws OrtException, IOException {
        return new SegPredictor(loadResource(DEFAULT_RESOURCE), useModelSegments);
    }

    public SegPredictor(File modelFile, boolean useModelSegments)
            throws OrtException, IOException {
        this(readFile(modelFile), useModelSegments);
    }

    public SegPredictor(byte[] modelBytes, boolean useModelSegments) throws OrtException {
        this.useModelSegments = useModelSegments;
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelBytes, OnnxSessions.forParallelRuns(MAX_PARALLEL_RUNS));
        inputName = session.getInputNames().iterator().next();
        outNames  = new ArrayList<>(session.getOutputNames());
        printInfo();
    }

    // -----------------------------------------------------------------------
    // Public inference
    // -----------------------------------------------------------------------

    /**
     * Runs the model on every track of {@code exp} and fills
     * {@link ExperimentData#stepAlpha}, {@link ExperimentData#stepLogD},
     * {@link ExperimentData#stepState} and {@link ExperimentData#segments}.
     * Tracks with fewer than 2 frames get empty arrays and no segments.
     */
    public void computeForExperiment(ExperimentData exp, MlPredictor.ProgressListener listener)
            throws OrtException {
        Map<Integer, TrackOut> outs = ParallelTracks.map(exp.tracks, this::computeTrack,
                                                         listener, MAX_PARALLEL_RUNS);

        Map<Integer, double[]>      outAlpha = new LinkedHashMap<>();
        Map<Integer, double[]>      outLogD  = new LinkedHashMap<>();
        Map<Integer, int[]>         outState = new LinkedHashMap<>();
        Map<Integer, List<Segment>> outSegs  = new LinkedHashMap<>();
        for (Map.Entry<Integer, TrackOut> e : outs.entrySet()) {
            int      id = e.getKey();
            TrackOut o  = e.getValue();
            outAlpha.put(id, o.alpha);
            outLogD.put(id,  o.logD);
            outState.put(id, o.state);
            outSegs.put(id,  o.alpha.length == 0 ? Collections.<Segment>emptyList()
                           : o.segments != null ? o.segments
                           : withBundledRules(buildSegments(o.cpProb, o.alpha, o.logD, o.state)));
        }

        exp.stepAlpha = outAlpha;
        exp.stepLogD  = outLogD;
        exp.stepState = outState;
        exp.segments  = outSegs;
    }

    /** Per-frame model outputs for a single track, plus any model-given segments. */
    public static final class TrackOut {
        public double[] alpha, logD, cpProb;
        public int[]    state;
        /** Segments read from the model's seg_* outputs, or null. */
        public List<Segment> segments;
    }

    /**
     * Runs the model on one track. Used both by {@link #computeForExperiment}
     * and by {@link SegmentationRunner} when filling outputs the user did not
     * supply a custom model for.
     */
    public TrackOut computeTrack(List<double[]> spots) throws OrtException {
        TrackOut o = new TrackOut();
        int T = spots.size();
        if (T < 2) {
            o.alpha = new double[0]; o.logD = new double[0];
            o.cpProb = new double[0]; o.state = new int[0];
            return o;
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][][]{ positions(spots) });
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            double[] alpha = required(res, "alpha", T);
            double[] logD  = required(res, "logD",  T);
            double[] cp    = required(res, "cp",    T);
            int[]    state = requiredState(res, T);

            o.alpha  = new double[T];
            o.logD   = new double[T];
            o.cpProb = cp;
            o.state  = state;
            for (int t = 0; t < T; t++) {
                o.alpha[t] = clamp(alpha[t], 0.0, ALPHA_MAX);
                // logD arrives already as log₁₀(D); keep it verbatim and map
                // anything non-finite to NaN so it is neither plotted nor
                // averaged into a segment mean.
                o.logD[t]  = Double.isFinite(logD[t]) ? logD[t] : Double.NaN;
            }
            applyImmobileRule(o.alpha, o.state);
            if (useModelSegments) {
                List<Segment> segs = readModelSegments(res, outNames, T);
                o.segments = segs == null ? null : withBundledRules(segs);
            }
        }
        return o;
    }

    /** Segments with α clipped and the immobile rule applied (bundled model only). */
    private static List<Segment> withBundledRules(List<Segment> segs) {
        List<Segment> out = new ArrayList<>(segs.size());
        for (Segment g : segs) {
            double a = clamp(g.alpha, 0.0, ALPHA_MAX);
            out.add(new Segment(g.start, g.length, a, g.logD, segmentState(a, g.state)));
        }
        return out;
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
    // Reading outputs
    // -----------------------------------------------------------------------

    private static final String REQUIRED_MSG =
            " It must provide alpha, logD, cp and diffusive_state.";

    /** A required per-frame output; absence is a hard error. */
    private double[] required(OrtSession.Result res, String name, int T) throws OrtException {
        OnnxValue v = byName(res, outNames, name);
        if (v == null)
            throw new OrtException("Segmentation model is missing the required output '"
                    + name + "'." + REQUIRED_MSG);
        return perFrame(v, T, name);
    }

    /** The required diffusive state, as a class index or per-frame probabilities. */
    private int[] requiredState(OrtSession.Result res, int T) throws OrtException {
        OnnxValue v = byName(res, outNames, "diffusive_state");
        if (v == null)
            throw new OrtException("Segmentation model is missing the required output "
                    + "'diffusive_state'." + REQUIRED_MSG);
        Object val = v.getValue();
        int[] out = new int[T];
        if (val instanceof float[][][]) {          // (1, T, K) -> argmax per frame
            float[][] frames = ((float[][][]) val)[0];
            for (int t = 0; t < T && t < frames.length; t++) {
                float[] f = frames[t];
                int best = 0;
                for (int k = 1; k < f.length; k++) if (f[k] > f[best]) best = k;
                out[t] = best;
            }
            return out;
        }
        double[] flat = perFrame(v, T, "diffusive_state");
        for (int t = 0; t < T; t++) out[t] = stateClass(flat[t]);
        return out;
    }

    /** Reads the optional seg_* outputs verbatim into segments, or null when absent. */
    static List<Segment> readModelSegments(OrtSession.Result res, List<String> outNames, int T)
            throws OrtException {
        OnnxValue vs = byName(res, outNames, "seg_start");
        OnnxValue vl = byName(res, outNames, "seg_length");
        if (vs == null || vl == null) return null;

        double[] start  = anyLength(vs);
        double[] length = anyLength(vl);
        double[] alpha  = optional(res, outNames, "seg_alpha");
        double[] logD   = optional(res, outNames, "seg_logD");
        double[] state  = optional(res, outNames, "seg_state");

        int S = Math.min(start.length, length.length);
        List<Segment> segs = new ArrayList<>();
        for (int i = 0; i < S; i++) {
            int st  = (int) Math.round(start[i]);
            int len = (int) Math.round(length[i]);
            if (len <= 0 || st < 0 || st >= T) continue;      // skip padding rows
            len = Math.min(len, T - st);
            double a = (alpha != null && i < alpha.length) ? alpha[i] : Double.NaN;
            double d = (logD != null && i < logD.length && Double.isFinite(logD[i]))
                     ? logD[i] : Double.NaN;
            int s = (state != null && i < state.length) ? stateClass(state[i]) : -1;
            segs.add(new Segment(st, len, a, d, s));
        }
        return segs.isEmpty() ? null : segs;
    }

    private static double[] optional(OrtSession.Result res, List<String> outNames, String name)
            throws OrtException {
        OnnxValue v = byName(res, outNames, name);
        return v == null ? null : anyLength(v);
    }

    static OnnxValue byName(OrtSession.Result res, List<String> outNames, String name)
            throws OrtException {
        for (String o : outNames)
            if (o.equalsIgnoreCase(name)) return res.get(o).orElse(null);
        return null;
    }

    /** Reads a {@code (1, T)}, {@code (1, T, 1)} or {@code (T,)} output as length T. */
    private static double[] perFrame(OnnxValue v, int T, String name) throws OrtException {
        Object val = v.getValue();
        double[] out = new double[T];
        if (val instanceof float[][][]) {
            float[][][] a = (float[][][]) val;
            for (int t = 0; t < T && t < a[0].length; t++) out[t] = a[0][t][0];
        } else if (val instanceof float[][]) {
            float[][] a = (float[][]) val;
            for (int t = 0; t < T && t < a[0].length; t++) out[t] = a[0][t];
        } else if (val instanceof float[]) {
            float[] a = (float[]) val;
            for (int t = 0; t < T && t < a.length; t++) out[t] = a[t];
        } else {
            throw new OrtException("Unexpected type for output '" + name + "': "
                    + (val == null ? "null" : val.getClass().getName()));
        }
        return out;
    }

    /** Reads a variable-length {@code (1, S)} / {@code (S,)} output of any numeric type. */
    private static double[] anyLength(OnnxValue v) throws OrtException {
        Object val = v.getValue();
        if (val instanceof float[][]) return widen(((float[][]) val)[0]);
        if (val instanceof float[])   return widen((float[]) val);
        if (val instanceof long[][])  return widen(((long[][]) val)[0]);
        if (val instanceof long[])    return widen((long[]) val);
        if (val instanceof int[][])   return widen(((int[][]) val)[0]);
        if (val instanceof int[])     return widen((int[]) val);
        throw new OrtException("Unexpected type for a seg_* output: "
                + (val == null ? "null" : val.getClass().getName()));
    }

    private static double[] widen(float[] a) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i];
        return o;
    }

    private static double[] widen(long[] a) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i];
        return o;
    }

    private static double[] widen(int[] a) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i];
        return o;
    }

    /** Forces state 0 (immobile) on every frame whose α is below the threshold. */
    private static void applyImmobileRule(double[] alpha, int[] state) {
        if (alpha == null || state == null) return;
        for (int t = 0; t < state.length && t < alpha.length; t++)
            if (alpha[t] < IMMOBILE_ALPHA_MAX) state[t] = 0;
    }

    /** State of a segment, forced to immobile when its α is below the threshold. */
    private static int segmentState(double alpha, int state) {
        return (alpha < IMMOBILE_ALPHA_MAX) ? 0 : state;
    }

    /** Rounds a raw model state value to a class in [0, STATE_CLASSES-1]. */
    static int stateClass(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(STATE_CLASSES - 1, (int) Math.round(v)));
    }

    // -----------------------------------------------------------------------
    // Change points to segments
    // -----------------------------------------------------------------------

    /** Builds segments from the model's 0/1 change-point flags. */
    static List<Segment> buildSegments(double[] cp, double[] alpha, double[] logD, int[] state) {
        return segmentsFrom(thresholdChangePoints(cp), alpha, logD, state);
    }

    /**
     * Change points from a per-frame 0/1 flag signal: every interior frame where
     * {@code cp >= 0.5} starts a new segment. Frame 0 and the last frame are
     * ignored — they are trajectory ends, not motion changes.
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
