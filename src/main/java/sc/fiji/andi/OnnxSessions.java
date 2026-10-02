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
 *   <li>{@link #forParallelRuns()}: few cores per run, for sessions that
 *       {@link ParallelTracks} runs on many trajectories at once.</li>
 * </ul>
 */
final class OnnxSessions {

    private OnnxSessions() {}

    static final int CORES = Math.max(1, Runtime.getRuntime().availableProcessors());

    /** CPU threads each run uses when runs execute in parallel. */
    static final int THREADS_PER_PARALLEL_RUN = 1;

    /** Number of runs {@link ParallelTracks} executes at once. */
    static final int PARALLEL_RUNS = Math.max(1, CORES / THREADS_PER_PARALLEL_RUN);

    static OrtSession.SessionOptions gpuIfAvailable() {
        return options(CORES);
    }

    static OrtSession.SessionOptions forParallelRuns() {
        return options(Math.min(CORES, THREADS_PER_PARALLEL_RUN));
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
