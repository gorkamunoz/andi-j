# Analysis model log

## v0
- **Network:** frozen XResAttn convolutional/transformer backbone (4 encoder layers), layer-wise mean and dual-attention pooling, shared MLP, 5 model-class logits, and 5 alpha regressors. The softmax-weighted alpha is the final alpha prediction.
- **Preprocessing:** convert Cartesian positions to relative polar steps: displacement magnitude `dr` and relative turning angle `dtheta`; normalize `dr` by its trajectory maximum.
- **Postprocessing:** take output 10 as alpha; use `argmax(output[5:10])` for the diffusion-model class.

## v1
- **Network:** two FCI-type networks with the same architecture (Granik et al. 2019): five parallel blocks of dilated causal convolutions, concatenated, max-pooled over time, three dense layers. Each network reads the displacements twice, as (dx, dy) and (dy, dx), and merges the two by an elementwise max. One network outputs a distribution over the 40 exponents of the AnDi-1 grid (0.05 to 2.00), the other the 5 diffusion-model logits. Weights: `analysis_v1_alpha.pth`, `analysis_v1_cls.pth`.
- **Preprocessing (inside the ONNX):** keep the last 1000 positions, shift the trajectory to start at the origin, zero-pad at the front, take the displacements and divide them by the mean step size. Training padded to 1001 points; the graph pads with 287 zeros (one receptive field + 1, capped at 1001 points), which gives identical outputs because the activations over a longer run of zeros are constant and do not change the global max. Compute grows with the trajectory length.
- **Postprocessing (inside the ONNX):** both networks averaged over the 8 sign-flip / axis-swap views (softmax probabilities); alpha = median of the exponent distribution, interpolated within its bin; class scores multiplied by P(alpha <= 1) for ATTM and CTRW and by 1 - P(alpha <= 1) for LW.
- **I/O:** input `input` (B, T, 2) raw positions; outputs `alpha` (B, 1) and `model` (B, 5), class = argmax.
- **AnDi-1 2D benchmark:** MAE 0.1370 (alpha), micro-F1 0.8920 (model).
