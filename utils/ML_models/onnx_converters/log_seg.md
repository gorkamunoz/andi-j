# Segmentation model log

## v0
- **Network:** 1-D U-Net 3+ over displacement channels, with change-point, per-step `[alpha, D, state]`, and 5-class model-type heads.
- **Preprocessing:** convert Cartesian positions to `(dx, dy)` steps, append a zero final step, and pad the sequence length to a multiple of 32. Optional 8-view rotation/flip test-time augmentation is applied outside the model.
- **Postprocessing:** sigmoid change-point probabilities are peak-detected; alpha is clipped to `[0, 1.999]` and D to `[1e-12, 1e6]`.

## v1
**Goal: get rid of nan prediction of D.**

- **Network:** same 1-D U-Net 3+ encoder/decoder; the direct D channel is replaced by a dedicated `K` head that predicts bounded natural `log(D)`.
- **Changes from v0:** predicts and clamps `log(D)` to `[-30, 11]`, rather than predicting raw D and clipping it only after inference. The ONNX wrapper converts natural log-D to `log10(D)`. Its interface now accepts raw Cartesian positions, creates/pads displacements internally, returns continuous alpha and log-D, and thresholds change points / rounds states.
- **Preprocessing:** provide Cartesian positions; the wrapper pads positions to a multiple of 32, then derives `(dx, dy)` with a zero final step.
- **Postprocessing:** alpha is clipped to `[0, 2]`; log-D is already bounded and converted to base 10; state is rounded and clipped to `[0, 3]`. Change points are thresholded at probability `0.5` and then reduced to the **rising edge**, so each boundary produces a single flag at the first frame of the new segment. Thresholding alone marks a *run* of consecutive frames around each boundary, and since the plugin treats every flagged interior frame as its own change point, that turned one boundary into several change points plus spurious one-frame segments.

## v2
- **Network:** the v1 U-Net 3+ trunk (weights v2.2: `seg_v2.pth`), retrained, with new heads on the top decoder level. log(D): an MLP. alpha: linear readout + residual MLP + log(D)-conditioned MLP, plus a temporal branch of three dilated convolutions. Diffusive state: a regression turned into 4 class logits, plus a learned correction. Architecture: `model_seg_v2.py`.
- **Preprocessing (inside the ONNX):** raw positions → displacements with a zero final step, zero-padded to a multiple of 32. The U-Net runs on the 8 D4 views (4 rotations × 2 reflections) in one batch, and the per-frame outputs are averaged over the views (the diffusive state as probabilities).
- **Postprocessing (inside the ONNX):** alpha = 0.85 × alpha without the temporal branch + 0.15 × alpha with it, clipped to [0, 1.999]; log(D) → log10(D); alpha > 1.9 → directed. Change points: strict local maxima of the probability ≥ 0.32, at least 3 frames from the start and 2 from the end, one flag per new segment. Segment outputs: per-segment medians, with the same alpha blend, log10(D) clipped to [-12, 6], and the state as the argmax of the median probabilities.
- **I/O:** input `input` (1, T, 2); outputs `alpha`, `logD`, `cp` (1, T), `diffusive_state` (1, T, 4), and `seg_start`, `seg_length`, `seg_alpha`, `seg_logD`, `seg_state` (1, S) with S = T (rows with `seg_length` 0 are padding). Batch fixed at 1. Converter: `convert_seg_v2.py` (TorchScript export, opset 13).
- **2nd AnDi Challenge benchmark (single-trajectory task, submission `model_v2.2`):** JI (CP) 0.684, RMSE (CP) 1.755, MAE (alpha) 0.193, MSLE (D) 0.0182, F1 (diffusive state) 0.918.
