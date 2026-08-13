#!/usr/bin/env python3
"""
Generate TrackMate-format CSV files from AnDi-2 *phenomenological* models
(`andi_datasets.datasets_phenom`).

Unlike `andi_to_trackmate.py` (theory models: ATTM/CTRW/FBM/LW/SBM, one α per
trajectory), the phenomenological models produce 2-D trajectories whose local
diffusion properties can **change over time** (multi-state, traps, dimerization,
confinement) — ideal for testing the segmentation tools.

This is a *simplified* front-end to `models_phenom.create_dataset`: it exposes
the parameters most people want from the terminal and lets everything else
(trap/compartment positions, per-state variances, …) use library defaults.

Output format matches AnDi Fiji's TrajectoryIO:
  Row 1:   column headers
  Rows 2-4: empty filler rows (TrackMate metadata, skipped by the plugin)
  Row 5+:  one data row per spot

Requirements
------------
    pip install andi-datasets numpy

Usage examples
--------------
  # Single-state 2-D FBM-like: α=0.7, D=1, 100 trajectories, T=200
  python andi2_to_trackmate.py --model single --alpha 0.7 --D 1 --N 100 --T 200

  # Two-state switching diffusion (α and D per state)
  python andi2_to_trackmate.py --model multi --alpha 1.2 0.5 --D 1.0 0.05 --pswitch 0.03

  # Immobile traps
  python andi2_to_trackmate.py --model traps --alpha 1 --D 1 --Nt 20 --Pb 0.02

  # Dimerization (free + bound state)
  python andi2_to_trackmate.py --model dimer --alpha 1 1 --D 1 0.05

  # Confinement in compartments
  python andi2_to_trackmate.py --model confinement --alpha 1 0.5 --D 1 0.1 --Nc 15

  # All options
  python andi2_to_trackmate.py --help
"""

import argparse
import csv
from pathlib import Path

import numpy as np
from andi_datasets.datasets_phenom import datasets_phenom


# Friendly CLI name  ->  models_phenom method name
MODEL_METHODS = {
    "single":      "single_state",
    "multi":       "multi_state",
    "traps":       "immobile_traps",
    "dimer":       "dimerization",
    "confinement": "confinement",
}

# How many diffusive states each model expects the user to specify
N_STATES = {
    "single": 1, "traps": 1,          # one moving state (traps add an immobile one)
    "multi": None,                    # any number >= 2
    "dimer": 2, "confinement": 2,
}

FIELDNAMES = ["LABEL", "TRACK_ID", "POSITION_X", "POSITION_Y", "FRAME", "POSITION_T"]


# ---------------------------------------------------------------------------
# Build the model dictionary for create_dataset
# ---------------------------------------------------------------------------

def build_dic(args) -> dict:
    """Assemble the `dic` passed to datasets_phenom().create_dataset."""
    method = MODEL_METHODS[args.model]
    var    = float(args.var)
    alphas = list(args.alpha)
    Ds     = list(args.D)

    # ---- validate state count ------------------------------------------------
    need = N_STATES[args.model]
    if need is not None and (len(alphas) != need or len(Ds) != need):
        raise SystemExit(
            f"Model '{args.model}' expects {need} value(s) for --alpha and --D "
            f"(got {len(alphas)} and {len(Ds)}).")
    if args.model == "multi" and (len(alphas) < 2 or len(alphas) != len(Ds)):
        raise SystemExit(
            "Model 'multi' expects >= 2 matching values for --alpha and --D.")

    def per_state(vals):        # -> [[mean, var], ...]
        return np.array([[float(v), var] for v in vals])

    def single(vals):           # -> [mean, var]
        return np.array([float(vals[0]), var])

    dic = {"model": method, "N": int(args.N), "T": int(args.T)}

    if args.model == "single":
        dic.update(Ds=single(Ds), alphas=single(alphas), dim=2)
        if args.L is not None:
            dic["L"] = float(args.L)

    elif args.model == "multi":
        n = len(alphas)
        # Transition matrix: stay with prob (1-pswitch), switch uniformly otherwise
        p = float(args.pswitch)
        M = np.full((n, n), p / max(n - 1, 1))
        np.fill_diagonal(M, 1.0 - p)
        dic.update(M=M, Ds=per_state(Ds), alphas=per_state(alphas))
        if args.L is not None:
            dic["L"] = float(args.L)

    elif args.model == "traps":
        dic.update(
            L=float(args.L if args.L is not None else 100),
            r=float(args.r if args.r is not None else 1),
            Pu=float(args.Pu), Pb=float(args.Pb), Nt=int(args.Nt),
            Ds=single(Ds), alphas=single(alphas))

    elif args.model == "dimer":
        dic.update(
            L=float(args.L if args.L is not None else 100),
            r=float(args.r if args.r is not None else 1),
            Pu=float(args.Pu), Pb=float(args.Pb),
            Ds=per_state(Ds), alphas=per_state(alphas))

    elif args.model == "confinement":
        dic.update(
            L=float(args.L if args.L is not None else 100),
            r=float(args.r if args.r is not None else 5),
            Nc=int(args.Nc), trans=float(args.trans),
            Ds=per_state(Ds), alphas=per_state(alphas))

    return dic


