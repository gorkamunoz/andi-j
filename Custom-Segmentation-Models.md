# Custom segmentation models (AnDi-J)

Trajectory Segmentation can run your own ONNX model(s) instead of the bundled
one. Pick **Load** in the *ML model options* box of the segmentation Data
Manager, then either:

- **Combined model** — one `.onnx` that outputs all three quantities, or
- **Per output** — one `.onnx` per output; any output you leave as *default* is
  produced by the bundled model.

Unlike the default model, **the plugin does no pre- or post-processing for your
model**: it feeds the raw trajectory in and reads your per-frame outputs out
verbatim. Everything else (differences, padding, test-time augmentation) is up
to you, inside your graph.

---

## What is ONNX?

ONNX is a portable format for a trained neural network: the graph of operations
plus the learned weights, in a single `.onnx` file. You train in PyTorch /
TensorFlow / scikit-learn and *export* to ONNX; the plugin runs it with ONNX
Runtime, no Python needed.

---

## I/O contract

### Input (all model types)

| name | shape | dtype | meaning |
|------|-------|-------|---------|
| `input` | `(1, T, 2)` | float32 | raw positions — column 0 = x, column 1 = y, one row per frame |

`T` (the number of frames) is **dynamic** — export the length axis as dynamic so
your model accepts any trajectory length. If your architecture needs
displacements, a fixed length, or padding, do it **inside** your model.

### Outputs

Every output is **per frame**, length `T`, in the same frame order as the input.
Shape `(1, T)` or `(1, T, 1)` are both accepted.

| output | shape | meaning | used as-is? |
|--------|-------|---------|-------------|
| `alpha` | `(1, T)` | anomalous exponent α per frame | yes, verbatim |
| `logD`  | `(1, T)` | **log₁₀(D)** per frame | yes, verbatim (use `NaN` for undefined/immobile) |
| `cp`    | `(1, T)` | change-point flag per frame, **0 or 1** | the plugin marks a change point at every interior frame where `cp ≥ 0.5` |

Notes:

- **D must be output as `log₁₀(D)`**, not linear D — that is exactly what the
  plugin stores and plots, so nothing is transformed. If a frame has no
  meaningful D (immobile / trapped), emit `NaN`; it will be shown as a gap and
  left out of segment means.
- **`cp` is a 0/1 flag**, one per frame. Frame 0 and the last frame are ignored
  (they are trajectory ends, not change points). Emit `1` at the first frame of
  each new segment.
- Segments (used by the *D vs α* and *Segment Stats* tabs) are assembled by the
  plugin from your `cp` output and the interval means of your `alpha` / `logD`.

### Output names

- **Combined model**: name the outputs `alpha`, `logD`, `cp`. If unnamed, the
  plugin falls back to output order 0 = alpha, 1 = logD, 2 = cp.
- **Per-output model**: a single output; its name does not matter.

---

## Minimal PyTorch export example

```python
import torch, torch.nn as nn

class CombinedSeg(nn.Module):
    def forward(self, x):                 # x: (1, T, 2) raw x,y
        # ... your architecture; must handle a dynamic T ...
        alpha = ...                       # (1, T)
        logD  = ...                       # (1, T)  = log10(D)
        cp    = ...                       # (1, T)  in {0,1}
        return alpha, logD, cp

m = CombinedSeg().eval()
dummy = torch.zeros(1, 200, 2)            # any length; the axis is dynamic
torch.onnx.export(
    m, dummy, "my_seg.onnx",
    input_names=["input"],
    output_names=["alpha", "logD", "cp"],
    dynamic_axes={"input": {1: "T"},
                  "alpha": {1: "T"}, "logD": {1: "T"}, "cp": {1: "T"}},
    opset_version=13, do_constant_folding=True,
)
```

For a **per-output** model, return a single `(1, T)` tensor and give it one
`output_names` / `dynamic_axes` entry.

> **Length-derived-constants pitfall.** Do not bake the dummy length (200 here)
> into any reshape/constant. Derive `T` from the input at runtime (`x.shape[1]`)
> so the exported graph is truly length-agnostic.

---

## Verify before loading

```python
import numpy as np, onnxruntime as ort
s = ort.InferenceSession("my_seg.onnx", providers=["CPUExecutionProvider"])
for T in (37, 128, 240):
    out = s.run(None, {"input": np.random.randn(1, T, 2).astype("float32")})
    for name, arr in zip([o.name for o in s.get_outputs()], out):
        print(T, name, arr.shape)     # each should be (1, T) or (1, T, 1)
```

If every output is length `T` for every `T`, it will load in the plugin.

---

## Test models

`pythons_helpers/make_seg_test_onnx_models.py` builds small **dummy** models
(`seg_alpha.onnx`, `seg_logd.onnx`, `seg_cp.onnx`, and `seg_combined.onnx`) that
follow this contract, so you can exercise both the *Combined* and *Per output*
Load modes before dropping in your real models.
