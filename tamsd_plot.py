# -*- coding: utf-8 -*-
"""
tMSD Analysis Plugin for Fiji
Loads a TrackMate CSV export and plots the time-averaged MSD vs time lag
for every trajectory, on a log-log scale.

Usage: run from Fiji's Script Editor (Language: Python)
"""

import sys
import math
reload(sys)
sys.setdefaultencoding('utf-8')

from ij import IJ
from ij.gui import GenericDialog, Plot
from ij.io import OpenDialog
from java.awt import Color


# ---------------------------------------------------------------------------
# Colour palette (matplotlib tab10)
# ---------------------------------------------------------------------------
COLORS = [
    Color(0.122, 0.467, 0.706),  # blue
    Color(1.000, 0.498, 0.055),  # orange
    Color(0.173, 0.627, 0.173),  # green
    Color(0.839, 0.153, 0.157),  # red
    Color(0.580, 0.404, 0.741),  # purple
    Color(0.549, 0.337, 0.294),  # brown
    Color(0.890, 0.467, 0.761),  # pink
    Color(0.498, 0.498, 0.498),  # grey
    Color(0.737, 0.741, 0.133),  # olive
    Color(0.090, 0.745, 0.812),  # cyan
]


# ---------------------------------------------------------------------------
# Step 1 – file chooser
# ---------------------------------------------------------------------------
def choose_file():
    od = OpenDialog("Select TrackMate CSV export", None)
    if od.getPath() is None:
        return None
    return od.getPath()


# ---------------------------------------------------------------------------
# Step 2 – parameter dialog
# ---------------------------------------------------------------------------
def get_parameters(default_max_lag):
    gd = GenericDialog("tMSD Parameters")
    gd.addNumericField("Min time lag (frames)", 1, 0)
    gd.addNumericField("Max time lag (frames)", default_max_lag, 0)
    gd.addNumericField("Number of lags", 15, 0)
    gd.addCheckbox("Log-spaced lags", True)
    gd.addNumericField("Frame interval (time/frame, for x-axis label)", 1.0, 2)
    gd.addStringField("Time unit", "frames")
    gd.showDialog()
    if gd.wasCanceled():
        return None
    return {
        "min_lag":       int(gd.getNextNumber()),
        "max_lag":       int(gd.getNextNumber()),
        "n_lags":        int(gd.getNextNumber()),
        "log_spaced":    gd.getNextBoolean(),
        "frame_interval": gd.getNextNumber(),
        "time_unit":     gd.getNextString(),
    }


