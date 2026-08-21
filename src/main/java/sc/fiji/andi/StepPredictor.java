package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.util.*;

/**
 * Runs the two STEP models (exported to ONNX format) to estimate per-frame
 * anomalous exponent α and log₁₀(D) for every trajectory in an
 * {@link ExperimentData}.
 *
 * <h3>Input preprocessing</h3>
 * The models take 2-D Cartesian coordinates converted to relative-polar form:
 * <ol>
 *   <li>Compute displacement vectors Δr[i] = r[i+1] − r[i].</li>
 *   <li>{@code dr[i]  = |Δr[i]|}  (Euclidean magnitude).</li>
 *   <li>{@code dθ[0]  = 0}; for i ≥ 1:
 *       {@code dθ[i] = signed angle(Δr[i−1], Δr[i])}.</li>
 * </ol>
 * For the logD model an additional {@code log₁₀(max(x, 1e-10))} transform is
 * applied element-wise before inference (identical to the Python reference),
 * and each output value is corrected by +{@value #LOGD_CORRECTION}.
 *
 * <h3>Output</h3>
 * For a track with T frames both models produce T−1 per-frame values.
 * Element [i] corresponds to the displacement from frame i to frame i+1.
 * Results are stored in {@link ExperimentData#stepAlpha} and
 * {@link ExperimentData#stepLogD}.
 */
public class StepPredictor implements Closeable {

    /** Classpath resource paths for the bundled ONNX models. */
    public static final String DEFAULT_ALPHA_RESOURCE = "/models/step_alpha.onnx";
    public static final String DEFAULT_LOGD_RESOURCE  = "/models/step_logd.onnx";

    /**
     * Additive correction applied to raw logD model output, matching the
     * Python reference: {@code preds_d = traj_pred_d + 3.5}.
     */
    public static final float LOGD_CORRECTION = 3.5f;

    /** Max trajectories per batched inference call. */
    private static final int MAX_BATCH = 256;
    /** Cap on batch×seqLen elements, so very long trajectories use smaller batches. */
    private static final int MAX_BATCH_ELEMENTS = 200_000;

    private final OrtEnvironment env;
    private final OrtSession     sessionAlpha;
    private final OrtSession     sessionLogD;
    private final String         alphaIn, alphaOut;
    private final String         logDIn,  logDOut;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Loads both STEP models from the bundled classpath resources.
     *
     * @throws OrtException  if ONNX Runtime rejects either model
     * @throws IOException   if a resource stream cannot be read; the message
     *                       includes instructions for generating the files
     */
    public static StepPredictor fromResources() throws OrtException, IOException {
        return new StepPredictor(
                loadResource(DEFAULT_ALPHA_RESOURCE),
                loadResource(DEFAULT_LOGD_RESOURCE));
    }

    /**
     * Loads both STEP models from files on disk (e.g. user-selected).
     */
    public StepPredictor(File alphaFile, File logDFile)
            throws OrtException, IOException {
        this(readFile(alphaFile), readFile(logDFile));
    }

    /**
     * Loads both STEP models from raw ONNX bytes.
     */
    public StepPredictor(byte[] alphaBytes, byte[] logDBytes) throws OrtException {
        env          = OrtEnvironment.getEnvironment();
        sessionAlpha = env.createSession(alphaBytes, makeSessionOptions());
        sessionLogD  = env.createSession(logDBytes,  makeSessionOptions());
        alphaIn  = sessionAlpha.getInputNames().iterator().next();
        alphaOut = sessionAlpha.getOutputNames().iterator().next();
        logDIn   = sessionLogD.getInputNames().iterator().next();
        logDOut  = sessionLogD.getOutputNames().iterator().next();
        printInfo("STEPalpha", sessionAlpha);
        printInfo("STEPlogD",  sessionLogD);
    }

