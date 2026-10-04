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
 * Tracks with fewer than 2 frames get {@code NaN} α and no model entry.
 * Trajectories run in parallel ({@link ParallelTracks}).</p>
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

    public static AnalysisPredictor fromResource() throws OrtException, IOException {
        return new AnalysisPredictor(
                OnnxModels.loadResource(DEFAULT_RESOURCE, "andi-j-analysis-latest"));
    }

    public AnalysisPredictor(byte[] modelBytes) throws OrtException {
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelBytes, OnnxSessions.forParallelRuns(OnnxSessions.PARALLEL_RUNS));
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
        // {alpha (1), scores (5)} per track; null for tracks too short to run
        Map<Integer, float[][]> outs = ParallelTracks.map(exp.tracks,
                spots -> spots.size() < 2 ? null : runSingle(OnnxModels.positions(spots)), listener);

        Map<Integer, double[]> alpha  = new LinkedHashMap<>();
        Map<Integer, Integer>  models = new LinkedHashMap<>();
        for (Map.Entry<Integer, float[][]> e : outs.entrySet()) {
            float[][] out = e.getValue();
            if (out == null) {
                alpha.put(e.getKey(), new double[]{Double.NaN, Double.NaN, Double.NaN});
                continue;
            }
            alpha.put(e.getKey(), new double[]{out[0][0], Double.NaN, Double.NaN});
            models.put(e.getKey(), OnnxModels.argmax(out[1]));
        }

        exp.alphaCoeffs = alpha;
        exp.diffModel   = models;
    }

    // -----------------------------------------------------------------------
    // ONNX Runtime inference
    // -----------------------------------------------------------------------

    /**
     * Runs the model on one trajectory.
     *
     * @param xy raw positions, shape {@code (T, 2)}
     * @return {@code {alpha (length 1), class scores (length N_MODELS)}}
     */
    private float[][] runSingle(float[][] xy) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][][]{ xy });
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

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) { }
    }
}
