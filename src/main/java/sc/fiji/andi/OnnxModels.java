package sc.fiji.andi;

import ai.onnxruntime.OrtException;

import java.io.*;
import java.util.List;

/**
 * Helpers shared by the ONNX predictors: reading model bytes, building the
 * {@code (T, 2)} position input from a track, and the arg-max of class scores.
 */
final class OnnxModels {

    private OnnxModels() {}

    /** The bytes of a model file. */
    static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) { return readAll(fis); }
    }

    /** The bytes of a bundled model on the classpath, e.g. {@code /models/andi-j-seg-latest.onnx}. */
    static byte[] loadResource(String path, String modelName) throws IOException {
        InputStream is = OnnxModels.class.getResourceAsStream(path);
        if (is == null)
            throw new FileNotFoundException(
                    "Bundled model " + modelName + " not found: " + path + "\n"
                    + "Copy the model into src/main/resources/models/ and rebuild.");
        try { return readAll(is); } finally { is.close(); }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = is.read(chunk)) != -1) buf.write(chunk, 0, len);
        return buf.toByteArray();
    }

    /** The raw x, y positions of a track ({@code {frame, x, y}} spots), one row per frame. */
    static float[][] positions(List<double[]> spots) {
        int T = spots.size();
        float[][] xy = new float[T][2];
        for (int t = 0; t < T; t++) {
            xy[t][0] = (float) spots.get(t)[1];
            xy[t][1] = (float) spots.get(t)[2];
        }
        return xy;
    }

    /**
     * Fails unless a per-frame output has one value per frame: a model that returns
     * {@code T−1} values (one per displacement) or a length fixed at export would
     * otherwise give silently wrong results.
     */
    static void checkFrames(String output, int got, int T) throws OrtException {
        if (got != T)
            throw new OrtException("Output '" + output + "' has " + got + " values for a "
                    + "trajectory of " + T + " frames; per-frame outputs must have length T "
                    + "(see the ML Models page of the wiki).");
    }

    /** Index of the largest value (the first one on ties). */
    static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }
}
