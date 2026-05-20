package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Runs a pre-trained GRU model (ONNX format) to estimate the anomalous
 * exponent α for each trajectory in an {@link ExperimentData}.
 *
 * <h3>Model contract</h3>
 * <ul>
 *   <li>Input  shape : {@code (batch, SEQ_LEN, 1)}  – float32</li>
 *   <li>Output shape : {@code (batch, 1)} or {@code (batch,)} – float32, one α per sample</li>
 *   <li>Each input sequence must be normalised <em>per trajectory</em> before inference
 *       (zero mean, unit std; std replaced by 1 when zero – identical to
 *       {@code andi_datasets.utils_trajectories.normalize}).</li>
 * </ul>
 *
 * <h3>2-D trajectories</h3>
 * The model was trained on 1-D position sequences.  For 2-D trajectories the
 * model is applied independently to the X and Y position sequences, and the
 * 2-D result is their average:
 * <pre>
 *   α_2D = (α_X + α_Y) / 2
 * </pre>
 *
 * <h3>Tracks shorter than SEQ_LEN</h3>
 * Tracks with fewer than {@value #SEQ_LEN} localisation frames are skipped;
 * their {@link ExperimentData#alphaCoeffs} entry is set to {@code NaN}.
 * For longer tracks only the first {@value #SEQ_LEN} frames are used.
 */
public class OnnxAlphaPredictor implements Closeable {

    /** Number of frames the GRU model was trained on (must match Tmax). */
    public static final int SEQ_LEN = 30;

    /** Classpath resource path for the bundled default model. */
    public static final String DEFAULT_RESOURCE = "/models/GRU_exponents.onnx";

    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Load the ONNX model from a file on disk.
     *
     * @param modelFile path to the {@code .onnx} file
     * @throws OrtException if ONNX Runtime cannot load or validate the model
     */
    public OnnxAlphaPredictor(File modelFile) throws OrtException {
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelFile.getAbsolutePath(),
                                      new OrtSession.SessionOptions());
        inputName = session.getInputNames().iterator().next();
    }

    /**
     * Load the ONNX model from a classpath resource (e.g. the bundled default).
     *
     * @param resourcePath resource path, e.g. {@value #DEFAULT_RESOURCE}
     * @return a ready predictor
     * @throws OrtException  if ONNX Runtime rejects the model bytes
     * @throws IOException   if the resource stream cannot be read
     */
    public static OnnxAlphaPredictor fromResource(String resourcePath)
            throws OrtException, IOException {
        InputStream is = OnnxAlphaPredictor.class.getResourceAsStream(resourcePath);
        if (is == null)
            throw new FileNotFoundException("ONNX model resource not found: " + resourcePath);
        byte[] bytes;
        try {
            bytes = readAllBytes(is);
        } finally {
            is.close();
        }
        OrtEnvironment env     = OrtEnvironment.getEnvironment();
        OrtSession     session = env.createSession(bytes, new OrtSession.SessionOptions());
        return new OnnxAlphaPredictor(env, session);
    }

    /** Private constructor used by {@link #fromResource}. */
    private OnnxAlphaPredictor(OrtEnvironment env, OrtSession session) throws OrtException {
        this.env       = env;
        this.session   = session;
        this.inputName = session.getInputNames().iterator().next();
    }

    // -----------------------------------------------------------------------
    // Inference
    // -----------------------------------------------------------------------

    /**
     * Runs ML inference for every track in {@code exp} and stores results in
     * {@link ExperimentData#alphaCoeffs}.
     *
     * <p>Existing {@code alphaCoeffs} are replaced entirely.</p>
     *
     * @param exp the experiment whose tracks should be analysed
     * @throws OrtException if ONNX Runtime encounters an error during inference
     */
    public void computeForExperiment(ExperimentData exp) throws OrtException {

        // --- Collect eligible tracks -----------------------------------------
        List<Integer>  ids   = new ArrayList<>();
        List<double[]> xSeqs = new ArrayList<>();   // SEQ_LEN positions each
        List<double[]> ySeqs = new ArrayList<>();

        for (Map.Entry<Integer, List<double[]>> e : exp.tracks.entrySet()) {
            List<double[]> spots = e.getValue();
            if (spots.size() < SEQ_LEN) continue;    // too short – will get NaN
            ids.add(e.getKey());
            double[] xs = new double[SEQ_LEN];
            double[] ys = new double[SEQ_LEN];
            for (int i = 0; i < SEQ_LEN; i++) {
                // spots entry: [frame, x, y]
                xs[i] = spots.get(i)[1];
                ys[i] = spots.get(i)[2];
            }
            xSeqs.add(xs);
            ySeqs.add(ys);
        }

        // Initialise all entries to NaN (short tracks stay NaN)
        Map<Integer, double[]> alphaCoeffs = new LinkedHashMap<>();
        for (Integer id : exp.tracks.keySet())
            alphaCoeffs.put(id, new double[]{Double.NaN, Double.NaN, Double.NaN});

        if (!ids.isEmpty()) {
            float[] alphaX = runBatch(xSeqs);
            float[] alphaY = runBatch(ySeqs);

            for (int i = 0; i < ids.size(); i++) {
                double ax  = alphaX[i];
                double ay  = alphaY[i];
                double a2d;
                if (Double.isFinite(ax) && Double.isFinite(ay))
                    a2d = (ax + ay) / 2.0;
                else if (Double.isFinite(ax))
                    a2d = ax;
                else
                    a2d = ay;
                alphaCoeffs.put(ids.get(i), new double[]{a2d, ax, ay});
            }
        }

        exp.alphaCoeffs = alphaCoeffs;
    }

    /**
     * Sends a batch of position sequences through the model and returns one
     * predicted α per sequence.
     *
     * @param seqs list of {@code double[SEQ_LEN]} arrays (one per track)
     * @return     {@code float[seqs.size()]} predicted α values
     */
    private float[] runBatch(List<double[]> seqs) throws OrtException {
        int batchSize = seqs.size();

        // Build normalised float buffer: shape [batch, SEQ_LEN, 1]
        FloatBuffer buf = FloatBuffer.allocate(batchSize * SEQ_LEN);
        for (double[] seq : seqs) {
            float[] norm = normalize(seq);
            buf.put(norm);
        }
        buf.rewind();

        long[] shape = {batchSize, SEQ_LEN, 1};
        float[] results = new float[batchSize];

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, buf, shape);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            // get(0) returns the first (and only) output
            Object val = res.get(0).getValue();

            if (val instanceof float[][]) {
                // Output shape (batch, 1)
                float[][] arr = (float[][]) val;
                for (int i = 0; i < batchSize; i++)
                    results[i] = arr[i].length > 0 ? arr[i][0] : Float.NaN;
            } else if (val instanceof float[]) {
                // Output shape (batch,)
                float[] arr = (float[]) val;
                System.arraycopy(arr, 0, results, 0, Math.min(arr.length, batchSize));
            } else {
                // Unexpected output type – fill with NaN
                Arrays.fill(results, Float.NaN);
            }
        }

        return results;
    }

    // -----------------------------------------------------------------------
    // Normalisation
    // -----------------------------------------------------------------------

    /**
     * Per-sequence normalisation that mirrors
     * {@code andi_datasets.utils_trajectories.normalize}:
     * <pre>
     *   x_norm = (x − mean(x)) / std(x)
     * </pre>
     * where {@code std} is replaced by 1 when it is (near) zero.
     */
    static float[] normalize(double[] seq) {
        double mean = 0.0;
        for (double v : seq) mean += v;
        mean /= seq.length;

        double var = 0.0;
        for (double v : seq) {
            double d = v - mean;
            var += d * d;
        }
        double std = Math.sqrt(var / seq.length);
        if (std < 1e-12) std = 1.0;

        float[] out = new float[seq.length];
        for (int i = 0; i < seq.length; i++)
            out[i] = (float) ((seq[i] - mean) / std);
        return out;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /** Java-8-compatible InputStream → byte[] reader. */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = is.read(chunk)) != -1)
            buf.write(chunk, 0, len);
        return buf.toByteArray();
    }

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) { }
    }
}
