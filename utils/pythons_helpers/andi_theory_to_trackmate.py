#!/usr/bin/env python3
"""
Generate TrackMate-format CSV files from andi_datasets simulated trajectories.

The output format matches what AnDi-J's TrajectoryIO expects:
  Row 1:   column headers
  Rows 2-4: empty filler rows (TrackMate metadata, skipped by the plugin)
  Row 5+:  data

Requirements:
    pip install andi-datasets numpy

Usage examples
--------------
  # 1-D (X only, Y=0): FBM, 100 trajs per exponent, T=30
  python andi_theory_to_trackmate.py

  # Proper 2-D trajectories (independent X and Y realisations):
  python andi_theory_to_trackmate.py --2d

  # CTRW model, 50 trajs, custom exponents, save to a specific path
  python andi_theory_to_trackmate.py --model 1 --N 50 --alpha 0.3 0.5 0.7 --output my_data.csv

  # All options
  python andi_theory_to_trackmate.py --help
"""

import argparse
import csv
import numpy as np
from pathlib import Path
from andi_datasets.datasets_theory import datasets_theory as DT

MODEL_NAMES = {
    0: "ATTM",
    1: "CTRW",
    2: "FBM",
    3: "LW",
    4: "SBM",
}

# ---------------------------------------------------------------------------
# Core generators
# ---------------------------------------------------------------------------

def generate_1d(model: int, N: int, T: int, exponents, interval: float) -> list:
    """
    Return a list of spot dicts from a 1-D simulation.
    Y is set to 0 for every spot (no Y information available for 1-D models).
    """
    dataset = DT().create_dataset(
        T=T, N_models=N, exponents=exponents, models=[model],
        load_trajectories=False,
    )
    np.random.shuffle(dataset)

    rows = []
    for traj_idx, row in enumerate(dataset):
        positions = row[2:]   # shape (T,)
        for frame_idx, pos in enumerate(positions):
            rows.append({
                "LABEL":      f"ID{traj_idx}",
                "TRACK_ID":   traj_idx,
                "POSITION_X": float(pos),
                "POSITION_Y": 0.0,
                "FRAME":      frame_idx,
                "POSITION_T": frame_idx * interval,
            })
    return rows, dataset.shape[0]


def generate_2d(model: int, N: int, T: int, exponents, interval: float) -> list:
    """
    Return a list of spot dicts from two independent 1-D simulations combined
    into a 2-D trajectory (isotropic diffusion: same model, same exponent for
    both X and Y components).
    """
    ds_x = DT().create_dataset(T=T, N_models=N, exponents=exponents, models=[model],
                                load_trajectories=False)
    ds_y = DT().create_dataset(T=T, N_models=N, exponents=exponents, models=[model],
                                load_trajectories=False)

    n_trajs = ds_x.shape[0]
    rows = []
    for traj_idx in range(n_trajs):
        x_pos = ds_x[traj_idx, 2:]   # shape (T,)
        y_pos = ds_y[traj_idx, 2:]
        for frame_idx in range(T):
            rows.append({
                "LABEL":      f"ID{traj_idx}",
                "TRACK_ID":   traj_idx,
                "POSITION_X": float(x_pos[frame_idx]),
                "POSITION_Y": float(y_pos[frame_idx]),
                "FRAME":      frame_idx,
                "POSITION_T": frame_idx * interval,
            })
    return rows, n_trajs


# ---------------------------------------------------------------------------
# Writer
# ---------------------------------------------------------------------------

FIELDNAMES = ["LABEL", "TRACK_ID", "POSITION_X", "POSITION_Y", "FRAME", "POSITION_T"]


def write_trackmate_csv(path: Path, spot_rows: list) -> None:
    """
    Write spot_rows to *path* in the TrackMate CSV convention that AnDi-J
    expects:
      Row 1:   column headers
      Rows 2-4: blank filler (TrackMate puts unit/type metadata here)
      Row 5+:  one data row per spot
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDNAMES)
        writer.writeheader()
        # Three blank filler rows (rows 2, 3, 4)
        for _ in range(3):
            writer.writerow({k: "" for k in FIELDNAMES})
        writer.writerows(spot_rows)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Generate a TrackMate-format CSV from andi_datasets simulations.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--model", type=int, default=2, choices=list(MODEL_NAMES.keys()),
        help="Diffusion model  0=ATTM 1=CTRW 2=FBM 3=LW 4=SBM  (default: 2=FBM)",
    )
    parser.add_argument(
        "--N", type=int, default=100,
        help="Number of trajectories per exponent value (default: 100)",
    )
    parser.add_argument(
        "--T", type=int, default=30,
        help="Trajectory length in frames (default: 30)",
    )
    parser.add_argument(
        "--alpha", type=float, nargs="+",
        default=list(np.round(np.arange(0.1, 1.0, 0.1), 2)),
        help="Anomalous exponent(s) to simulate (default: 0.1 0.2 … 0.9)",
    )
    parser.add_argument(
        "--interval", type=float, default=1.0,
        help="Frame interval in seconds used for POSITION_T (default: 1.0)",
    )
    parser.add_argument(
        "--2d", dest="two_d", action="store_true",
        help=(
            "Generate proper 2-D trajectories by running the model independently "
            "for X and Y (default: 1-D, Y=0)"
        ),
    )
    parser.add_argument(
        "--output", type=str, default=None,
        help=(
            "Output CSV file path.  "
            "Default: auto-named <script_dir>/andi_<MODEL>_N<N>_T<T>_<mode>.csv"
        ),
    )
    args = parser.parse_args()

    model_name = MODEL_NAMES.get(args.model, f"model{args.model}")
    mode_str   = "2d" if args.two_d else "1d"
    default_name = f"andi_{model_name}_N{args.N}_T{args.T}_a{args.alpha[0]}_{mode_str}.csv"
    output_path  = Path(args.output) if args.output else Path(__file__).parent / default_name

    print(
        f"Model : {model_name}\n"
        f"N/α   : {args.N} trajectories × {len(args.alpha)} exponent(s) = "
        f"{args.N * len(args.alpha)} trajectories total\n"
        f"T     : {args.T} frames\n"
        f"α     : {args.alpha}\n"
        f"Mode  : {'2-D (X and Y independent)' if args.two_d else '1-D (Y=0)'}\n"
        f"Output: {output_path}\n"
    )

    if args.two_d:
        spot_rows, n_trajs = generate_2d(
            args.model, args.N, args.T, args.alpha, args.interval)
    else:
        spot_rows, n_trajs = generate_1d(
            args.model, args.N, args.T, args.alpha, args.interval)

    write_trackmate_csv(output_path, spot_rows)
    print(f"✓  Written {n_trajs} trajectories ({len(spot_rows)} spots)  →  {output_path}")
    print("   Load this file from AnDi-J  →  Manage Data  →  Load CSV.")


if __name__ == "__main__":
    main()
