package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.util.*;

/**
 * Runs the bundled analysis model <b>andi-j-analysis-latest</b> to estimate,
 * for every trajectory in an {@link ExperimentData}, both:
 * <ul>
 *   <li>the anomalous exponent α, and</li>
 *   <li>the diffusion-model class (ATTM / CTRW / FBM / LW / SBM).</li>
 * </ul>
 *
 * <h3>Model contract</h3>
 * The same input as user-supplied analysis models (see the <i>ML Models</i> wiki
 * page); any preprocessing lives inside the model.
 * <ul>
 *   <li>Input {@code (1, T, 2)} float32 — raw x, y positions, one row per frame.</li>
 *   <li>Output {@code alpha} : {@code (1, 1)} — the anomalous exponent.</li>
 *   <li>Output {@code model} : {@code (1, 5)} — class scores in the order of
 *       {@link DiffusionModelPanel#MODEL_NAMES}; the class is their argmax.</li>
 * </ul>
 *
 * <p>Results are stored in {@link ExperimentData#alphaCoeffs}
 * ({@code {α, NaN, NaN}}) and {@link ExperimentData#diffModel} (class index).
 * Tracks with fewer than 2 frames get {@code NaN} α and no model entry.</p>
 */
public class AnalysisPredictor implements MlPredictor {

    /** Classpath resource path for the bundled andi-j-analysis-latest model. */
    public static final String DEFAULT_RESOURCE = "/models/andi-j-analysis-latest.onnx";

    /** Number of diffusion-model classes. */
    public static final int N_MODELS = 5;

    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public AnalysisPredictor(File modelFile) throws OrtException, IOException {
        this(readFile(modelFile));
    }

    public static AnalysisPredictor fromResource() throws OrtException, IOException {
        InputStream is = AnalysisPredictor.class.getResourceAsStream(DEFAULT_RESOURCE);
        if (is == null)
            throw new FileNotFoundException(
                    "Bundled model andi-j-analysis-latest not found: " + DEFAULT_RESOURCE + "\n"
                    + "Copy the model into src/main/resources/models/ and rebuild.");
        byte[] bytes;
        try { bytes = readAllBytes(is); } finally { is.close(); }
        return new AnalysisPredictor(bytes);
    }

    public AnalysisPredictor(byte[] modelBytes) throws OrtException {
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelBytes, OnnxSessions.gpuIfAvailable());
        inputName = session.getInputNames().iterator().next();
    }

    // -----------------------------------------------------------------------
    // Inference
    // -----------------------------------------------------------------------

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

            float[][] out = runSingle(positions(spots));   // {alpha (1), scores (5)}
            alpha.put(id, new double[]{out[0][0], Double.NaN, Double.NaN});
            models.put(id, argmax(out[1]));
            if (listener != null) listener.trackDone();
        }

        exp.alphaCoeffs = alpha;
        exp.diffModel   = models;
    }

    private static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    /** The {@code (1, T, 2)} input tensor data: raw x, y per frame. */
    private static float[][][] positions(List<double[]> spots) {
        int T = spots.size();
        float[][][] xy = new float[1][T][2];
        for (int t = 0; t < T; t++) {
            xy[0][t][0] = (float) spots.get(t)[1];
            xy[0][t][1] = (float) spots.get(t)[2];
        }
        return xy;
    }

    // -----------------------------------------------------------------------
    // ONNX Runtime inference
    // -----------------------------------------------------------------------

    /**
     * Runs the model on one trajectory.
     *
     * @return {@code {alpha (length 1), class scores (length N_MODELS)}}
     */
    private float[][] runSingle(float[][][] input) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {
            return new float[][]{ row(res, "alpha"), row(res, "model") };
        }
    }

    /** First row of a named {@code (1, K)} output. */
    private static float[] row(OrtSession.Result res, String name) throws OrtException {
        OnnxValue v = res.get(name).orElseThrow(() -> new OrtException(
                "Output '" + name + "' not found in the andi-j-analysis-latest model"));
        Object val = v.getValue();
        if (val instanceof float[][]) return ((float[][]) val)[0];
        throw new OrtException("Unexpected type for output '" + name + "': "
                + (val == null ? "null" : val.getClass().getName()));
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
