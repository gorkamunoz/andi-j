# AnDi Fiji – Paper / Documentation Notes

Working notes for the prospective publication and user documentation.

---

## Overview

AnDi Fiji is a Fiji/ImageJ plugin for anomalous diffusion analysis of
single-particle tracking (SPT) data exported from TrackMate.  It provides:

- **tMSD visualization** – time-averaged MSD per trajectory and ensemble average,
  with eMSD overlay for ergodicity diagnostics
- **Diffusion coefficient estimation** – linear fit of tMSD vs lag
- **Anomalous exponent estimation** – log-log fit (tMSD) and ML-based prediction
  (SingleTrajModel, variable-length)
- **Trajectory segmentation** – per-frame α and log₁₀(D) via STEP models,
  visualized as 16-panel time series and a 2-D density histogram

---

## Validation checklist

### eMSD vs taMSD ergodicity test
- [ ] Verify that for **ergodic** processes (e.g. fBm, normal Brownian motion)
      eMSD ≈ avg taMSD across all lags in log-log space (same slope, same amplitude)
- [ ] Verify that for **non-ergodic** processes (e.g. CTRW, ATTM) the two curves
      diverge: eMSD ~ Δ^α (anomalous) while avg taMSD ~ Δ^1 (linear), and the
      ratio eMSD / taMSD → 0 as N or T increases
- [ ] Test with `andi_to_trackmate.py` synthetic data (models 0–4) at different α
- [ ] Check that enabling only eMSD with no Avg/All does not crash (bounds fix)
- [ ] Confirm dashed vs solid line style is visually clear in the plot

### ML alpha predictor (SingleTrajModel)
- [ ] Compare ML α vs tMSD log-log fit α on synthetic trajectories of different
      lengths (min_T = 10, 30, 100, 200) – check accuracy vs trajectory length
- [ ] Verify variable-length inference: run on a dataset with mixed track lengths
      and confirm no truncation or padding artefacts
- [ ] Check that log_t = log(T-1) is passed correctly (T-1 = number of polar
      feature vectors, not number of frames)

### STEP per-frame predictions
- [ ] Verify α and log₁₀(D) time series look sensible on synthetic multi-state
      trajectories (e.g. two-state model switching between α=0.5 and α=1.5)
- [ ] Check that the cartesian_to_relative_polar Java implementation matches
      the Python reference exactly (unit test with a known trajectory)
- [ ] Confirm the +3.5 log₁₀(D) correction is applied correctly
- [ ] Verify D vs α 2D histogram shows expected clustering for different models

### Data loading / TrajectoryIO
- [ ] Test with TrackMate CSVs that have gaps (missing frames within a track)
- [ ] Test with 1-D data (Y = 0 for all spots)
- [ ] Verify frame interval estimation from POSITION_T / FRAME

---

## Key design decisions to document

- **eMSD computation**: uses the first observed frame of each track as origin,
  gap-aware (frame map lookup).  Computed at the same lag points as taMSD
  (reuses `ExperimentData.lags`).  Always recomputed when taMSD is recomputed.

- **ONNX export of SingleTrajModel**: `math.log(T)` inside `forward()` would be
  baked as a constant by the ONNX tracer.  Solved by exposing `log_t` as a
  second explicit model input (`_OnnxWrapper`); Java passes `log(seqLen)` at
  runtime.

- **STEP log₁₀(D) preprocessing**: `log₁₀(max(x, 1e-10))` applied element-wise
  to polar features (both dr and dθ) before inference, clipping negative angles
  to −10 in log scale.  Matches Python reference exactly.

- **DataManagerWindow launcher injection**: refactored to accept an
  `AnalysisLauncher` callback so Trajectory Analysis and Trajectory Segmentation
  share the same data-loading UI without code duplication.

---

## Figures to include

1. Screenshot of tMSD tab: avg taMSD (solid) + eMSD (dashed) on the same axes,
   showing ergodicity breaking for CTRW
2. Screenshot of Anomalous Exponent tab: ML vs tMSD α histogram comparison
3. Screenshot of Segmentation → Visualization: 4×4 grid of per-frame α and D
4. Screenshot of Segmentation → D vs α: 2D density histogram for a multi-state dataset
5. Workflow diagram: TrackMate CSV → AnDi Fiji → results

---

## TODO / future work

- [ ] Export results (α, D, eMSD, taMSD) to CSV from the UI
- [ ] Add ergodicity breaking parameter EB = ⟨taMSD²⟩ / ⟨taMSD⟩² as a scalar
      summary statistic
- [ ] Support for 3-D trajectories (POSITION_Z column)
- [ ] Batch processing mode (no GUI, run from a macro/script)
- [ ] Publish ONNX models alongside the plugin for reproducibility
