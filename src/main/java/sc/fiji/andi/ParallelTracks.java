package sc.fiji.andi;

import ai.onnxruntime.OrtException;

import java.util.*;
import java.util.concurrent.*;

/**
 * Runs a per-trajectory model on many trajectories at once.
 *
 * <p>Each trajectory is one ONNX run on a worker thread (at most
 * {@link OnnxSessions#PARALLEL_RUNS}, or a smaller cap, at a time); an ONNX Runtime
 * session can be run from several threads. Results are collected on the calling
 * thread in track order, and the {@link MlPredictor.ProgressListener} is called there
 * too, once per track: a listener that throws (e.g. to abort) cancels the remaining
 * runs.</p>
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
        return map(tracks, fn, listener, OnnxSessions.PARALLEL_RUNS);
    }

    /**
     * As {@link #map(Map, TrackFn, MlPredictor.ProgressListener)}, with at most
     * {@code maxRuns} trajectories in flight (bounds memory for large models).
     */
    static <R> Map<Integer, R> map(Map<Integer, List<double[]>> tracks, TrackFn<R> fn,
                                   MlPredictor.ProgressListener listener, int maxRuns)
            throws OrtException {
        List<Integer> ids = new ArrayList<>(tracks.keySet());
        List<Future<R>> futures = new ArrayList<>();
        int window = Math.max(1, maxRuns);

        Map<Integer, R> results = new LinkedHashMap<>();
        try {
            for (int i = 0; i < ids.size(); i++) {
                while (futures.size() < ids.size() && futures.size() < i + window) {
                    List<double[]> spots = tracks.get(ids.get(futures.size()));
                    futures.add(pool().submit(() -> fn.apply(spots)));
                }
                results.put(ids.get(i), futures.get(i).get());
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
            for (Future<R> f : futures) f.cancel(false);
        }
        return results;
    }
}
