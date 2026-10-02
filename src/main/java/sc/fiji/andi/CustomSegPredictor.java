package sc.fiji.andi;

import ai.onnxruntime.*;

import java.io.*;
import java.util.*;

/**
 * Runs a user-supplied ("Load") segmentation ONNX model with <b>no</b>
 * pre/post-processing: the raw trajectory is fed as {@code (1, T, 2)} float32
 * (x, y positions) and the per-frame outputs are read verbatim.
 *
 * <p>A {@link Kind#COMBINED} model exposes three outputs — α, log₁₀(D) and a
 * per-frame change-point signal — plus an optional {@code diffusive_state} —
 * read by name (falling back to output order 0, 1, 2, 3). A single-output model
 * ({@link Kind#ALPHA}/{@link Kind#LOGD}/{@link Kind#CP}) exposes just that one.
 * When {@code useModelSegments} is set, the optional seg_* outputs are read
 * verbatim as the segments. See the <i>ML Models</i> wiki page for the contract.</p>
 */
public class CustomSegPredictor implements Closeable {

    public enum Kind { COMBINED, ALPHA, LOGD, CP, MODELTYPE }

    /** Per-frame outputs; any field may be null if this model does not provide it. */
    public static final class Out {
        public double[] alpha, logD, cp;
        public int[]    state;      // per-frame diffusive-state class (null if none)
        /** Segments read from the model's seg_* outputs, or null. */
        public List<Segment> segments;
    }

    private final Kind          kind;
    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;
    private final List<String>   outNames;
    /** Use the model's own seg_* outputs when it provides them. */
    private final boolean        useModelSegments;

    public CustomSegPredictor(File modelFile, Kind kind) throws OrtException, IOException {
        this(readFile(modelFile), kind, false);
    }

    public CustomSegPredictor(File modelFile, Kind kind, boolean useModelSegments)
            throws OrtException, IOException {
        this(readFile(modelFile), kind, useModelSegments);
    }

    public CustomSegPredictor(byte[] modelBytes, Kind kind, boolean useModelSegments)
            throws OrtException {
        this.kind = kind;
        this.useModelSegments = useModelSegments;
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelBytes,
                OnnxSessions.forParallelRuns(SegPredictor.MAX_PARALLEL_RUNS));
        inputName = session.getInputNames().iterator().next();
        outNames  = new ArrayList<>(session.getOutputNames());
    }

    /**
     * Runs the model on one trajectory.
     *
     * @param xy raw positions, shape {@code (T, 2)} = [[x0,y0], …]
     */
    public Out run(double[][] xy) throws OrtException {
        int T = xy.length;
        float[][][] in = new float[1][T][2];
        for (int t = 0; t < T; t++) {
            in[0][t][0] = (float) xy[t][0];
            in[0][t][1] = (float) xy[t][1];
        }

        Out out = new Out();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, in);
             OrtSession.Result res = session.run(Collections.singletonMap(inputName, tensor))) {
            if (kind == Kind.COMBINED) {
                out.alpha = read(res, "alpha", 0, T);
                out.logD  = read(res, "logD",  1, T);
                out.cp    = read(res, "cp",    2, T);
                out.state = requiredState(res, T);
                if (useModelSegments)
                    out.segments = SegPredictor.readModelSegments(res, outNames, T);
            } else if (kind == Kind.MODELTYPE) {
                out.state = readStatePerFrame(res, 0, T, (String) null);
            } else {
                double[] v = read(res, null, 0, T);
                if (kind == Kind.ALPHA)     out.alpha = v;
                else if (kind == Kind.LOGD) out.logD  = v;
                else                        out.cp    = v;
            }
        }
        return out;
    }

    /**
     * Resolves an output by any of {@code names} (case-insensitive), falling back
     * to position {@code index}. Returns {@code null} if neither matches.
     */
    private OnnxValue find(OrtSession.Result res, int index, String... names)
            throws OrtException {
        for (String n : names)
            if (n != null)
                for (String o : outNames)
                    if (o.equalsIgnoreCase(n)) {
                        OnnxValue v = res.get(o).orElse(null);
                        if (v != null) return v;
                    }
        if (index < outNames.size()) return res.get(outNames.get(index)).orElse(null);
        return null;
    }

    /** Reads a named (or index-fallback) output as a length-T per-frame array. */
    private double[] read(OrtSession.Result res, String name, int index, int T)
            throws OrtException {
        OnnxValue v = find(res, index, name);
        if (v == null)
            throw new OrtException("Custom model output '" + name + "' (index " + index
                    + ") not found");

        Object val = v.getValue();
        double[] out = new double[T];
        if (val instanceof float[][][]) {          // (1, T, 1)
            float[][][] a = (float[][][]) val;
            for (int t = 0; t < T && t < a[0].length; t++) out[t] = a[0][t][0];
        } else if (val instanceof float[][]) {     // (1, T)
            float[][] a = (float[][]) val;
            for (int t = 0; t < T && t < a[0].length; t++) out[t] = a[0][t];
        } else if (val instanceof float[]) {       // (T,)
            float[] a = (float[]) val;
            for (int t = 0; t < T && t < a.length; t++) out[t] = a[t];
        } else {
            throw new OrtException("Unexpected custom-model output type: "
                    + (val == null ? "null" : val.getClass().getName()));
        }
        return out;
    }

    /** The diffusive state, required for a combined model. */
    private int[] requiredState(OrtSession.Result res, int T) throws OrtException {
        int[] s = readStatePerFrame(res, 3, T, "diffusive_state");
        if (s == null)
            throw new OrtException("Custom combined model is missing the required output "
                    + "diffusive_state. It must provide alpha, logD, cp and diffusive_state.");
        return s;
    }

    /**
     * Reads an optional diffusive-state output into a per-frame class array:
     * {@code (1, T, K)} → per-frame argmax; {@code (1, T)} → per-frame rounded
     * value. Returns {@code null} if the output is absent.
     */
    private int[] readStatePerFrame(OrtSession.Result res, int index, int T, String... names)
            throws OrtException {
        OnnxValue v = find(res, index, names);
        if (v == null) return null;

        Object val = v.getValue();
        int[] out = new int[T];
        if (val instanceof float[][][]) {              // (1, T, K) → argmax per frame
            float[][] frames = ((float[][][]) val)[0];
            for (int t = 0; t < T && t < frames.length; t++) {
                float[] f = frames[t];
                int best = 0;
                for (int k = 1; k < f.length; k++) if (f[k] > f[best]) best = k;
                out[t] = best;
            }
        } else if (val instanceof float[][]) {          // (1, T) → rounded value
            float[] row = ((float[][]) val)[0];
            for (int t = 0; t < T && t < row.length; t++) out[t] = Math.max(0, Math.round(row[t]));
        } else {
            return null;
        }
        return out;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int len;
            while ((len = fis.read(chunk)) != -1) buf.write(chunk, 0, len);
            return buf.toByteArray();
        }
    }

    @Override
    public void close() {
        try { session.close(); } catch (OrtException ignored) {}
    }
}
