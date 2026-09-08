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
- **Postprocessing:** alpha is clipped to `[0, 2]`; log-D is already bounded and converted to base 10; change points are binary at probability `0.5`, and state is rounded and clipped to `[0, 3]`.