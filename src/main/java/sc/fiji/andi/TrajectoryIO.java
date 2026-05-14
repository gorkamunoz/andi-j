package sc.fiji.andi;

import java.io.*;
import java.util.*;

/**
 * Shared CSV parser for TrackMate spot exports.
 * TrackMate CSV format has 4 header rows before the data starts.
 */
public class TrajectoryIO {

    public static class ParseResult {
        /** trackId -> list of {frame, x, y} sorted by frame */
        public final Map<Integer, List<double[]>> tracks;
        /** Median time-per-frame estimated from POSITION_T / FRAME */
        public final double estimatedInterval;

        ParseResult(Map<Integer, List<double[]>> tracks, double interval) {
            this.tracks = tracks;
            this.estimatedInterval = interval;
        }
    }

    public static ParseResult parseCsv(File file) throws IOException {
        Map<Integer, List<double[]>> tracks = new LinkedHashMap<>();
        List<Double> frameIntervals = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {

            String headerLine = br.readLine();
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

            br.readLine(); br.readLine(); br.readLine(); // skip rows 2-4 (labels + units)

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",");
                try {
                    int    tid   = (int) Double.parseDouble(p[idxTrack].trim());
                    double x     = Double.parseDouble(p[idxX].trim());
                    double y     = Double.parseDouble(p[idxY].trim());
                    int    frame = (int) Double.parseDouble(p[idxFrame].trim());
                    tracks.computeIfAbsent(tid, k -> new ArrayList<>())
                          .add(new double[]{frame, x, y});
                    if (idxT >= 0 && idxT < p.length) {
                        double posT = safeDouble(p[idxT].trim());
                        if (frame > 0 && !Double.isNaN(posT) && posT > 0)
                            frameIntervals.add(posT / frame);
                    }
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {}
            }
        }

        for (List<double[]> spots : tracks.values())
            spots.sort(Comparator.comparingDouble(s -> s[0]));

        double interval = 1.0;
        if (!frameIntervals.isEmpty()) {
            Collections.sort(frameIntervals);
            interval = frameIntervals.get(frameIntervals.size() / 2);
        }
        return new ParseResult(tracks, interval);
    }

    private static int indexOf(String[] h, String name) {
        for (int i = 0; i < h.length; i++) if (h[i].trim().equals(name)) return i;
        return -1;
    }

    private static double safeDouble(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }
}
