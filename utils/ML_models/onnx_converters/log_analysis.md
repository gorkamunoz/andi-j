# Analysis model log

## v0
- **Network:** frozen XResAttn convolutional/transformer backbone (4 encoder layers), layer-wise mean and dual-attention pooling, shared MLP, 5 model-class logits, and 5 alpha regressors. The softmax-weighted alpha is the final alpha prediction.
- **Preprocessing:** convert Cartesian positions to relative polar steps: displacement magnitude `dr` and relative turning angle `dtheta`; normalize `dr` by its trajectory maximum.
- **Postprocessing:** take output 10 as alpha; use `argmax(output[5:10])` for the diffusion-model class.