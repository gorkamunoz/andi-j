"""
analysis.py – AnDi Fiji analysis functions.

This is the developer-facing Python module.  Add functions here using
andi_datasets, numpy, scipy, etc.  Java calls this script by writing a
JSON request to stdin and reading a JSON response from stdout.

Request format:
    {"function": "<name>", "params": { ... }}

Response format (success):
    {"status": "ok", "result": { ... }}

Response format (error):
    {"status": "error", "message": "<description>"}

To add a new function:
    1. Write it below (use andi_datasets freely).
    2. Register it in the FUNCTIONS dict at the bottom.
    3. Call it from Java:  PythonBridge.get().call("analysis.py", json)
"""

import sys
import json
import traceback

import numpy as np


# ── helpers ────────────────────────────────────────────────────────────────

def ok(result):
    """Wrap a successful result."""
    return {"status": "ok", "result": result}

def err(message):
    """Wrap an error."""
    return {"status": "error", "message": message}


# ── analysis functions ──────────────────────────────────────────────────────

def tamsd(params):
    """
    Time-averaged MSD for multiple trajectories.

    params:
        tracks  : dict  trackId (str) -> {"frames": [...], "x": [...], "y": [...]}
        t_lags  : list of int  (frame lags)

    returns:
        dict  trackId (str) -> list of tMSD values (one per lag, null if not enough data)
    """
    tracks = params["tracks"]
    t_lags = [int(l) for l in params["t_lags"]]

    results = {}
    for tid, data in tracks.items():
        frames = np.array(data["frames"], dtype=int)
        x      = np.array(data["x"],      dtype=float)
        y      = np.array(data["y"],      dtype=float)

        # Build frame -> position lookup
        frame_to_pos = {f: (xi, yi) for f, xi, yi in zip(frames, x, y)}

        tmsds = []
        for lag in t_lags:
            sq_disps = []
            for f in frame_to_pos:
                if f + lag in frame_to_pos:
                    dx = frame_to_pos[f + lag][0] - frame_to_pos[f][0]
                    dy = frame_to_pos[f + lag][1] - frame_to_pos[f][1]
                    sq_disps.append(dx**2 + dy**2)
            tmsds.append(float(np.mean(sq_disps)) if sq_disps else None)

        results[str(tid)] = tmsds

    return ok({"tamsd": results})


def fit_alpha(params):
    """
    Fit the anomalous exponent α and diffusion coefficient D by log-linear
    regression of tMSD = 4D * t^α  (2D case).

    params:
        t_lags  : list of float  (real-time lag values, e.g. in seconds)
        tamsd   : list of float  (tMSD values; None entries are skipped)

    returns:
        {"alpha": float, "D": float, "r_squared": float}
    """
    t_lags = np.array(params["t_lags"], dtype=float)
    tamsd  = np.array([v if v is not None else np.nan
                       for v in params["tamsd"]], dtype=float)

    # Keep only valid (positive, finite) points
    mask = np.isfinite(tamsd) & (tamsd > 0) & (t_lags > 0)
    if mask.sum() < 2:
        return err("Not enough valid tMSD points to fit α (need at least 2).")

    log_t = np.log(t_lags[mask])
    log_m = np.log(tamsd[mask])

    # Weighted least-squares in log-log space
    coeffs = np.polyfit(log_t, log_m, 1)
    alpha  = float(coeffs[0])
    log_4D = float(coeffs[1])
    D      = float(np.exp(log_4D) / 4.0)

    # R²
    residuals  = log_m - np.polyval(coeffs, log_t)
    ss_res     = float(np.sum(residuals**2))
    ss_tot     = float(np.sum((log_m - log_m.mean())**2))
    r_squared  = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")

    return ok({"alpha": alpha, "D": D, "r_squared": r_squared})


def velocity_autocorrelation(params):
    """
    Velocity autocorrelation function (VACF) at lag 1.
    Useful for distinguishing FBM (negative VACF) from CTRW (near-zero).

    params:
        x : list of float  (x positions in chronological order)
        y : list of float  (y positions in chronological order)

    returns:
        {"vacf_lag1": float}
    """
    x = np.array(params["x"], dtype=float)
    y = np.array(params["y"], dtype=float)

    if len(x) < 3:
        return err("Trajectory too short for VACF (need at least 3 points).")

    vx = np.diff(x)
    vy = np.diff(y)

    # VACF at lag 1: <v(t)·v(t+1)> / <v(t)·v(t)>
    num   = float(np.mean(vx[:-1]*vx[1:] + vy[:-1]*vy[1:]))
    denom = float(np.mean(vx**2 + vy**2))
    vacf  = num / denom if denom > 0 else float("nan")

    return ok({"vacf_lag1": vacf})


# ── andi_datasets integration ───────────────────────────────────────────────
# Add functions here that use andi_datasets directly.

def generate_reference_trajectory(params):
    """
    Generate a synthetic trajectory from andi_datasets for testing.

    params:
        model  : str   one of "fbm", "ctrw", "lw", "attm", "sbm"
        alpha  : float anomalous exponent (0 < alpha <= 2)
        length : int   number of steps

    returns:
        {"x": [...], "y": [...]}
    """
    try:
        from andi_datasets.datasets_theory import datasets_theory
    except ImportError:
        return err("andi_datasets is not installed. Run: pip install andi-datasets")

    model_map = {"fbm": 1, "ctrw": 2, "lw": 3, "attm": 4, "sbm": 5}
    model_name = params.get("model", "fbm").lower()
    if model_name not in model_map:
        return err("Unknown model '{}'. Choose from: {}".format(
            model_name, list(model_map.keys())))

    alpha  = float(params.get("alpha",  1.0))
    length = int(params.get("length", 100))

    AD = datasets_theory()
    traj = AD.create_dataset(
        T      = length,
        N      = 1,
        exponents = [alpha],
        models    = [model_map[model_name]],
        dimension = 2,
    )
    # traj shape: (1, length, 2)
    xy = traj[0]
    return ok({"x": xy[:, 0].tolist(), "y": xy[:, 1].tolist()})


# ── dispatch table ──────────────────────────────────────────────────────────
# Register every callable function here.

FUNCTIONS = {
    "tamsd":                       tamsd,
    "fit_alpha":                   fit_alpha,
    "velocity_autocorrelation":    velocity_autocorrelation,
    "generate_reference_trajectory": generate_reference_trajectory,
}


# ── main entry point ────────────────────────────────────────────────────────

if __name__ == "__main__":
    try:
        raw   = sys.stdin.read()
        req   = json.loads(raw)
        fname = req.get("function", "")
        prms  = req.get("params", {})

        if fname not in FUNCTIONS:
            response = err("Unknown function '{}'. Available: {}".format(
                fname, list(FUNCTIONS.keys())))
        else:
            response = FUNCTIONS[fname](prms)

    except json.JSONDecodeError as e:
        response = err("Invalid JSON input: " + str(e))
    except Exception as e:
        response = err("Unhandled exception: " + traceback.format_exc())

    sys.stdout.write(json.dumps(response))
    sys.stdout.flush()
