# Custom ML Models

AnDi Fiji ships with a default deep‑learning model, but you can plug in **your
own** model for either prediction task:

- **Anomalous exponent** (`Anomalous exponent` tab → **Type: Load**) — a model
  that predicts the exponent *α* for a trajectory.
- **Diffusion model classification** (`Diffusion Model` tab → **Type: Load**) —
  a model that classifies a trajectory into one of the five diffusion models.

You develop and train your model in Python (PyTorch, TensorFlow, scikit‑learn…),
export it to **ONNX**, and load the `.onnx` file in the plugin. This page
describes the exact input/output your model must expose and how to export it.

> The bundled **Default** model predicts α *and* the diffusion model in one
> network. A model you **Load** is single‑purpose: an α model updates only α; a
> classifier model updates only the diffusion‑model tab.

<!-- IMAGE: ml_load_type.png -->
![The Type: Default / Load selector](images/ml_load_type.png)

---

## What is ONNX (and why)

**ONNX** (Open Neural Network Exchange) is a portable, framework‑independent file
format for machine‑learning models. You train in whatever Python framework you
like, then export the trained model to a single `.onnx` file that describes its
computation graph and weights.

AnDi Fiji runs `.onnx` files with **ONNX Runtime** (the Java build), so at
inference time **no Python is needed** — the model runs inside Fiji. This is why
the plugin asks for an `.onnx` file rather than a PyTorch/TF checkpoint.

---

## Input: what the plugin feeds your model

For every trajectory the plugin builds one input tensor and calls your model:

| | |
|---|---|
| **Name** | `input` (the first/only model input) |
| **Shape** | `(1, T, 2)` — batch 1, one row per **frame**, two features |
| **Type** | `float32` |
| **Dynamic axis** | the `T` (sequence‑length) axis **must be dynamic** |

The two features are the **raw trajectory coordinates**:

- column `0` = `x`
- column `1` = `y`

i.e. exactly the (x, y) positions from your loaded data (in µm), one row per
frame. The plugin feeds these directly — it does **no** preprocessing.

> **Choose your own representation inside the model.** If your method works on
> displacements, relative‑polar features `(dr, dθ)`, images, etc., compute that
> transform as the first layers of your model (or in the exported graph). That
> way training and inference stay identical and the plugin's contract stays
> simple. The bundled *Default* model, for example, converts (x, y) to
> relative‑polar internally — that choice lives inside the model, not the
> plugin.

---

## Output: what the plugin reads back

### Anomalous‑exponent model

| | |
|---|---|
| **Name** | `output` |
| **Shape** | `(1, 1)` (or `(1,)`) |
| **Meaning** | the predicted exponent α; the plugin reads element `[0]` |

α should lie in `[0, 2]` (values outside are dropped from the histogram).
The α prediction is a single 2‑D value, so the X / Y component buttons are
disabled while ML α results are shown.

### Diffusion‑model classifier

| | |
|---|---|
| **Name** | `output` |
| **Shape** | `(1, K)` — one score/logit per class |
| **Meaning** | predicted class = `argmax(output)` |

`K = 5` and the class order **must** be:

```
0 = ATTM   1 = CTRW   2 = FBM   3 = LW   4 = SBM
```

(A scalar output `(1, 1)` is accepted too and taken as a rounded class index.)

---

## Exporting to ONNX (PyTorch example)

```python
import torch

model = MyModel()                     # your trained nn.Module
model.load_state_dict(torch.load("my_weights.pth", map_location="cpu"))
model.eval()

dummy = torch.zeros(1, 50, 2)         # (1, T, 2) raw (x, y); 50 is arbitrary here

torch.onnx.export(
    model, dummy, "my_alpha.onnx",
    input_names=["input"],
    output_names=["output"],
    dynamic_axes={"input":  {0: "batch", 1: "seq_len"},   # length must be dynamic!
                  "output": {0: "batch"}},
    opset_version=13,
    do_constant_folding=True,
)
```

The **`dynamic_axes`** entry for the sequence‑length axis is essential —
trajectories have different lengths, and without it the exported graph is locked
to `50`.

### Watch out: values derived from the sequence length

If your `forward` uses the length as a Python integer — e.g. `T = x.shape[1]`
then `math.log(T)`, `1/T`, or slicing like `x[:, T//2:]` — the ONNX tracer
**bakes those in as constants** for the dummy length, and the model is then
wrong for other lengths. Fixes:

- derive such quantities from the tensor shape at runtime (e.g. build a length
  tensor with `torch.ones_like(...).sum(dim=1)` and a position index with
  `torch.cumsum(torch.ones_like(...), dim=1) - 1`, then use masks instead of
  fixed slices), or
- expose the extra scalar as an additional model input.

The bundled model's converter (`convert_single_traj_v2_to_onnx.py`) shows a
worked example of both techniques.

---

## Verify before loading

Check the exported graph in Python with ONNX Runtime, at **two different
lengths**, before loading it in Fiji:

```python
import numpy as np, onnxruntime as ort

sess = ort.InferenceSession("my_alpha.onnx")
for T in (12, 137):                              # different trajectory lengths
    x = np.random.randn(1, T, 2).astype(np.float32)   # (1, T, 2) raw (x, y)
    out = sess.run(["output"], {"input": x})[0]
    print(T, out.shape, out.ravel()[:5])
```

You want the output shape to be `(1, 1)` for an α model or `(1, 5)` for a
classifier, and no error at either length.

A ready‑made pair of **test models** (an α estimator and a classifier) is
provided in `pythons_helpers/make_test_onnx_models.py`:

```bash
python make_test_onnx_models.py     # -> test_alpha.onnx, test_model.onnx
```

Use them to confirm the Load path works end‑to‑end before wiring your own model.

---

## Loading in the plugin

1. Run **Perform Analysis** so the trajectories are loaded.
2. Open the relevant tab:
   - **Anomalous exponent** → set **Type: Load** → **Choose model…** → pick your
     α `.onnx` → **Compute α**.
   - **Diffusion Model** → set **Type: Load** → **Choose model…** → pick your
     classifier `.onnx` → **Compute models (ML)**.
3. Each runs with a progress bar over the number of trajectories.

Switching **Type** back to **Default** restores the bundled model (which fills
both α and the diffusion model, and keeps the two tabs in sync).

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Output '…' not found" / wrong values | output not named `output`, or wrong shape |
| Works for one dataset, wrong for another | sequence‑length axis not dynamic, or length‑derived constants baked in |
| Classes look shuffled | classifier class order ≠ `ATTM, CTRW, FBM, LW, SBM` |
| α histogram empty | α outside `[0, 2]`, or NaNs — clamp/validate your output |
| Load fails immediately | not a valid `.onnx`, or an opset ONNX Runtime 1.17 doesn't support (use opset ≤ 17) |
