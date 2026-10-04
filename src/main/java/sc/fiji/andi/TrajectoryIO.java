package sc.fiji.andi;

import java.io.*;
import java.util.*;

/**
 * Shared CSV parser for TrackMate spot exports.
 * TrackMate CSV format has 4 header rows before the data starts: column names,
 * labels, short labels and units, e.g. {@code (micron)} for the positions and
 * {@code (sec)} for {@code POSITION_T}; {@code (pixel)} / {@code (frame)} when the
 * image was not calibrated.
 */
public class TrajectoryIO {

    public static class ParseResult {
        /** trackId -> list of {frame, x, y} sorted by frame */
        public final Map<Integer, List<double[]>> tracks;
        /** Size of one position unit in μm, from the units row; null if unknown (e.g. pixels). */
        public final Double spaceUm;
        /** Frame interval in μs: median POSITION_T / FRAME in the units-row time unit; null if unknown. */
        public final Double frameUs;

        ParseResult(Map<Integer, List<double[]>> tracks, Double spaceUm, Double frameUs) {
            this.tracks  = tracks;
            this.spaceUm = spaceUm;
            this.frameUs = frameUs;
        }
    }

    public static ParseResult parseCsv(File file) throws IOException {
        Map<Integer, List<double[]>> tracks = new LinkedHashMap<>();
        List<Double> frameIntervals = new ArrayList<>();

        Double spaceUm, timeUs;
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

            br.readLine(); br.readLine();                // rows 2-3: labels
            String unitsLine = br.readLine();            // row 4: units
            String[] units = unitsLine == null ? new String[0] : unitsLine.split(",", -1);
            spaceUm = spaceUnitUm(unitAt(units, idxX));
            timeUs  = timeUnitUs(unitAt(units, idxT));

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

        Double frameUs = null;
        if (timeUs != null && !frameIntervals.isEmpty()) {
            Collections.sort(frameIntervals);
            frameUs = frameIntervals.get(frameIntervals.size() / 2) * timeUs;
        }
        return new ParseResult(tracks, spaceUm, frameUs);
    }

    /** The unit text of column {@code idx} without brackets, lower case; "" if absent. */
    private static String unitAt(String[] units, int idx) {
        if (idx < 0 || idx >= units.length) return "";
        return units[idx].trim().replace("(", "").replace(")", "").trim().toLowerCase(Locale.ROOT);
    }

    /** Size in μm of a TrackMate space unit; null for pixels or an unknown unit. */
    static Double spaceUnitUm(String u) {
        switch (u) {
            case "nm": case "nanometer": case "nanometers": case "nanometre": case "nanometres":
                return 1e-3;
            case "micron": case "microns": case "µm": case "μm": case "um":
            case "micrometer": case "micrometers": case "micrometre": case "micrometres":
                return 1.0;
            case "mm": case "millimeter": case "millimeters": case "millimetre": case "millimetres":
                return 1e3;
            case "cm": case "centimeter": case "centimeters": case "centimetre": case "centimetres":
                return 1e4;
            case "m": case "meter": case "meters": case "metre": case "metres":
                return 1e6;
            default:
                return null;   // pixel, pixels, empty, …
        }
    }

    /** Size in μs of a TrackMate time unit; null for frames or an unknown unit. */
    static Double timeUnitUs(String u) {
        switch (u) {
            case "ns": case "nanosecond": case "nanoseconds": return 1e-3;
            case "µs": case "μs": case "us": case "microsecond": case "microseconds": return 1.0;
            case "ms": case "millisecond": case "milliseconds": case "msec": return 1e3;
            case "s": case "sec": case "secs": case "second": case "seconds": return 1e6;
            case "min": case "mins": case "minute": case "minutes": return 6e7;
            case "h": case "hr": case "hour": case "hours": return 3.6e9;
            default: return null;  // frame, frames, empty, …
        }
    }

    private static int indexOf(String[] h, String name) {
        for (int i = 0; i < h.length; i++) if (h[i].trim().equals(name)) return i;
        return -1;
    }

    private static double safeDouble(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }
}
