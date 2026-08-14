package sc.fiji.andi;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.gui.Plot;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * ImageJ1 plugin that reads a TrackMate CSV spot export and shows a log-log
 * plot of the time-averaged MSD (tMSD) vs time lag for every trajectory.
 *
 * Registered in plugins.config as:  Plugins > AnDi-J > Plot tMSD
 */
public class TamsdPlotCommand implements PlugIn {

    // ------------------------------------------------------------------
    // Colour palette (matplotlib tab10)
    // ------------------------------------------------------------------
    private static final Color[] PALETTE = {
        new Color( 31, 119, 180),
        new Color(255, 127,  14),
        new Color( 44, 160,  44),
        new Color(214,  39,  40),
        new Color(148, 103, 189),
        new Color(140,  86,  75),
        new Color(227, 119, 194),
        new Color(127, 127, 127),
        new Color(188, 189,  34),
        new Color( 23, 190, 207),
    };

    // ------------------------------------------------------------------
    // Entry point – called by Fiji when the menu item is clicked
    // ------------------------------------------------------------------
    @Override
    public void run(String arg) {

        // 1. Ask the user to pick a CSV file
        OpenDialog od = new OpenDialog("Select TrackMate CSV export", null);
        if (od.getPath() == null) return;
        File csvFile = new File(od.getPath());

        // 2. Parse the file so we can suggest a sensible default max lag
        ParseResult parsed;
        try {
            parsed = parseCsv(csvFile);
        } catch (IOException e) {
            IJ.error("AnDi-J tMSD", "Could not read file:\n" + e.getMessage());
            return;
        }
        if (parsed.tracks.isEmpty()) {
            IJ.error("AnDi-J tMSD", "No tracks found in the file.");
            return;
        }
        IJ.log("AnDi-J tMSD: loaded " + parsed.tracks.size() + " track(s) from " + csvFile.getName());

        int maxTrackLen = parsed.tracks.values().stream().mapToInt(List::size).max().orElse(4);
        int defaultMaxLag = Math.max(1, maxTrackLen / 4);

        // 3. Show parameter dialog
        GenericDialog gd = new GenericDialog("tMSD Parameters");
        gd.addNumericField("Min time lag (frames)", 1, 0);
        gd.addNumericField("Max time lag (frames)", defaultMaxLag, 0);
        gd.addNumericField("Number of lags", 15, 0);
        gd.addCheckbox("Log-spaced lags", true);
        gd.addNumericField("Frame interval (real time per frame)", parsed.estimatedInterval, 3);
        gd.addStringField("Time unit (e.g. s, min, frames)", "frames");
        gd.showDialog();
        if (gd.wasCanceled()) return;

        int     minLag        = (int) gd.getNextNumber();
        int     maxLag        = (int) gd.getNextNumber();
        int     nLags         = (int) gd.getNextNumber();
        boolean logSpaced     = gd.getNextBoolean();
        double  frameInterval = gd.getNextNumber();
        String  timeUnit      = gd.getNextString();

        // 4. Build lag array
        int[] lags = makeLags(minLag, maxLag, nLags, logSpaced);
        IJ.log("AnDi-J tMSD: lags (frames) = " + Arrays.toString(lags));

        // 5. Compute tMSD for every track
        Map<Integer, double[]> tamsdByTrack = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<double[]>> entry : parsed.tracks.entrySet())
            tamsdByTrack.put(entry.getKey(), computeTamsd(entry.getValue(), lags));

