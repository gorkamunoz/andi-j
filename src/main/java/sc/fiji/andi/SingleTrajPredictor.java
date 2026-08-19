package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.util.*;

/**
 * Runs the V2 combined single-trajectory model (exported to ONNX) to estimate,
 * for every trajectory in an {@link ExperimentData}, both:
 * <ul>
 *   <li>the anomalous exponent α (the soft-routed prediction), and</li>
 *   <li>the diffusion-model class (ATTM / CTRW / FBM / LW / SBM).</li>
 * </ul>
 *
 * <h3>Model contract</h3>
 * <ul>
 *   <li>Input  {@code "input"}  : {@code (1, T-1, 2)} float32 relative-polar features
 *       (same {@code cartesian_to_relative_polar} preprocessing as before).</li>
 *   <li>Output {@code "output"} : {@code (1, 11)} float32
 *       <ul>
 *         <li>cols 0-4  : per-type α regressions</li>
 *         <li>cols 5-9  : classification logits (one per diffusion model)</li>
 *         <li>col  10   : soft-routed α  → used as the α prediction</li>
 *       </ul>
 *       Diffusion-model class = {@code argmax(output[5..9])}.</li>
 * </ul>
 *
 * <p>Results are stored in {@link ExperimentData#alphaCoeffs}
 * ({@code {α, NaN, NaN}}) and {@link ExperimentData#diffModel} (class index).
 * Tracks with fewer than 2 frames get {@code NaN} α and no model entry.</p>
 */
public class SingleTrajPredictor implements MlPredictor {

    /** Classpath resource path for the bundled V2 model. */
    public static final String DEFAULT_RESOURCE = "/models/single_traj_v2.onnx";

    /** Number of diffusion-model classes (= classification logits). */
    public static final int N_MODELS = 5;

    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;
    private final String         outputName;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public SingleTrajPredictor(File modelFile) throws OrtException, IOException {
        this(readFile(modelFile));
    }

    public static SingleTrajPredictor fromResource() throws OrtException, IOException {
        InputStream is = SingleTrajPredictor.class.getResourceAsStream(DEFAULT_RESOURCE);
        if (is == null)
            throw new FileNotFoundException(
                    "Model resource not found: " + DEFAULT_RESOURCE + "\n"
                    + "Run ML_analysis/pythons_for_plugin/convert_single_traj_v2_to_onnx.py, "
                    + "copy single_traj_v2.onnx to src/main/resources/models/ and rebuild.");
        byte[] bytes;
        try { bytes = readAllBytes(is); } finally { is.close(); }
        return new SingleTrajPredictor(bytes);
    }

    public SingleTrajPredictor(byte[] modelBytes) throws OrtException {
        env        = OrtEnvironment.getEnvironment();
        session    = env.createSession(modelBytes, OnnxSessions.gpuIfAvailable());
        inputName  = session.getInputNames().iterator().next();
        outputName = session.getOutputNames().iterator().next();
    }

    // -----------------------------------------------------------------------
    // Inference
    // -----------------------------------------------------------------------

    public void computeForExperiment(ExperimentData exp) throws OrtException {
        computeForExperiment(exp, null);
    }

    /**
     * Runs inference for every track and stores α in
     * {@link ExperimentData#alphaCoeffs} and the model class in
     * {@link ExperimentData#diffModel}.  Existing values are replaced.
     */
    @Override
    public void computeForExperiment(ExperimentData exp, MlPredictor.ProgressListener listener)
            throws OrtException {
        Map<Integer, double[]> alpha  = new LinkedHashMap<>();
        Map<Integer, Integer>  models = new LinkedHashMap<>();

        for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
            int            id    = e.getKey();
            List<double[]> spots = e.getValue();

            if (spots.size() < 2) {
                alpha.put(id, new double[]{Double.NaN, Double.NaN, Double.NaN});
                if (listener != null) listener.trackDone();
                continue;
            }

            float[][] polar = cartesianToRelativePolar(extractXY(spots));
            float[]   out   = runSingle(polar);   // length 11

            alpha.put(id, new double[]{out[10], Double.NaN, Double.NaN});
            models.put(id, argmax(out, 5, 5 + N_MODELS));
            if (listener != null) listener.trackDone();
        }

        exp.alphaCoeffs = alpha;
        exp.diffModel   = models;
    }

    /** Index of the largest value in {@code v[from..to)}, offset back to [0, N_MODELS). */
    private static int argmax(float[] v, int from, int to) {
        int best = from;
        for (int i = from + 1; i < to && i < v.length; i++)
            if (v[i] > v[best]) best = i;
        return best - from;
    }

    // -----------------------------------------------------------------------
    // Preprocessing  (identical to the previous predictor)
    // -----------------------------------------------------------------------

    private static double[][] extractXY(List<double[]> spots) {
        int T = spots.size();
        double[][] out = new double[T][2];
        for (int i = 0; i < T; i++) {
            out[i][0] = spots.get(i)[1];
            out[i][1] = spots.get(i)[2];
        }
        return out;
    }

    /**
     * Mirrors {@code cartesian_to_relative_polar}: (T,2) Cartesian → (T-1,2)
     * {@code [dr, dθ]} (dθ[0]=0).
     */
    static float[][] cartesianToRelativePolar(double[][] cart) {
        int T = cart.length;
        int N = T - 1;
        float[][] out = new float[N][2];

        double[] dx = new double[N];
        double[] dy = new double[N];
        for (int i = 0; i < N; i++) {
            dx[i] = cart[i + 1][0] - cart[i][0];
            dy[i] = cart[i + 1][1] - cart[i][1];
        }
        for (int i = 0; i < N; i++)
            out[i][0] = (float) Math.hypot(dx[i], dy[i]);
        for (int i = 1; i < N; i++) {
            double x1 = dx[i - 1], y1 = dy[i - 1];
            double x2 = dx[i],     y2 = dy[i];
            out[i][1] = (float) Math.atan2(x1 * y2 - y1 * x2, x1 * x2 + y1 * y2);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // ONNX Runtime inference
    // -----------------------------------------------------------------------

    /**
     * Runs the model on one trajectory's polar features.
     *
     * @param polar (T-1, 2) features
     * @return the 11-element output row, or all-NaN on unexpected output
     */
    private float[] runSingle(float[][] polar) throws OrtException {
        int L = polar.length;
        float[][][] input = new float[1][L][2];
        for (int t = 0; t < L; t++) {
            input[0][t][0] = polar[t][0];
            input[0][t][1] = polar[t][1];
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            OnnxValue outVal = res.get(outputName)
                    .orElseThrow(() -> new OrtException(
                            "Output '" + outputName + "' not found"));
            Object val = outVal.getValue();
            if (val instanceof float[][]) return ((float[][]) val)[0];   // (1, 11)
            if (val instanceof float[])   return (float[]) val;          // (11,)
            System.err.println("[SingleTrajPredictor] unexpected output type: "
                    + (val == null ? "null" : val.getClass().getName()));
            float[] nan = new float[11];
            Arrays.fill(nan, Float.NaN);
            return nan;
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

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

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) { }
    }
}