    /**
     * Builds session options: CPU intra-op parallelism set to the core count,
     * and (best-effort) the CUDA GPU execution provider.  If the runtime is the
     * CPU-only build or no compatible GPU is present, {@code addCUDA} fails and
     * we silently fall back to CPU.
     */
    private static OrtSession.SessionOptions makeSessionOptions() throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors()));
        try {
            opts.addCUDA(0);   // device 0; CPU remains as automatic fallback
        } catch (Throwable t) {
            AndiLog.debug("StepPredictor: CUDA GPU unavailable, using CPU: " + t.getMessage());
        }
        return opts;
    }

    // -----------------------------------------------------------------------
    // Public inference
    // -----------------------------------------------------------------------

    /**
     * Runs STEP inference for every track in {@code exp} and stores results
     * in {@link ExperimentData#stepAlpha} and {@link ExperimentData#stepLogD}.
     *
     * <p>For a track with T frames both arrays have length T−1;
     * element [i] is the prediction for the displacement from frame i to i+1.
     * Tracks with fewer than 2 frames receive empty (length 0) arrays.</p>
     *
     * @param exp the experiment to process
     * @throws OrtException if ONNX Runtime encounters an inference error
     */
    public void computeForExperiment(ExperimentData exp) throws OrtException {
        computeForExperiment(exp, null);
    }

    /** Notified once after each track is processed (for progress reporting). */
    public interface ProgressListener { void trackDone(); }

    /**
     * As {@link #computeForExperiment(ExperimentData)}, but invokes
     * {@code listener.trackDone()} after every track (including skipped ones),
     * so callers can drive a determinate progress bar.
     */
    public void computeForExperiment(ExperimentData exp, ProgressListener listener)
            throws OrtException {
        Map<Integer, double[]> outAlpha = new LinkedHashMap<>();
        Map<Integer, double[]> outLogD  = new LinkedHashMap<>();

        // Preprocess eligible tracks to polar form; short tracks get empty output.
        List<Integer>   ids    = new ArrayList<>();
        List<float[][]> polars = new ArrayList<>();
        for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
            int            id    = e.getKey();
            List<double[]> spots = e.getValue();
            if (spots.size() < 2) {
                outAlpha.put(id, new double[0]);
                outLogD.put(id,  new double[0]);
                if (listener != null) listener.trackDone();
                continue;
            }
            ids.add(id);
            polars.add(cartesianToRelativePolar(extractXY(spots)));
        }

        // Bucket tracks by sequence length so each batch is a rectangular tensor
        // (no padding needed → exact, identical to per-trajectory inference).
        Map<Integer, List<Integer>> byLen = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++)
            byLen.computeIfAbsent(polars.get(i).length, k -> new ArrayList<>()).add(i);

        for (Map.Entry<Integer, List<Integer>> bucket : byLen.entrySet()) {
            int           L    = bucket.getKey();
            List<Integer> idxs = bucket.getValue();
            int maxB = Math.max(1, Math.min(MAX_BATCH, MAX_BATCH_ELEMENTS / Math.max(1, L)));

            for (int off = 0; off < idxs.size(); off += maxB) {
                int B = Math.min(maxB, idxs.size() - off);

                // Build the two input tensors: α uses raw polar; D uses log10(max(x,1e-10))
                float[][][] inA = new float[B][L][2];
                float[][][] inD = new float[B][L][2];
                for (int b = 0; b < B; b++) {
                    float[][] p = polars.get(idxs.get(off + b));
                    for (int t = 0; t < L; t++) {
                        float v0 = p[t][0], v1 = p[t][1];
                        inA[b][t][0] = v0;
                        inA[b][t][1] = v1;
                        inD[b][t][0] = (float) Math.log10(Math.max(v0, 1e-10f));
                        inD[b][t][1] = (float) Math.log10(Math.max(v1, 1e-10f));
                    }
                }

                float[][] aOut = runBatch(sessionAlpha, alphaIn, alphaOut, inA);
                float[][] dOut = runBatch(sessionLogD,  logDIn,  logDOut,  inD);

                for (int b = 0; b < B; b++) {
                    int      id = ids.get(idxs.get(off + b));
                    double[] a  = new double[L];
                    double[] d  = new double[L];
                    for (int t = 0; t < L; t++) {
                        a[t] = aOut[b][t];
                        d[t] = dOut[b][t] + LOGD_CORRECTION;
                    }
                    outAlpha.put(id, a);
                    outLogD.put(id, d);
                    if (listener != null) listener.trackDone();
                }
            }
        }

        exp.stepAlpha = outAlpha;
        exp.stepLogD  = outLogD;
    }

    // -----------------------------------------------------------------------
    // Preprocessing
    // -----------------------------------------------------------------------

    /** Extracts the (T, 2) [x, y] matrix from a spot list sorted by frame. */
    private static double[][] extractXY(List<double[]> spots) {
        int T = spots.size();
        double[][] out = new double[T][2];
        for (int i = 0; i < T; i++) {
            out[i][0] = spots.get(i)[1];  // x
            out[i][1] = spots.get(i)[2];  // y
        }
        return out;
    }

    /**
     * Mirrors {@code andi_datasets.utils_trajectories.cartesian_to_relative_polar}:
     * converts (T, 2) Cartesian positions to (T−1, 2) relative-polar
     * features {@code [dr, dθ]}.
     *
     * <ul>
     *   <li>{@code dr[i]  = hypot(Δx[i], Δy[i])}</li>
     *   <li>{@code dθ[0]  = 0} (zero-initialised, matching {@code np.zeros_like})</li>
     *   <li>{@code dθ[i]  = atan2(det, dot)} of consecutive displacement vectors (i ≥ 1)</li>
     * </ul>
     */
    static float[][] cartesianToRelativePolar(double[][] cart) {
        int T = cart.length;
        int N = T - 1;
        float[][] out = new float[N][2];  // out[i][1] initialised to 0 by default

        double[] dx = new double[N];
        double[] dy = new double[N];
        for (int i = 0; i < N; i++) {
            dx[i] = cart[i + 1][0] - cart[i][0];
            dy[i] = cart[i + 1][1] - cart[i][1];
        }

        // dr[i] = |Δr[i]|
        for (int i = 0; i < N; i++)
            out[i][0] = (float) Math.hypot(dx[i], dy[i]);

        // dθ[i] = signed angle between Δr[i-1] and Δr[i]  (i >= 1)
        for (int i = 1; i < N; i++) {
            double x1 = dx[i - 1], y1 = dy[i - 1];
            double x2 = dx[i],     y2 = dy[i];
            out[i][1] = (float) Math.atan2(
                    x1 * y2 - y1 * x2,   // 2-D cross product (determinant)
                    x1 * x2 + y1 * y2);  // dot product
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // ONNX Runtime inference
    // -----------------------------------------------------------------------

    /**
     * Runs one model session on a batch of equal-length polar sequences.
     *
     * @param input  {@code [B][L][2]} feature tensor (already preprocessed)
     * @return       {@code [B][L]} per-frame predictions
     */
    private float[][] runBatch(OrtSession session, String inputName, String outputName,
                               float[][][] input) throws OrtException {
        int B = input.length;
        int L = (B > 0) ? input[0].length : 0;
        float[][] results = new float[B][L];

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            OnnxValue outVal = res.get(outputName)
                    .orElseThrow(() -> new OrtException(
                            "Output '" + outputName + "' not found in STEP model"));
            Object val = outVal.getValue();

            if (val instanceof float[][][]) {
                // shape (B, L, 1)
                float[][][] arr = (float[][][]) val;
                for (int b = 0; b < B; b++)
                    for (int t = 0; t < L; t++) results[b][t] = arr[b][t][0];

            } else if (val instanceof float[][]) {
                // shape (B, L)
                float[][] arr = (float[][]) val;
                for (int b = 0; b < B; b++)
                    System.arraycopy(arr[b], 0, results[b], 0, Math.min(L, arr[b].length));

            } else {
                AndiLog.warn("StepPredictor: unexpected output type: "
                        + (val == null ? "null" : val.getClass().getName()));
                for (float[] row : results) Arrays.fill(row, Float.NaN);
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static byte[] loadResource(String path) throws IOException {
        InputStream is = StepPredictor.class.getResourceAsStream(path);
        if (is == null)
            throw new FileNotFoundException(
                    "STEP model resource not found: " + path + "\n"
                    + "Run ML_analysis/convert_step_to_onnx.py, copy the .onnx\n"
                    + "files to src/main/resources/models/, and rebuild.");
        try { return readAllBytes(is); } finally { is.close(); }
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return readAllBytes(fis);
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = is.read(chunk)) != -1) buf.write(chunk, 0, len);
        return buf.toByteArray();
    }

    private static void printInfo(String label, OrtSession s) throws OrtException {
        if (!AndiLog.isDebug()) return;
        AndiLog.debug("=== " + label + " ===");
        for (NodeInfo ni : s.getInputInfo().values())
            AndiLog.debug("  INPUT  " + ni.getName()
                    + "  shape=" + Arrays.toString(
                            ((TensorInfo) ni.getInfo()).getShape()));
        for (NodeInfo ni : s.getOutputInfo().values())
            AndiLog.debug("  OUTPUT " + ni.getName()
                    + "  shape=" + Arrays.toString(
                            ((TensorInfo) ni.getInfo()).getShape()));
    }

    @Override
    public void close() {
        try { sessionAlpha.close(); } catch (OrtException ignored) {}
        try { sessionLogD.close();  } catch (OrtException ignored) {}
    }
}