        // 6. Plot
        buildPlot(tamsdByTrack, lags, frameInterval, timeUnit);
        IJ.log("AnDi-J tMSD: done.");
    }

    // ==================================================================
    // CSV parsing
    // ==================================================================

    private static class ParseResult {
        Map<Integer, List<double[]>> tracks;  // trackId -> [{frame, x, y}, ...]
        double estimatedInterval;             // median time per frame
    }

    private ParseResult parseCsv(File file) throws IOException {
        ParseResult out = new ParseResult();
        out.tracks = new LinkedHashMap<>();
        List<Double> frameIntervals = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {

            String headerLine = br.readLine();   // row 1: raw column names
            if (headerLine == null) throw new IOException("File is empty.");
            String[] headers = headerLine.split(",");

            int idxTrack = indexOf(headers, "TRACK_ID");
            int idxX     = indexOf(headers, "POSITION_X");
            int idxY     = indexOf(headers, "POSITION_Y");
            int idxFrame = indexOf(headers, "FRAME");
            int idxT     = indexOf(headers, "POSITION_T");

            if (idxTrack < 0 || idxX < 0 || idxY < 0 || idxFrame < 0)
                throw new IOException(
                    "Required columns not found (TRACK_ID, POSITION_X, POSITION_Y, FRAME).\n" +
                    "Please use a TrackMate spot CSV export.");

            br.readLine(); br.readLine(); br.readLine();  // skip rows 2-4 (labels + units)

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                try {
                    int    trackId = (int) Double.parseDouble(parts[idxTrack].trim());
                    double x       = Double.parseDouble(parts[idxX].trim());
                    double y       = Double.parseDouble(parts[idxY].trim());
                    int    frame   = (int) Double.parseDouble(parts[idxFrame].trim());
                    out.tracks.computeIfAbsent(trackId, k -> new ArrayList<>())
                              .add(new double[]{frame, x, y});
                    if (idxT >= 0 && idxT < parts.length) {
                        double posT = parseDoubleOrNaN(parts[idxT].trim());
                        if (frame > 0 && !Double.isNaN(posT) && posT > 0)
                            frameIntervals.add(posT / frame);
                    }
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {}
            }
        }

        for (List<double[]> spots : out.tracks.values())
            spots.sort(Comparator.comparingDouble(s -> s[0]));

        out.estimatedInterval = 1.0;
        if (!frameIntervals.isEmpty()) {
            Collections.sort(frameIntervals);
            out.estimatedInterval = frameIntervals.get(frameIntervals.size() / 2);
        }
        return out;
    }

    private static int indexOf(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++)
            if (headers[i].trim().equals(name)) return i;
        return -1;
    }

    private static double parseDoubleOrNaN(String s) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    // ==================================================================
    // Lag generation
    // ==================================================================

    private static int[] makeLags(int minL, int maxL, int n, boolean logSpaced) {
        minL = Math.max(1, minL);
        maxL = Math.max(minL, maxL);
        n    = Math.max(2, n);
        TreeSet<Integer> set = new TreeSet<>();
        if (logSpaced && maxL > minL) {
            double logMin = Math.log(minL), logMax = Math.log(maxL);
            double step = (logMax - logMin) / (n - 1);
            for (int i = 0; i < n; i++)
                set.add(Math.max(1, (int) Math.round(Math.exp(logMin + i * step))));
        } else {
            double step = (double)(maxL - minL) / (n - 1);
            for (int i = 0; i < n; i++)
                set.add(Math.max(1, (int) Math.round(minL + i * step)));
        }
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    // ==================================================================
    // Gap-aware tMSD for one trajectory
    // ==================================================================

    /**
     * Computes the time-averaged MSD for a single trajectory at each lag.
     * Handles gappy tracks: for lag Δ, averages over every pair of detections
     * whose frame numbers differ by exactly Δ.
     *
     * @param spots list of {frame, x, y} sorted by frame
     * @param lags  integer frame lags to evaluate
     * @return tMSD value per lag (NaN if no pairs exist for that lag)
     */
    private static double[] computeTamsd(List<double[]> spots, int[] lags) {
        Map<Integer, double[]> frameMap = new HashMap<>(spots.size() * 2);
        for (double[] s : spots)
            frameMap.put((int) s[0], new double[]{s[1], s[2]});

        double[] result = new double[lags.length];
        Arrays.fill(result, Double.NaN);
        for (int li = 0; li < lags.length; li++) {
            double sum = 0; int count = 0;
            for (Map.Entry<Integer, double[]> e : frameMap.entrySet()) {
                double[] p2 = frameMap.get(e.getKey() + lags[li]);
                if (p2 != null) {
                    double dx = p2[0] - e.getValue()[0];
                    double dy = p2[1] - e.getValue()[1];
                    sum += dx*dx + dy*dy;
                    count++;
                }
            }
            if (count > 0) result[li] = sum / count;
        }
        return result;
    }

    // ==================================================================
    // Plot
    // ==================================================================

    private void buildPlot(Map<Integer, double[]> tamsdByTrack,
                           int[] lags, double interval, String unit) {
        double[] tAxis = new double[lags.length];
        for (int i = 0; i < lags.length; i++) tAxis[i] = lags[i] * interval;

        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (double[] vals : tamsdByTrack.values())
            for (double v : vals)
                if (!Double.isNaN(v) && v > 0) { yMin = Math.min(yMin, v); yMax = Math.max(yMax, v); }

        if (yMin == Double.MAX_VALUE) { IJ.error("AnDi-J tMSD", "No valid tMSD values to plot."); return; }

        Plot plot = new Plot("tMSD vs time lag", "Time lag (" + unit + ")", "tMSD (μm²)");
        plot.setLogScaleX();
        plot.setLogScaleY();
        plot.setLimits(tAxis[0] * 0.8, tAxis[tAxis.length-1] * 1.2, yMin * 0.5, yMax * 2.0);

        StringBuilder legend = new StringBuilder();
        int ci = 0;
        for (Map.Entry<Integer, double[]> entry : tamsdByTrack.entrySet()) {
            double[] vals = entry.getValue();
            List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (int i = 0; i < lags.length; i++)
                if (!Double.isNaN(vals[i]) && vals[i] > 0) {
                    xs.add((float) tAxis[i]);
                    ys.add((float) vals[i]);
                }
            if (xs.isEmpty()) continue;
            Color c = PALETTE[ci % PALETTE.length];
            plot.setColor(c);
            plot.setLineWidth(1);
            plot.addPoints(toFloatArray(xs), toFloatArray(ys), Plot.LINE);
            plot.setLineWidth(0);
            plot.addPoints(toFloatArray(xs), toFloatArray(ys), Plot.CIRCLE);
            if (legend.length() > 0) legend.append("\n");
            legend.append("Track ").append(entry.getKey());
            ci++;
        }
        plot.setColor(Color.BLACK);
        plot.addLegend(legend.toString());
        plot.show();
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
        return a;
    }
}
