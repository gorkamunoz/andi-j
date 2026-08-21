package sc.fiji.andi;

import ai.onnxruntime.OrtSession;

/**
 * Shared ONNX Runtime session options for all AnDi-J predictors: use every CPU
 * core for intra-op work, and the CUDA GPU execution provider when it is
 * available. With the CPU-only {@code onnxruntime} build (or no compatible GPU)
 * {@code addCUDA} fails and inference falls back to CPU; swapping the dependency
 * to {@code onnxruntime_gpu} (with matching CUDA/cuDNN) then runs on the GPU.
 */
final class OnnxSessions {

    private OnnxSessions() {}

    static OrtSession.SessionOptions gpuIfAvailable() {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        try {
            opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors()));
            opts.addCUDA(0);   // device 0; CPU remains the automatic fallback
        } catch (Throwable t) {
            // Expected on the released CPU-only build — a diagnostic, not an error.
            AndiLog.debug("GPU (CUDA) unavailable, using CPU: " + t.getMessage());
        }
        return opts;
    }
}