# ---------------------------------------------------------------------------
# Step 3 – CSV parsing
# ---------------------------------------------------------------------------
def parse_csv(filepath):
    """
    TrackMate CSV has 4 header rows; data starts at row 5.
    Returns: dict  track_id (int) -> list of (frame, x, y)  sorted by frame.
    Also returns the frame interval estimated from POSITION_T / FRAME (or 1.0).
    """
    with open(filepath, 'r') as fh:
        lines = fh.readlines()

    if len(lines) < 5:
        IJ.error("File too short – expected at least 4 header rows + data.")
        return None, None

    # Column indices come from row 1 (the raw-name header)
    header = [c.strip() for c in lines[0].split(',')]
    required = ['TRACK_ID', 'POSITION_X', 'POSITION_Y', 'FRAME', 'POSITION_T']
    indices = {}
    for col in required:
        try:
            indices[col] = header.index(col)
        except ValueError:
            IJ.error("Column '%s' not found in CSV." % col)
            return None, None

    tracks = {}
    frame_intervals = []

    for raw in lines[4:]:         # skip the 4 header rows
        raw = raw.strip()
        if not raw:
            continue
        parts = raw.split(',')
        try:
            track_id  = int(float(parts[indices['TRACK_ID']]))
            x         = float(parts[indices['POSITION_X']])
            y         = float(parts[indices['POSITION_Y']])
            frame     = int(float(parts[indices['FRAME']]))
            pos_t     = float(parts[indices['POSITION_T']])
        except (ValueError, IndexError):
            continue

        if track_id not in tracks:
            tracks[track_id] = []
        tracks[track_id].append((frame, x, y))

        # Estimate frame interval from the first non-zero frame we encounter
        if frame > 0 and pos_t > 0:
            frame_intervals.append(pos_t / frame)

    for tid in tracks:
        tracks[tid].sort(key=lambda s: s[0])

    # Median frame interval (robust to outliers)
    if frame_intervals:
        frame_intervals.sort()
        estimated_interval = frame_intervals[len(frame_intervals) // 2]
    else:
        estimated_interval = 1.0

    return tracks, estimated_interval


# ---------------------------------------------------------------------------
# Step 4 – build the list of time lags
# ---------------------------------------------------------------------------
def make_lags(min_lag, max_lag, n_lags, log_spaced):
    if min_lag < 1:
        min_lag = 1
    if max_lag < min_lag:
        max_lag = min_lag
    if n_lags < 1:
        n_lags = 1

    if log_spaced and min_lag < max_lag:
        log_min = math.log(float(min_lag))
        log_max = math.log(float(max_lag))
        step = (log_max - log_min) / max(n_lags - 1, 1)
        lags = sorted(set(
            int(round(math.exp(log_min + i * step))) for i in range(n_lags)
        ))
    else:
        step = max(1, int(round(float(max_lag - min_lag) / max(n_lags - 1, 1))))
        lags = list(range(min_lag, max_lag + 1, step))[:n_lags]

    return [l for l in lags if l >= 1]


# ---------------------------------------------------------------------------
# Step 5 – TAMSD for one trajectory (handles gaps)
# ---------------------------------------------------------------------------
def tamsd_single(spots, t_lags):
    """
    spots   : list of (frame, x, y) sorted by frame
    t_lags  : list of integer frame lags
    Returns : list of (lag, value_or_None)

    For each lag delta, we average  (dx^2 + dy^2)  over ALL pairs of
    observations whose frames differ by exactly delta.  This is the
    correct generalisation of the AnDi TA-MSD to gappy trajectories.
    """
    frame_to_pos = {f: (x, y) for f, x, y in spots}
    all_frames   = sorted(frame_to_pos)

    results = []
    for lag in t_lags:
        sq_disps = []
        for f in all_frames:
            f2 = f + lag
            if f2 in frame_to_pos:
                dx = frame_to_pos[f2][0] - frame_to_pos[f][0]
                dy = frame_to_pos[f2][1] - frame_to_pos[f][1]
                sq_disps.append(dx * dx + dy * dy)
        if sq_disps:
            results.append((lag, sum(sq_disps) / float(len(sq_disps))))
        else:
            results.append((lag, None))
    return results


# ---------------------------------------------------------------------------
# Step 6 – plot
# ---------------------------------------------------------------------------
def build_plot(all_tamsds, t_lags, frame_interval, time_unit):
    x_label = "Time lag (%s)" % time_unit
    y_label  = "tMSD (position units\xb2)"
    plot = Plot("tMSD vs time lag", x_label, y_label)
    plot.setLogScaleX()
    plot.setLogScaleY()

    # Convert frame lags to real time for the x-axis
    t_axis = [lag * frame_interval for lag in t_lags]

    all_vals = [v for vals in all_tamsds.values()
                for _, v in vals if v is not None and v > 0]
    if not all_vals:
        IJ.error("No valid tMSD values to plot.")
        return

    y_min = min(all_vals) * 0.5
    y_max = max(all_vals) * 2.0
    x_min = t_axis[0]  * 0.8
    x_max = t_axis[-1] * 1.2
    plot.setLimits(x_min, x_max, y_min, y_max)

    legend_lines = []
    for i, tid in enumerate(sorted(all_tamsds)):
        vals   = all_tamsds[tid]
        color  = COLORS[i % len(COLORS)]

        xs = []
        ys = []
        for (lag, val), t in zip(vals, t_axis):
            if val is not None and val > 0:
                xs.append(t)
                ys.append(val)
        if not xs:
            continue

        plot.setColor(color)
        plot.setLineWidth(1)
        plot.addPoints(xs, ys, Plot.LINE)
        plot.setLineWidth(0)
        plot.addPoints(xs, ys, Plot.CIRCLE)
        legend_lines.append("Track %d" % tid)

    plot.setColor(Color.BLACK)
    plot.addLegend("\n".join(legend_lines))
    plot.show()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def run():
    filepath = choose_file()
    if filepath is None:
        IJ.log("tMSD: cancelled – no file selected.")
        return

    IJ.log("tMSD: loading  " + filepath)
    tracks, estimated_interval = parse_csv(filepath)
    if tracks is None:
        return

    n_tracks = len(tracks)
    IJ.log("tMSD: found %d track(s)." % n_tracks)

    # Use the longest track length to suggest a sensible default max lag
    max_track_len = max(len(v) for v in tracks.values())
    default_max_lag = max(1, max_track_len // 4)   # quarter of longest track

    params = get_parameters(default_max_lag)
    if params is None:
        IJ.log("tMSD: cancelled by user.")
        return

    # Override estimated interval with user's value if they changed it
    frame_interval = params["frame_interval"]
    time_unit      = params["time_unit"]

    t_lags = make_lags(
        params["min_lag"], params["max_lag"],
        params["n_lags"],  params["log_spaced"]
    )
    IJ.log("tMSD: computing for lags (frames): " + str(t_lags))

    all_tamsds = {}
    for tid, spots in sorted(tracks.items()):
        all_tamsds[tid] = tamsd_single(spots, t_lags)

    build_plot(all_tamsds, t_lags, frame_interval, time_unit)
    IJ.log("tMSD: done.")


run()
