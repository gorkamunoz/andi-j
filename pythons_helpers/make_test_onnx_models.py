#!/usr/bin/env python3
"""
Build small *test* ONNX models to exercise AnDi Fiji's "Load" model type
(Anomalous exponent → Type: Load, and Diffusion Model → Type: Load).

These are NOT trained networks — they are deterministic estimators that show
how a third-party ONNX model must be shaped to plug into the plugin. Use them
to check that the Load path works end-to-end; then drop in your real models.

Plugin I/O contract (what the plugin feeds / reads)
---------------------------------------------------
  Input  : one tensor "input" of shape (1, T, 2), float32 — the raw trajectory
           coordinates (column 0 = x, column 1 = y), one row per frame.
           The T (sequence-length) axis is dynamic. The plugin does no
           preprocessing; any representation your model needs is up to it.

  Output (α model)     : "output" shape (1, 1) — the predicted anomalous exponent.
  Output (model model) : "output" shape (1, 5) — one logit per diffusion model,
                         in the order ATTM, CTRW, FBM, LW, SBM (argmax = class).

What these test models compute
------------------------------
  test_alpha.onnx : computes the time-averaged MSD of the (x, y) trajectory at
                    lags 1..4 and returns the log-log slope (clamped to [0, 2])
                    — the same idea as the tMSD α method.
  test_model.onnx : takes that same α estimate and returns logits = -|α - proto|
                    against per-model prototype exponents, so argmax picks the
                    nearest one. (Purely illustrative.)

Requirements
------------
    pip install torch

Usage
-----
    python make_test_onnx_models.py
    # -> test_alpha.onnx, test_model.onnx  (in this folder)

Then in the plugin: Anomalous exponent → Type: Load → Choose model → test_alpha.onnx
                    Diffusion Model    → Type: Load → Choose model → test_model.onnx
"""

import math
from pathlib import Path

import torch
import torch.nn as nn

SCRIPT_DIR = Path(__file__).parent
OPSET      = 13
DUMMY_LEN  = 50            # arbitrary; the seq-len axis is exported as dynamic
LAGS       = [1, 2, 3, 4]  # tMSD lags used by the α estimator

# Prototype exponents per diffusion model (ATTM, CTRW, FBM, LW, SBM) — dummy
MODEL_PROTOTYPES = [0.4, 0.7, 1.0, 1.7, 0.6]


def _alpha_from_xy(x: torch.Tensor) -> torch.Tensor:
    """
    x: (B, T, 2) raw positions [x, y]  ->  (B, 1) tMSD log-log slope in [0,2].
    Computes the time-averaged MSD at each lag in LAGS then fits log(MSD) vs
    log(lag) by least squares.
    """
    px = x[:, :, 0]
    py = x[:, :, 1]

    log_msd = []
    for L in LAGS:
        dx  = px[:, L:] - px[:, :-L]
        dy  = py[:, L:] - py[:, :-L]
        msd = (dx * dx + dy * dy).mean(dim=1, keepdim=True)   # (B, 1)
        log_msd.append(torch.log(msd.clamp(min=1e-12)))
    logM = torch.cat(log_msd, dim=1)                          # (B, len(LAGS))

    logL   = px.new_tensor([math.log(L) for L in LAGS]).reshape(1, -1)  # (1, len)
    lLmean = logL.mean()
    mMmean = logM.mean(dim=1, keepdim=True)
    num    = ((logL - lLmean) * (logM - mMmean)).sum(dim=1, keepdim=True)
    den    = ((logL - lLmean) ** 2).sum().clamp(min=1e-12)
    alpha  = (num / den).clamp(0.0, 2.0)                       # (B, 1)
    return alpha


class TestAlpha(nn.Module):
    def forward(self, x):
        return _alpha_from_xy(x)             # (B, 1)


class TestModel(nn.Module):
    def forward(self, x):
        alpha  = _alpha_from_xy(x)                                 # (B, 1)
        protos = alpha.new_tensor(MODEL_PROTOTYPES).reshape(1, -1) # (1, 5)
        logits = -torch.abs(alpha - protos)                        # (B, 5)
        return logits


def export(model, name):
    out = SCRIPT_DIR / name
    dummy = torch.randn(1, DUMMY_LEN, 2)
    with torch.no_grad():
        torch.onnx.export(
            model.eval(), dummy, str(out),
            input_names=["input"], output_names=["output"],
            dynamic_axes={"input": {0: "batch", 1: "seq_len"},
                          "output": {0: "batch"}},
            opset_version=OPSET, do_constant_folding=True,
        )
    print(f"  -> {out.name}  ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    print("Exporting test ONNX models ...")
    export(TestAlpha(), "test_alpha.onnx")
    export(TestModel(), "test_model.onnx")
    print("\nLoad them via  Type: Load → Choose model  in the α and Diffusion-Model tabs.")
