package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.util.*;

/**
 * Runs a user-supplied ONNX model that predicts a single quantity per
 * trajectory — either the anomalous exponent α ({@link Mode#ALPHA}) or the
 * diffusion-model class ({@link Mode#MODEL}).
 *
 * <h3>Model contract for custom models</h3>
 * <ul>
 *   <li>Input: one tensor of shape {@code (1, T, 2)} float32 — the raw
 *       trajectory as {@code [x, y]} coordinates (one row per frame). The
 *       sequence-length axis may be dynamic. Any further representation the
 *       model needs (displacements, polar, …) is up to the model itself.</li>
 *   <li>Output (ALPHA): a tensor whose first element is the predicted α, e.g.
 *       shape {@code (1, 1)} or {@code (1,)}.</li>
 *   <li>Output (MODEL): a tensor of per-class scores/logits, e.g. {@code (1, K)};
 *       the predicted class is {@code argmax}. A scalar output is treated as a
 *       class index (rounded).</li>
 * </ul>
 *
 * Only the relevant {@link ExperimentData} map is written:
 * {@code alphaCoeffs} for ALPHA, {@code diffModel} for MODEL. Trajectories run in
 * parallel ({@link ParallelTracks}).
 */
public class CustomAnalysisPredictor implements MlPredictor {

    public enum Mode { ALPHA, MODEL }

    private final Mode         mode;
    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;
    private final String         outputName;

    public CustomAnalysisPredictor(File modelFile, Mode mode) throws OrtException, IOException {
        this(OnnxModels.readFile(modelFile), mode);
    }

    public CustomAnalysisPredictor(byte[] modelBytes, Mode mode) throws OrtException {
        this.mode  = mode;
        env        = OrtEnvironment.getEnvironment();
        session    = env.createSession(modelBytes, OnnxSessions.forParallelRuns(OnnxSessions.PARALLEL_RUNS));
        inputName  = session.getInputNames().iterator().next();
        outputName = session.getOutputNames().iterator().next();
    }

    // -----------------------------------------------------------------------

    @Override
    public void computeForExperiment(ExperimentData exp, MlPredictor.ProgressListener listener)
            throws OrtException {
        // raw model output per track; null for tracks too short to run
        Map<Integer, float[]> outs = ParallelTracks.map(exp.tracks,
                spots -> spots.size() < 2 ? null : runSingle(OnnxModels.positions(spots)), listener);

        Map<Integer, double[]> alpha  = (mode == Mode.ALPHA) ? new LinkedHashMap<>() : null;
        Map<Integer, Integer>  models = (mode == Mode.MODEL) ? new LinkedHashMap<>() : null;
        for (Map.Entry<Integer, float[]> e : outs.entrySet()) {
            int     id  = e.getKey();
            float[] out = e.getValue();
            if (mode == Mode.ALPHA) {
                alpha.put(id, new double[]{out != null && out.length > 0 ? out[0] : Double.NaN,
                                           Double.NaN, Double.NaN});
            } else if (out != null) {
                models.put(id, out.length > 1 ? OnnxModels.argmax(out)
                                              : Math.round(out.length > 0 ? out[0] : 0));
            }
        }

        if (mode == Mode.ALPHA) exp.alphaCoeffs = alpha;
        else                    exp.diffModel   = models;
    }

    // -----------------------------------------------------------------------

    /** Runs the model on one trajectory ({@code (T, 2)} raw positions). */
    private float[] runSingle(float[][] xy) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][][]{ xy });
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {
            OnnxValue outVal = res.get(outputName)
                    .orElseThrow(() -> new OrtException("Output '" + outputName + "' not found"));
            return flattenRow(outVal.getValue());
        }
    }

    /** Flattens the first row of whatever shape the output tensor has. */
    private static float[] flattenRow(Object val) {
        if (val instanceof float[][][]) {              // (1, K, 1) or (1, 1, K)
            float[][] r = ((float[][][]) val)[0];
            float[] out = new float[r.length];
            for (int i = 0; i < r.length; i++) out[i] = r[i].length > 0 ? r[i][0] : Float.NaN;
            return out;
        }
        if (val instanceof float[][]) return ((float[][]) val)[0];   // (1, K)
        if (val instanceof float[])   return (float[]) val;          // (K,)
        if (val instanceof Float)     return new float[]{(Float) val};
        AndiLog.warn("CustomAnalysisPredictor: unexpected output type: "
                + (val == null ? "null" : val.getClass().getName()));
        return new float[]{Float.NaN};
    }

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) { }
    }
}
