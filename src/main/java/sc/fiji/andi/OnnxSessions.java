package sc.fiji.andi;

import ai.onnxruntime.OrtSession;

/**
 * Shared ONNX Runtime session options for all AnDi-J predictors, with the CUDA
 * GPU execution provider when it is available. With the CPU-only
 * {@code onnxruntime} build (or no compatible GPU) {@code addCUDA} fails and
 * inference falls back to CPU; swapping the dependency to {@code onnxruntime_gpu}
 * (with matching CUDA/cuDNN) then runs on the GPU.
 *
 * <ul>
 *   <li>{@link #gpuIfAvailable()}: one run at a time, using every CPU core.</li>
 *   <li>{@link #forParallelRuns(int)}: the cores split between that many
 *       simultaneous runs, for sessions that {@link ParallelTracks} runs on many
 *       trajectories at once.</li>
 * </ul>
 */
final class OnnxSessions {

    private OnnxSessions() {}

    static final int CORES = Math.max(1, Runtime.getRuntime().availableProcessors());

    /** Maximum number of runs {@link ParallelTracks} executes at once: one per core. */
    static final int PARALLEL_RUNS = CORES;

    static OrtSession.SessionOptions gpuIfAvailable() {
        return options(CORES);
    }

    /** Options for a session run by {@code runs} trajectories at once. */
    static OrtSession.SessionOptions forParallelRuns(int runs) {
        return options(Math.max(1, CORES / Math.max(1, runs)));
    }

    private static OrtSession.SessionOptions options(int intraOpThreads) {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        try {
            opts.setIntraOpNumThreads(intraOpThreads);
            opts.addCUDA(0);   // device 0; CPU remains the automatic fallback
        } catch (Throwable t) {
            // Expected on the released CPU-only build — a diagnostic, not an error.
            AndiLog.debug("GPU (CUDA) unavailable, using CPU: " + t.getMessage());
        }
        return opts;
    }
}
