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
 * per-frame change-point signal — read by the names {@code alpha}, {@code logD},
 * {@code cp} (falling back to output order 0, 1, 2). A single-output model
 * ({@link Kind#ALPHA}/{@link Kind#LOGD}/{@link Kind#CP}) exposes just that one.
 * See {@code Custom-Segmentation-Models.md} for the contract.</p>
 */
public class CustomSegPredictor implements Closeable {

    public enum Kind { COMBINED, ALPHA, LOGD, CP, MODELTYPE }

    /** Per-frame outputs; any field may be null if this model does not provide it. */
    public static final class Out {
        public double[] alpha, logD, cp;
        public int      phenom = -1;   // aggregated phenomenological-model class (−1 = none)
    }

    private final Kind          kind;
    private final OrtEnvironment env;
    private final OrtSession     session;
    private final String         inputName;
    private final List<String>   outNames;

    public CustomSegPredictor(File modelFile, Kind kind) throws OrtException, IOException {
        this(readFile(modelFile), kind);
    }

    public CustomSegPredictor(byte[] modelBytes, Kind kind) throws OrtException {
        this.kind = kind;
        env       = OrtEnvironment.getEnvironment();
        session   = env.createSession(modelBytes, OnnxSessions.gpuIfAvailable());
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
                out.alpha  = read(res, "alpha", 0, T);
                out.logD   = read(res, "logD",  1, T);
                out.cp     = read(res, "cp",    2, T);
                out.phenom = readPhenom(res, "model_type", 3);   // optional 4th output
            } else if (kind == Kind.MODELTYPE) {
                out.phenom = readPhenom(res, null, 0);
            } else {
                double[] v = read(res, null, 0, T);
                if (kind == Kind.ALPHA)     out.alpha = v;
                else if (kind == Kind.LOGD) out.logD  = v;
                else                        out.cp    = v;
            }
        }
        return out;
    }

    /** Reads a named (or index-fallback) output as a length-T per-frame array. */
    private double[] read(OrtSession.Result res, String name, int index, int T)
            throws OrtException {
        OnnxValue v = null;
        if (name != null)
            for (String o : outNames)
                if (o.equalsIgnoreCase(name)) { v = res.get(o).orElse(null); break; }
        if (v == null && index < outNames.size())
            v = res.get(outNames.get(index)).orElse(null);
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

    /**
     * Reads an optional per-frame class-probability output ({@code (1, T, K)} or
     * a per-trajectory {@code (1, K)}) and returns the aggregated class index
     * (argmax of the probabilities summed over frames), or −1 if absent.
     */
    private int readPhenom(OrtSession.Result res, String name, int index) throws OrtException {
        OnnxValue v = null;
        if (name != null)
            for (String o : outNames)
                if (o.equalsIgnoreCase(name)) { v = res.get(o).orElse(null); break; }
        if (v == null && index < outNames.size())
            v = res.get(outNames.get(index)).orElse(null);
        if (v == null) return -1;

        Object val = v.getValue();
        double[] acc = null;
        if (val instanceof float[][][]) {              // (1, T, K)
            for (float[] frame : ((float[][][]) val)[0]) {
                if (acc == null) acc = new double[frame.length];
                for (int k = 0; k < frame.length; k++) acc[k] += frame[k];
            }
        } else if (val instanceof float[][]) {          // (1, K)
            float[] row = ((float[][]) val)[0];
            acc = new double[row.length];
            for (int k = 0; k < row.length; k++) acc[k] = row[k];
        } else {
            return -1;
        }
        return (acc == null) ? -1 : UclStepPredictor.argmax(acc);
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
