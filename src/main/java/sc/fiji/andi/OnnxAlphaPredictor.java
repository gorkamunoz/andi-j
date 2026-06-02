package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
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
    private final String         outputName;

    /** Set to false after the first batch so diagnostics print only once. */
    private boolean firstBatch = true;

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
        env        = OrtEnvironment.getEnvironment();
        session    = env.createSession(modelFile.getAbsolutePath(),
                                       new OrtSession.SessionOptions());
        inputName  = session.getInputNames().iterator().next();
        outputName = session.getOutputNames().iterator().next();
        printModelInfo();
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
        this.env        = env;
        this.session    = session;
        this.inputName  = session.getInputNames().iterator().next();
        this.outputName = session.getOutputNames().iterator().next();
        printModelInfo();
    }

    /** Prints input/output names and shapes to stderr for diagnosis. */
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
        System.err.println("[OnnxAlphaPredictor]  Using output: " + outputName);
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

            // Y: only infer for tracks whose Y sequence has real variance.
            // Tracks with constant Y (e.g. 1-D data where Y=0) produce an
            // all-zero normalised input that is out-of-distribution for the
            // model and would corrupt α_2D.  Those tracks get αY = NaN and
            // α_2D falls back to αX alone.
            float[] alphaY = new float[ids.size()];
            Arrays.fill(alphaY, Float.NaN);
            List<Integer>  yIdx  = new ArrayList<>();   // positions in ids[]
            List<double[]> yVary = new ArrayList<>();   // only seqs with variance
            for (int i = 0; i < ySeqs.size(); i++) {
                if (hasVariance(ySeqs.get(i))) {
                    yIdx.add(i);
                    yVary.add(ySeqs.get(i));
                }
            }
            if (!yVary.isEmpty()) {
                float[] yPreds = runBatch(yVary);
                for (int j = 0; j < yIdx.size(); j++)
                    alphaY[yIdx.get(j)] = yPreds[j];
            }

            for (int i = 0; i < ids.size(); i++) {
                double ax  = alphaX[i];
                double ay  = alphaY[i];
                double a2d;
                if (Double.isFinite(ax) && Double.isFinite(ay))
                    a2d = (ax + ay) / 2.0;
                else if (Double.isFinite(ax))
                    a2d = ax;   // Y had no variance (1-D data) – use X only
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
     * Input tensor shape : (batchSize, SEQ_LEN, 1) – built as float[][][]
     * so the Java array layout unambiguously matches the ONNX row-major layout.
     *
     * @param seqs list of {@code double[SEQ_LEN]} arrays (one per track)
     * @return     {@code float[seqs.size()]} predicted α values
     */
    private float[] runBatch(List<double[]> seqs) throws OrtException {
        int batchSize = seqs.size();

        // Build a 3-D Java array: [batch][time][feature=1]
        // OnnxTensor.createTensor(env, float[][][]) maps directly to shape (B,T,1)
        // with no ambiguity about memory layout or buffer position.
        float[][][] input = new float[batchSize][SEQ_LEN][1];
        for (int b = 0; b < batchSize; b++) {
            float[] norm = normalize(seqs.get(b));
            for (int t = 0; t < SEQ_LEN; t++)
                input[b][t][0] = norm[t];
        }

        // ── Diagnostics (first batch only) ──────────────────────────────
        if (firstBatch) {
            float[] first = input[0][0].length > 0 ? extractRow(input[0]) : new float[0];
            System.err.println("[OnnxAlphaPredictor] === First batch (X) ===");
            System.err.println("[OnnxAlphaPredictor]  batchSize=" + batchSize
                    + "  seqLen=" + SEQ_LEN);
            System.err.println("[OnnxAlphaPredictor]  raw positions (first 5): "
                    + rawPositions(seqs.get(0)));
            System.err.println("[OnnxAlphaPredictor]  normalised  (first 5): "
                    + Arrays.toString(Arrays.copyOf(first, Math.min(5, first.length))));
        }

        float[] results = new float[batchSize];

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result res = session.run(
                     Collections.singletonMap(inputName, tensor))) {

            // Use the stored output name (avoids index-ordering ambiguity when
            // tf2onnx exports multiple outputs such as GRU hidden states).
            // In ORT 1.17 Java, get(String) returns Optional<OnnxValue>.
            OnnxValue outVal = res.get(outputName)
                    .orElseThrow(() -> new OrtException(
                            "Output '" + outputName + "' not found in model result"));
            Object val = outVal.getValue();

            if (firstBatch)
                System.err.println("[OnnxAlphaPredictor]  output type: "
                        + (val == null ? "null" : val.getClass().getName()));

            if (val instanceof float[][]) {
                // Most common: output shape (batch, 1)
                float[][] arr = (float[][]) val;
                if (firstBatch)
                    System.err.println("[OnnxAlphaPredictor]  output[0].length=" + arr[0].length
                            + "  first value=" + arr[0][0]);
                for (int i = 0; i < batchSize; i++)
                    results[i] = (arr[i].length > 0) ? arr[i][0] : Float.NaN;
            } else if (val instanceof float[]) {
                // Output shape (batch,) after squeeze
                float[] arr = (float[]) val;
                if (firstBatch)
                    System.err.println("[OnnxAlphaPredictor]  output length=" + arr.length
                            + "  first value=" + arr[0]);
                System.arraycopy(arr, 0, results, 0, Math.min(arr.length, batchSize));
            } else {
                System.err.println("[OnnxAlphaPredictor] unexpected output type: "
                        + (val == null ? "null" : val.getClass().getName()));
                Arrays.fill(results, Float.NaN);
            }
        }

        firstBatch = false;
        return results;
    }

    // -----------------------------------------------------------------------
    // Normalisation
    // -----------------------------------------------------------------------

    /**
     * Returns true when the sequence has non-trivial variance (std ≥ 1e-12).
     * Sequences that fail this check (e.g. constant-zero Y from 1-D data)
     * would normalise to all-zeros and produce meaningless model output.
     */
    static boolean hasVariance(double[] seq) {
        double mean = 0;
        for (double v : seq) mean += v;
        mean /= seq.length;
        double var = 0;
        for (double v : seq) { double d = v - mean; var += d * d; }
        return Math.sqrt(var / seq.length) >= 1e-12;
    }

    /**
     * Per-sequence normalisation that exactly mirrors
     * {@code andi_datasets.utils_trajectories.normalize}:
     *
     * <ol>
     *   <li>Compute displacements: {@code d[i] = x[i+1] − x[i]}</li>
     *   <li>Divide each displacement by the population std of all displacements</li>
     *   <li>Cumulative-sum the normalised displacements</li>
     *   <li>Prepend a zero → result always starts at 0</li>
     * </ol>
     *
     * <p>Note: the centering step in the Python source ({@code trajs − mean})
     * cancels out when displacements are taken, so it is omitted here.</p>
     */
    static float[] normalize(double[] seq) {
        int T = seq.length;
        if (T < 2) return new float[T];

        // 1. Displacements
        double[] disp = new double[T - 1];
        for (int i = 0; i < T - 1; i++) disp[i] = seq[i + 1] - seq[i];

        // 2. Population std of displacements  (matches np.std default ddof=0)
        double dMean = 0;
        for (double d : disp) dMean += d;
        dMean /= disp.length;
        double dVar = 0;
        for (double d : disp) { double diff = d - dMean; dVar += diff * diff; }
        double dStd = Math.sqrt(dVar / disp.length);
        if (dStd < 1e-12) dStd = 1.0;

        // 3–4. Normalised displacements → cumsum with leading zero
        float[] out = new float[T];
        out[0] = 0.0f;
        for (int i = 1; i < T; i++)
            out[i] = out[i - 1] + (float) (disp[i - 1] / dStd);

        return out;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /** Flatten float[SEQ_LEN][1] → float[SEQ_LEN] for printing. */
    private static float[] extractRow(float[][] row2d) {
        float[] out = new float[row2d.length];
        for (int i = 0; i < row2d.length; i++) out[i] = row2d[i][0];
        return out;
    }

    /** Format the first 5 raw (un-normalised) positions for printing. */
    private static String rawPositions(double[] seq) {
        int n = Math.min(5, seq.length);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.4f", seq[i]));
        }
        sb.append("]");
        return sb.toString();
    }

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
