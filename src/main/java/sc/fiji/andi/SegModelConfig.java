package sc.fiji.andi;

import java.io.File;

/**
 * The user's choice of segmentation ML model(s), assembled by
 * {@link SegModelOptionsPanel} and consumed by {@link SegmentationRunner}.
 *
 * <ul>
 *   <li>{@code useDefault} – run the bundled UNet3+ model (with {@code tta}).</li>
 *   <li>Load / combined – one custom ONNX providing α, log₁₀(D) and CP.</li>
 *   <li>Load / per-output – a custom ONNX per output; any {@code null} file is
 *       filled from the default model.</li>
 * </ul>
 */
public final class SegModelConfig {

    public final boolean useDefault;
    public final boolean tta;          // default model only
    public final boolean combined;     // Load: one file for all outputs vs per-output
    public final File    combinedFile; // combined mode
    public final File    alphaFile;    // per-output mode (any may be null → default)
    public final File    dFile;
    public final File    cpFile;
    public final File    phenomFile;   // per-output phenomenological-model (optional)

    private SegModelConfig(boolean useDefault, boolean tta, boolean combined, File combinedFile,
                           File alphaFile, File dFile, File cpFile, File phenomFile) {
        this.useDefault   = useDefault;
        this.tta          = tta;
        this.combined     = combined;
        this.combinedFile = combinedFile;
        this.alphaFile    = alphaFile;
        this.dFile        = dFile;
        this.cpFile       = cpFile;
        this.phenomFile   = phenomFile;
    }

    public static SegModelConfig defaultModel(boolean tta) {
        return new SegModelConfig(true, tta, false, null, null, null, null, null);
    }

    public static SegModelConfig combined(File file) {
        return new SegModelConfig(false, false, true, file, null, null, null, null);
    }

    public static SegModelConfig perOutput(File alpha, File d, File cp, File phenom, boolean tta) {
        return new SegModelConfig(false, tta, false, null, alpha, d, cp, phenom);
    }

    /** True in per-output mode when at least one α/D/CP output falls back to the default. */
    public boolean needsDefault() {
        return !useDefault && !combined && (alphaFile == null || dFile == null || cpFile == null);
    }

    /** Stable descriptor used in the prediction cache key. */
    public String key() {
        if (useDefault) return "default:tta=" + tta;
        if (combined)   return "combined:" + path(combinedFile);
        return "perout:a=" + path(alphaFile) + ";d=" + path(dFile) + ";cp=" + path(cpFile)
             + ";ph=" + path(phenomFile) + ";tta=" + tta;
    }

    private static String path(File f) { return f == null ? "-" : f.getAbsolutePath(); }
}