# ---------------------------------------------------------------------------
# Trajectories -> TrackMate spot rows
# ---------------------------------------------------------------------------

def trajs_to_rows(trajs, interval: float):
    """
    Convert an (T, N, 2) position array (as returned by create_dataset) into a
    list of TrackMate spot dicts. Returns (rows, n_trajs).
    """
    trajs = np.asarray(trajs)
    T, N = trajs.shape[0], trajs.shape[1]
    rows = []
    for n in range(N):
        for t in range(T):
            rows.append({
                "LABEL":      f"ID{n}",
                "TRACK_ID":   n,
                "POSITION_X": float(trajs[t, n, 0]),
                "POSITION_Y": float(trajs[t, n, 1]),
                "FRAME":      t,
                "POSITION_T": t * interval,
            })
    return rows, N


def write_trackmate_csv(path: Path, spot_rows: list) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDNAMES)
        writer.writeheader()
        for _ in range(3):                       # blank filler rows 2-4
            writer.writerow({k: "" for k in FIELDNAMES})
        writer.writerows(spot_rows)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Generate a TrackMate CSV from AnDi-2 phenomenological models.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--model", choices=list(MODEL_METHODS), default="single",
                        help="Phenomenological model (default: single)")
    parser.add_argument("--N", type=int, default=100,
                        help="Number of trajectories (default: 100)")
    parser.add_argument("--T", type=int, default=200,
                        help="Trajectory length in frames (default: 200)")
    parser.add_argument("--alpha", type=float, nargs="+", default=None,
                        help="Anomalous exponent(s), one per diffusive state")
    parser.add_argument("--D", type=float, nargs="+", default=None,
                        help="Diffusion coefficient(s), one per diffusive state")
    parser.add_argument("--var", type=float, default=0.0,
                        help="Variance for the α / D distributions (default: 0 = fixed)")
    parser.add_argument("--L", type=float, default=None,
                        help="Box size (periodic). Default: None (free) for single/multi, "
                             "100 for traps/dimer/confinement")
    parser.add_argument("--interval", type=float, default=1.0,
                        help="Frame interval for POSITION_T (default: 1.0)")
    # model-specific
    parser.add_argument("--pswitch", type=float, default=0.02,
                        help="[multi] per-frame probability of switching state (default: 0.02)")
    parser.add_argument("--Pu", type=float, default=0.1,
                        help="[traps/dimer] unbinding probability (default: 0.1)")
    parser.add_argument("--Pb", type=float, default=0.01,
                        help="[traps/dimer] binding probability (default: 0.01)")
    parser.add_argument("--Nt", type=int, default=10,
                        help="[traps] number of traps (default: 10)")
    parser.add_argument("--Nc", type=int, default=10,
                        help="[confinement] number of compartments (default: 10)")
    parser.add_argument("--trans", type=float, default=0.1,
                        help="[confinement] compartment transmittance (default: 0.1)")
    parser.add_argument("--r", type=float, default=None,
                        help="[traps/dimer/confinement] interaction radius "
                             "(default: 1 for traps/dimer, 5 for confinement)")
    parser.add_argument("--output", type=str, default=None,
                        help="Output CSV path (default: auto-named in the script folder)")
    args = parser.parse_args()

    # Per-model defaults for α / D when not given
    defaults = {
        "single":      ([1.0],       [1.0]),
        "multi":       ([1.2, 0.5],  [1.0, 0.1]),
        "traps":       ([1.0],       [1.0]),
        "dimer":       ([1.0, 1.0],  [1.0, 0.05]),
        "confinement": ([1.0, 0.5],  [1.0, 0.1]),
    }
    if args.alpha is None:
        args.alpha = defaults[args.model][0]
    if args.D is None:
        args.D = defaults[args.model][1]

    dic = build_dic(args)

    a_str = ",".join(f"{a:g}" for a in args.alpha)
    d_str = ",".join(f"{d:g}" for d in args.D)
    default_name = f"andi2_{args.model}_N{args.N}_T{args.T}_a{a_str}.csv"
    output_path  = Path(args.output) if args.output else Path(__file__).parent / default_name

    print(
        f"Model : {args.model}  ({dic['model']})\n"
        f"N     : {args.N} trajectories\n"
        f"T     : {args.T} frames\n"
        f"α     : {args.alpha}\n"
        f"D     : {args.D}\n"
        f"Output: {output_path}\n"
    )

    trajs, labels = datasets_phenom().create_dataset(dics=[dic])
    spot_rows, n_trajs = trajs_to_rows(trajs, args.interval)

    write_trackmate_csv(output_path, spot_rows)
    print(f"✓  Written {n_trajs} trajectories ({len(spot_rows)} spots)  →  {output_path}")
    print("   Load this file from AnDi Fiji  →  Manage Data  →  Choose Files / Load Data.")


if __name__ == "__main__":
    main()
