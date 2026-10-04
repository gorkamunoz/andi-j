package sc.fiji.andi;

import java.io.File;

/**
 * The user's choice of segmentation ML model(s), assembled by
 * {@link SegModelOptionsPanel} and consumed by {@link SegmentationRunner}.
 *
 * <ul>
 *   <li>{@code useDefault} – run the bundled andi-j-seg-latest model; segments
 *       come from its seg_* outputs.</li>
 *   <li>Load / combined – one custom ONNX providing α, log₁₀(D), CP and the
 *       diffusive state; {@code useModelSegments} selects its seg_* outputs.</li>
 *   <li>Load / per-output – a custom ONNX per output; any {@code null} file is
 *       filled from the default model.</li>
 * </ul>
 */
public final class SegModelConfig {

    public final boolean useDefault;
    /** Use the model's own seg_* outputs instead of deriving segments from cp. */
    public final boolean useModelSegments;
    public final boolean combined;     // Load: one file for all outputs vs per-output
    public final File    combinedFile; // combined mode
    public final File    alphaFile;    // per-output mode (any may be null → default)
    public final File    dFile;
    public final File    cpFile;
    public final File    stateFile;   // per-output diffusive state (optional)

    private SegModelConfig(boolean useDefault, boolean useModelSegments, boolean combined,
                           File combinedFile, File alphaFile, File dFile, File cpFile,
                           File stateFile) {
        this.useDefault       = useDefault;
        this.useModelSegments = useModelSegments;
        this.combined     = combined;
        this.combinedFile = combinedFile;
        this.alphaFile    = alphaFile;
        this.dFile        = dFile;
        this.cpFile       = cpFile;
        this.stateFile   = stateFile;
    }

    public static SegModelConfig defaultModel() {
        return new SegModelConfig(true, true, false, null, null, null, null, null);
    }

    public static SegModelConfig combined(File file, boolean useModelSegments) {
        return new SegModelConfig(false, useModelSegments, true, file, null, null, null, null);
    }

    public static SegModelConfig perOutput(File alpha, File d, File cp, File state) {
        return new SegModelConfig(false, false, false, null, alpha, d, cp, state);
    }

    /** True in per-output mode when at least one α/D/CP output falls back to the default. */
    public boolean needsDefault() {
        return !useDefault && !combined && (alphaFile == null || dFile == null || cpFile == null);
    }

    /** Stable descriptor used in the prediction cache key. */
    public String key() {
        if (useDefault) return "default";
        if (combined)   return "combined:" + path(combinedFile) + ";segs=" + useModelSegments;
        return "perout:a=" + path(alphaFile) + ";d=" + path(dFile) + ";cp=" + path(cpFile)
             + ";state=" + path(stateFile);
    }

    private static String path(File f) { return f == null ? "-" : f.getAbsolutePath(); }
}
