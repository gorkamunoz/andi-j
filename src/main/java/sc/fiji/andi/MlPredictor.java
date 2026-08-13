package sc.fiji.andi;

import ai.onnxruntime.OrtException;

import java.io.Closeable;

/**
 * Common interface for the trajectory ML predictors so the UI can drive either
 * the bundled combined model or a user-loaded single-purpose model uniformly.
 *
 * <p>Implementations read a trajectory, run inference, and write their result
 * into the {@link ExperimentData} maps ({@code alphaCoeffs} and/or
 * {@code diffModel}).</p>
 */
public interface MlPredictor extends Closeable {

    /** Notified once after each track is processed (for progress reporting). */
    interface ProgressListener { void trackDone(); }

    /** Runs inference for every track in {@code exp}, storing results in it. */
    void computeForExperiment(ExperimentData exp, ProgressListener listener) throws OrtException;

    @Override
    void close();
}
