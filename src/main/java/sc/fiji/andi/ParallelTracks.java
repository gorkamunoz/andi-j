package sc.fiji.andi;

import ai.onnxruntime.OrtException;

import java.util.*;
import java.util.concurrent.*;

/**
 * Runs a per-trajectory model on many trajectories at once.
 *
 * <p>Each trajectory is one ONNX run on a worker thread
 * ({@link OnnxSessions#PARALLEL_RUNS} at a time); an ONNX Runtime session can be
 * run from several threads. Results are collected on the calling thread in track
 * order, and the {@link MlPredictor.ProgressListener} is called there too, once per
 * track: a listener that throws (e.g. to abort) cancels the remaining runs.</p>
 */
final class ParallelTracks {

    private ParallelTracks() {}

    /** Inference for one trajectory. */
    interface TrackFn<R> { R apply(List<double[]> spots) throws OrtException; }

    private static ExecutorService pool;

    private static synchronized ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(OnnxSessions.PARALLEL_RUNS, r -> {
                Thread t = new Thread(r, "AnDi-J inference");
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }

    /** {@code fn} applied to every track, keyed and ordered like {@code tracks}. */
    static <R> Map<Integer, R> map(Map<Integer, List<double[]>> tracks, TrackFn<R> fn,
                                   MlPredictor.ProgressListener listener) throws OrtException {
        Map<Integer, Future<R>> futures = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> e : tracks.entrySet()) {
            List<double[]> spots = e.getValue();
            futures.put(e.getKey(), pool().submit(() -> fn.apply(spots)));
        }

        Map<Integer, R> results = new LinkedHashMap<>();
        try {
            for (Map.Entry<Integer, Future<R>> e : futures.entrySet()) {
                results.put(e.getKey(), e.getValue().get());
                if (listener != null) listener.trackDone();
            }
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof OrtException) throw (OrtException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Inference interrupted");
        } finally {
            for (Future<R> f : futures.values()) f.cancel(false);
        }
        return results;
    }
}
