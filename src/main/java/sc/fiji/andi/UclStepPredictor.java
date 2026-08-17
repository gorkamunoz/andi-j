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
 *   <li>The tensor is laid out channel-first: {@code (1, 2, Tpad)} with
 *       channel 0 = Δx, channel 1 = Δy.</li>
 * </ol>
 * The 8-view test-time augmentation is baked into the ONNX graph, so a single
 * forward pass per trajectory reproduces {@code predict_trajectory(use_tta=True)}.
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

    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;
    private final String         cpName;
    private final String         kacName;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public static UclStepPredictor fromResources() throws OrtException, IOException {
        return new UclStepPredictor(loadResource(DEFAULT_RESOURCE));
    }

    public UclStepPredictor(File modelFile) throws OrtException, IOException {
        this(readFile(modelFile));
    }

    public UclStepPredictor(byte[] modelBytes) throws OrtException {
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelBytes, makeSessionOptions());
        inputName = session.getInputNames().iterator().next();

        // Outputs are exported in the order: cp, kac, model_type.
        List<String> outs = new ArrayList<>(session.getOutputNames());
        cpName  = pickOutput(outs, "cp",  0);
        kacName = pickOutput(outs, "kac", 1);
        printInfo();
    }

    private static String pickOutput(List<String> outs, String preferred, int fallbackIdx) {
        for (String o : outs) if (o.equalsIgnoreCase(preferred)) return o;
        return outs.get(Math.min(fallbackIdx, outs.size() - 1));
    }

    private static OrtSession.SessionOptions makeSessionOptions() throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors()));
        try {
            opts.addCUDA(0);   // best-effort GPU; CPU remains the fallback
        } catch (Throwable t) {
            System.err.println("[UclStepPredictor] CUDA GPU unavailable, using CPU: " + t.getMessage());
        }
        return opts;
    }

    // -----------------------------------------------------------------------
    // Public inference
    // -----------------------------------------------------------------------

    public void computeForExperiment(ExperimentData exp) throws OrtException {
        computeForExperiment(exp, null);
    }

    /** Notified once after each track is processed (for progress reporting). */
    public interface ProgressListener { void trackDone(); }

    /**
     * Runs the model on every track of {@code exp} and fills
     * {@link ExperimentData#stepAlpha}, {@link ExperimentData#stepLogD} and
     * {@link ExperimentData#segments}. Tracks with fewer than 2 frames get
     * empty arrays and no segments.
     */
    public void computeForExperiment(ExperimentData exp, ProgressListener listener)
            throws OrtException {
        Map<Integer, double[]>        outAlpha = new LinkedHashMap<>();
        Map<Integer, double[]>        outLogD  = new LinkedHashMap<>();
        Map<Integer, List<Segment>>   outSegs  = new LinkedHashMap<>();

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

            // Displacements (channel-first, zero-padded last step, length T).
            int Tpad = padLength(T);
            float[][][] in = new float[1][2][Tpad];   // (1, 2, Tpad)
            for (int i = 0; i < T - 1; i++) {
                in[0][0][i] = (float) (spots.get(i + 1)[1] - spots.get(i)[1]); // Δx
                in[0][1][i] = (float) (spots.get(i + 1)[2] - spots.get(i)[2]); // Δy
            }
            // in[0][*][T-1..] already zero (pad).

            float[][][] cp  = run3d(in, cpName);   // (1, Tpad, 1)
            float[][][] kac = run3d(in, kacName);  // (1, Tpad, 3)

            double[] alpha = new double[T];
            double[] logD  = new double[T];
            double[] cpProb = new double[T];
            for (int t = 0; t < T; t++) {
                alpha[t]  = clamp(kac[0][t][0], 0.0, ALPHA_MAX);
                // The model's D head is a raw, unbounded linear output that
                // legitimately dips to ≤ 0 at (near-)immobile / uncertain points.
                // The Python reference clips D to [1e-12, 1e6] and shows it on a
                // *linear* axis, where ≈0 is invisible; taking log₁₀ of that
                // floor would instead paint spurious −12 spikes. We keep every
                // physically meaningful D (log₁₀ of a positive value) but mark
                // non-positive D as undefined (NaN), so it is neither plotted nor
                // averaged into a segment's mean log₁₀(D).
                double rawD = kac[0][t][1];
                logD[t]   = (Double.isFinite(rawD) && rawD > D_MIN)
                          ? Math.log10(Math.min(rawD, D_MAX)) : Double.NaN;
                cpProb[t] = cp[0][t][0];
            }

            outAlpha.put(id, alpha);
            outLogD.put(id,  logD);
            outSegs.put(id,  buildSegments(cpProb, alpha, logD));
            if (listener != null) listener.trackDone();
        }

        exp.stepAlpha = outAlpha;
        exp.stepLogD  = outLogD;
        exp.segments  = outSegs;
    }

    // -----------------------------------------------------------------------
    // Change-point → segments
    // -----------------------------------------------------------------------

    /** Builds segments from the per-frame cp probability + α / logD means. */
    private static List<Segment> buildSegments(double[] cpProb, double[] alpha, double[] logD) {
        int T = alpha.length;
        int[] cps = findChangePoints(cpProb);

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

    /** Runs the model and returns the named 3-D output as {@code float[1][T][C]}. */
    private float[][][] run3d(float[][][] input, String outputName) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            OnnxValue outVal = res.get(outputName)
                    .orElseThrow(() -> new OrtException(
                            "Output '" + outputName + "' not found in UCL-Net model"));
            Object val = outVal.getValue();
            if (val instanceof float[][][]) return (float[][][]) val;
            throw new OrtException("Unexpected output type for '" + outputName
                    + "': " + (val == null ? "null" : val.getClass().getName()));
        }
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
