package sc.fiji.andi;

import ij.IJ;

/**
 * Diagnostic output for AnDi-J.
 *
 * <p>Routine diagnostics (model I/O shapes, GPU availability, …) are written
 * only when ImageJ's <b>debug mode</b> is on — {@code Edit ▸ Options ▸ Misc… ▸
 * Debug mode} — and go to {@code System.out}, so they never raise Fiji's
 * console on their own. A developer who wants them ticks debug mode and reads
 * them in {@code Window ▸ Console}.</p>
 *
 * <p>Genuine anomalies (something the plugin did not expect and cannot handle
 * silently) go to {@code System.err} unconditionally via {@link #warn}, so real
 * problems still surface.</p>
 */
final class AndiLog {

    private AndiLog() {}

    /** True when ImageJ debug mode is enabled. */
    static boolean isDebug() {
        try {
            return IJ.debugMode;
        } catch (Throwable t) {          // ImageJ not initialised (e.g. unit tests)
            return false;
        }
    }

    /** Developer diagnostic: printed to stdout only in ImageJ debug mode. */
    static void debug(String msg) {
        if (isDebug()) System.out.println("[AnDi-J] " + msg);
    }

    /** Unexpected condition worth surfacing: always printed to stderr. */
    static void warn(String msg) {
        System.err.println("[AnDi-J] " + msg);
    }
}
