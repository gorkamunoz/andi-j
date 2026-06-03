package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Runs the SingleTrajModel (exported to ONNX) to estimate the anomalous
 * exponent α for each trajectory in an {@link ExperimentData}.
 *
 * <h3>Model contract</h3>
 * <ul>
 *   <li>Input 1 – {@code "input"}  shape {@code (1, T-1, 2)} – float32,
 *       2-D Cartesian coordinates converted to relative-polar form
 *       (same preprocessing as the STEP models).</li>
 *   <li>Input 2 – {@code "log_t"} shape {@code (1, 1)}  – float32,
 *       precomputed {@code log(T-1)} where T-1 is the number of polar
 *       feature vectors.  Supplied externally so the sequence length
 *       stays fully dynamic in the ONNX graph.</li>
 *   <li>Output – {@code "output"} shape {@code (1, 1)} or {@code (1,)}
 *       – one predicted α per trajectory.</li>
 * </ul>
 *
 * <h3>Preprocessing</h3>
 * Mirrors {@code cartesian_to_relative_polar} from the Python reference:
 * <ol>
 *   <li>{@code dr[i]  = |Δr[i]|}   (Euclidean displacement magnitude)</li>
 *   <li>{@code dθ[0]  = 0}; for i ≥ 1:
 *       {@code dθ[i]  = signed angle(Δr[i-1], Δr[i])}</li>
 * </ol>
 * Both features are fed directly to the model without further normalisation.
 *
 * <h3>Variable-length trajectories</h3>
 * The model accepts any number of frames T ≥ 2.  Each trajectory is
 * processed individually because lengths differ between tracks.
 *
 * <h3>Stored result</h3>
 * α is stored in {@link ExperimentData#alphaCoeffs} as
 * {@code {α, NaN, NaN}} (2-D result only; X and Y components are not
 * produced by this model).
 */
public class OnnxAlphaPredictor implements Closeable {

    /** Classpath resource path for the bundled model. */
    public static final String DEFAULT_RESOURCE = "/models/single_traj_alpha.onnx";

    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;   // "input"
    private final String         logTName;    // "log_t"
    private final String         outputName;  // "output"

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Load the model from a file on disk.
     *
     * @param modelFile path to the {@code .onnx} file
     * @throws OrtException if ONNX Runtime cannot load the model
     * @throws IOException  if the file cannot be read
     */
    public OnnxAlphaPredictor(File modelFile) throws OrtException, IOException {
        this(readFile(modelFile));
    }

    /**
     * Load the model from the bundled classpath resource.
     *
     * @return a ready predictor
     * @throws OrtException  if ONNX Runtime rejects the model bytes
     * @throws IOException   if the resource stream cannot be read
     */
    public static OnnxAlphaPredictor fromResource()
            throws OrtException, IOException {
        InputStream is = OnnxAlphaPredictor.class.getResourceAsStream(DEFAULT_RESOURCE);
        if (is == null)
            throw new FileNotFoundException(
                    "Model resource not found: " + DEFAULT_RESOURCE + "\n"
                    + "Run ML_analysis/convert_single_traj_to_onnx.py, copy "
                    + "single_traj_alpha.onnx to src/main/resources/models/ "
                    + "and rebuild.");
        byte[] bytes;
        try { bytes = readAllBytes(is); } finally { is.close(); }
        return new OnnxAlphaPredictor(bytes);
    }

    /** Internal constructor used by all public entry points. */
    public OnnxAlphaPredictor(byte[] modelBytes) throws OrtException {
        env     = OrtEnvironment.getEnvironment();
        session = env.createSession(modelBytes, new OrtSession.SessionOptions());

        List<String> inNames = new ArrayList<>(session.getInputNames());
        if (inNames.size() < 2)
            throw new OrtException(
                    "Expected 2 model inputs (input, log_t), found " + inNames.size()
                    + ". Re-export with convert_single_traj_to_onnx.py.");
        inputName  = inNames.get(0);  // "input"
        logTName   = inNames.get(1);  // "log_t"
        outputName = session.getOutputNames().iterator().next();

        printModelInfo();
    }

    // -----------------------------------------------------------------------
    // Inference
    // -----------------------------------------------------------------------

    /**
     * Runs ML inference for every track in {@code exp} and stores results in
     * {@link ExperimentData#alphaCoeffs}.
     *
     * <p>Existing {@code alphaCoeffs} values are replaced entirely.
     * Tracks with fewer than 2 frames receive {@code {NaN, NaN, NaN}}.</p>
     *
     * @param exp the experiment to analyse
     * @throws OrtException if ONNX Runtime encounters an error
     */
    public void computeForExperiment(ExperimentData exp) throws OrtException {
        Map<Integer, double[]> result = new LinkedHashMap<>();

        for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
            int            id    = e.getKey();
            List<double[]> spots = e.getValue();

            if (spots.size() < 2) {
                result.put(id, new double[]{Double.NaN, Double.NaN, Double.NaN});
                continue;
            }

            float[][] polar = cartesianToRelativePolar(extractXY(spots));
            float     alpha = runSingle(polar);

            // Store {alpha_2D, NaN, NaN}: model gives one 2-D result only
            result.put(id, new double[]{alpha, Double.NaN, Double.NaN});
        }

        exp.alphaCoeffs = result;
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
     * Mirrors {@code cartesian_to_relative_polar} from the Python reference:
     * converts (T, 2) Cartesian positions into (T-1, 2) relative-polar features
     * {@code [dr, dθ]}.
     *
     * <ul>
     *   <li>{@code dr[i]  = hypot(Δx[i], Δy[i])}</li>
     *   <li>{@code dθ[0]  = 0}  (matching {@code np.zeros_like} initialisation)</li>
     *   <li>{@code dθ[i]  = atan2(det, dot)} of consecutive displacement vectors (i ≥ 1)</li>
     * </ul>
     */
    static float[][] cartesianToRelativePolar(double[][] cart) {
        int T = cart.length;
        int N = T - 1;
        float[][] out = new float[N][2];   // out[i][1] defaults to 0

        double[] dx = new double[N];
        double[] dy = new double[N];
        for (int i = 0; i < N; i++) {
            dx[i] = cart[i + 1][0] - cart[i][0];
            dy[i] = cart[i + 1][1] - cart[i][1];
        }

        // dr
        for (int i = 0; i < N; i++)
            out[i][0] = (float) Math.hypot(dx[i], dy[i]);

        // dθ  (signed angle between consecutive displacement vectors; index 0 stays 0)
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
     * Runs the two-input model on one trajectory's polar features and returns
     * the predicted α.
     *
     * <p>Tensor shapes:
     * <ul>
     *   <li>{@code "input"}  : {@code [1, seqLen, 2]}</li>
     *   <li>{@code "log_t"}  : {@code [1, 1]}, value = {@code log(seqLen)}</li>
     * </ul>
     * {@code seqLen = T - 1} (number of polar feature vectors).
     * {@code T} in the Python model equals {@code x.shape[1]} = {@code seqLen}.
     *
     * @param polar (T-1, 2) relative-polar features
     * @return predicted α, or {@link Float#NaN} on unexpected model output
     */
    private float runSingle(float[][] polar) throws OrtException {
        int seqLen = polar.length;

        // Feature tensor: [1, seqLen, 2], row-major
        FloatBuffer featBuf = FloatBuffer.allocate(seqLen * 2);
        for (float[] f : polar) { featBuf.put(f[0]); featBuf.put(f[1]); }
        featBuf.rewind();

        // log_t tensor: [1, 1]
        // T in the model = x.shape[1] = seqLen (length of polar input)
        FloatBuffer logTBuf = FloatBuffer.allocate(1);
        logTBuf.put((float) Math.log(seqLen));
        logTBuf.rewind();

        try (OnnxTensor featTensor = OnnxTensor.createTensor(
                 env, featBuf, new long[]{1L, (long) seqLen, 2L});
             OnnxTensor logTTensor = OnnxTensor.createTensor(
                 env, logTBuf, new long[]{1L, 1L})) {

            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(inputName, featTensor);
            inputs.put(logTName,  logTTensor);

            try (OrtSession.Result res = session.run(inputs)) {
                OnnxValue outVal = res.get(outputName)
                        .orElseThrow(() -> new OrtException(
                                "Output '" + outputName + "' not found"));
                return extractScalar(outVal.getValue());
            }
        }
    }

    /** Extracts the first float value from whatever shape the output tensor has. */
    private static float extractScalar(Object val) {
        if (val instanceof float[][]) return ((float[][]) val)[0][0];
        if (val instanceof float[])   return ((float[])   val)[0];
        if (val instanceof Float)     return (Float) val;
        System.err.println("[OnnxAlphaPredictor] unexpected output type: "
                + (val == null ? "null" : val.getClass().getName()));
        return Float.NaN;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

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

    private void printModelInfo() throws OrtException {
        System.err.println("[OnnxAlphaPredictor] === Model info ===");
        for (NodeInfo ni : session.getInputInfo().values())
            System.err.println("[OnnxAlphaPredictor]  INPUT  " + ni.getName()
                    + "  shape=" + Arrays.toString(
                            ((TensorInfo) ni.getInfo()).getShape()));
        for (NodeInfo ni : session.getOutputInfo().values())
            System.err.println("[OnnxAlphaPredictor]  OUTPUT " + ni.getName()
                    + "  shape=" + Arrays.toString(
                            ((TensorInfo) ni.getInfo()).getShape()));
    }

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) { }
    }
}
