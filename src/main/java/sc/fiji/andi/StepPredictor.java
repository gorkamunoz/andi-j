package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.nio.FloatBuffer;
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
        sessionAlpha = env.createSession(alphaBytes, new OrtSession.SessionOptions());
        sessionLogD  = env.createSession(logDBytes,  new OrtSession.SessionOptions());
        alphaIn  = sessionAlpha.getInputNames().iterator().next();
        alphaOut = sessionAlpha.getOutputNames().iterator().next();
        logDIn   = sessionLogD.getInputNames().iterator().next();
        logDOut  = sessionLogD.getOutputNames().iterator().next();
        printInfo("STEPalpha", sessionAlpha);
        printInfo("STEPlogD",  sessionLogD);
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

        for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
            int            id    = e.getKey();
            List<double[]> spots = e.getValue();

            if (spots.size() < 2) {
                outAlpha.put(id, new double[0]);
                outLogD.put(id,  new double[0]);
                if (listener != null) listener.trackDone();
                continue;
            }

            double[][] cart  = extractXY(spots);
            float[][]  polar = cartesianToRelativePolar(cart);

            outAlpha.put(id, toDouble(
                    runSession(sessionAlpha, alphaIn, alphaOut, polar, false)));
            outLogD.put(id, toDouble(
                    runSession(sessionLogD,  logDIn,  logDOut,  polar, true)));

            if (listener != null) listener.trackDone();
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
     * Runs one model session on a single trajectory's polar features.
     *
     * @param polar       (T−1, 2) relative-polar features
     * @param log10Input  if true, applies {@code log₁₀(max(x, 1e-10))} to
     *                    every feature element before inference and adds
     *                    {@link #LOGD_CORRECTION} to every output
     * @return            {@code float[T-1]} per-frame predicted values
     */
    private float[] runSession(OrtSession session,
                               String inputName, String outputName,
                               float[][] polar,
                               boolean log10Input) throws OrtException {
        int seqLen = polar.length;

        // Build flat row-major FloatBuffer with shape [1, seqLen, 2]
        FloatBuffer buf = FloatBuffer.allocate(seqLen * 2);
        for (int t = 0; t < seqLen; t++) {
            float v0 = polar[t][0];
            float v1 = polar[t][1];
            if (log10Input) {
                // torch.log10(torch.maximum(traj_polar, 1e-10))
                // negative dθ is clipped to 1e-10 before log10
                v0 = (float) Math.log10(Math.max(v0, 1e-10f));
                v1 = (float) Math.log10(Math.max(v1, 1e-10f));
            }
            buf.put(v0);
            buf.put(v1);
        }
        buf.rewind();

        long[]  shape   = {1L, (long) seqLen, 2L};
        float[] results = new float[seqLen];

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, buf, shape);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            OnnxValue outVal = res.get(outputName)
                    .orElseThrow(() -> new OrtException(
                            "Output '" + outputName + "' not found in STEP model"));
            Object val = outVal.getValue();

            if (val instanceof float[][][]) {
                // shape (1, seqLen, 1)
                float[][][] arr = (float[][][]) val;
                for (int t = 0; t < seqLen; t++) results[t] = arr[0][t][0];

            } else if (val instanceof float[][]) {
                float[][] arr = (float[][]) val;
                if (arr.length == 1) {
                    // shape (1, seqLen)
                    System.arraycopy(arr[0], 0, results, 0,
                            Math.min(arr[0].length, seqLen));
                } else {
                    // shape (seqLen, 1)
                    for (int t = 0; t < Math.min(arr.length, seqLen); t++)
                        results[t] = arr[t].length > 0 ? arr[t][0] : Float.NaN;
                }

            } else if (val instanceof float[]) {
                float[] arr = (float[]) val;
                System.arraycopy(arr, 0, results, 0, Math.min(arr.length, seqLen));

            } else {
                System.err.println("[StepPredictor] unexpected output type: "
                        + (val == null ? "null" : val.getClass().getName()));
                Arrays.fill(results, Float.NaN);
            }
        }

        if (log10Input)
            for (int t = 0; t < seqLen; t++) results[t] += LOGD_CORRECTION;

        return results;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static double[] toDouble(float[] f) {
        double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) d[i] = f[i];
        return d;
    }

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
        System.err.println("[StepPredictor] === " + label + " ===");
        for (NodeInfo ni : s.getInputInfo().values())
            System.err.println("[StepPredictor]  INPUT  " + ni.getName()
                    + "  shape=" + Arrays.toString(
                            ((TensorInfo) ni.getInfo()).getShape()));
        for (NodeInfo ni : s.getOutputInfo().values())
            System.err.println("[StepPredictor]  OUTPUT " + ni.getName()
                    + "  shape=" + Arrays.toString(
                            ((TensorInfo) ni.getInfo()).getShape()));
    }

    @Override
    public void close() {
        try { sessionAlpha.close(); } catch (OrtException ignored) {}
        try { sessionLogD.close();  } catch (OrtException ignored) {}
    }
}
