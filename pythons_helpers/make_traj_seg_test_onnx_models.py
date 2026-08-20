#!/usr/bin/env python3
"""
Build small *test* ONNX models for AnDi-J Trajectory Segmentation → Load.

These are NOT trained networks — they are deterministic, data-dependent dummies
that follow the custom-segmentation contract, so you can check that both Load
modes work end-to-end before dropping in real models:

  Combined mode   → seg_combined.onnx   (outputs alpha, logD, cp, model_type)
  Per-output mode → seg_alpha.onnx, seg_logd.onnx, seg_cp.onnx, seg_modeltype.onnx

Contract (see the ML Models wiki page)
---------------------------------------
  Input  "input"      : (1, T, 2)   float32 — raw x,y positions, T dynamic.
  Output "alpha"      : (1, T)      per-frame α                     (verbatim)
  Output "logD"       : (1, T)      per-frame log10(D)              (verbatim)
  Output "cp"         : (1, T)      per-frame 0/1 change-point flag
  Output "model_type" : (1, T, 4)   per-frame diffusive-mode softmax (optional)
                         classes: 0=immobile, 1=confined, 2=free diffusion, 3=directed

What these dummies compute (purely illustrative, so you can *see* your model is
being used rather than the default):
  step        = |Δr[t]|  (displacement magnitude, last frame padded with 0)
  alpha       = clamp(step / mean(step), 0, 2)     ~1 on average, varies
  logD        = log10(step^2 + 1e-6)
  cp          = 1 where step > 2·mean(step)        (flags big jumps)
  model_type  = softmax over 4 step-magnitude prototypes (illustrative only)

Usage
-----
    pip install torch
    python make_traj_seg_test_onnx_models.py
"""

from pathlib import Path
import torch
import torch.nn as nn

SCRIPT_DIR = Path(__file__).parent
OPSET      = 13
DUMMY_T    = 200            # arbitrary; the length axis is exported dynamic


def _step(x):                                   # x: (1, T, 2) -> (1, T)
    d = x[:, 1:, :] - x[:, :-1, :]              # (1, T-1, 2)
    d = torch.cat([d, torch.zeros_like(d[:, :1, :])], dim=1)   # pad last -> (1, T, 2)
    return torch.sqrt((d * d).sum(-1) + 1e-12)  # (1, T)


def _alpha(step):
    mean = step.mean(dim=1, keepdim=True) + 1e-9
    return torch.clamp(step / mean, 0.0, 2.0)


def _logd(step):
    return torch.log10(step * step + 1e-6)


def _cp(step):
    mean = step.mean(dim=1, keepdim=True) + 1e-9
    return (step > 2.0 * mean).float()


def _model_type(step):
    # (1, T, 4) per-frame softmax over the 4 AnDi diffusive modes
    # (immobile, confined, free diffusion, directed), mapped from step
    # magnitude (purely illustrative).
    centers = torch.tensor([0.25, 1.0, 1.75, 2.5])               # (4,)
    logits  = -3.0 * (step.unsqueeze(-1) - centers) ** 2         # (1, T, 4)
    return torch.softmax(logits, dim=-1)


class AlphaModel(nn.Module):
    def forward(self, x): return _alpha(_step(x))


class LogDModel(nn.Module):
    def forward(self, x): return _logd(_step(x))


class CpModel(nn.Module):
    def forward(self, x): return _cp(_step(x))


class ModelTypeModel(nn.Module):
    def forward(self, x): return _model_type(_step(x))


class CombinedModel(nn.Module):
    def forward(self, x):
        s = _step(x)
        return _alpha(s), _logd(s), _cp(s), _model_type(s)


def export(model, name, out_names):
    out = SCRIPT_DIR / name
    dummy = torch.randn(1, DUMMY_T, 2)
    dyn = {"input": {1: "T"}}
    for o in out_names:
        dyn[o] = {1: "T"}
    with torch.no_grad():
        torch.onnx.export(
            model.eval(), dummy, str(out),
            input_names=["input"], output_names=out_names,
            dynamic_axes=dyn, opset_version=OPSET, do_constant_folding=True,
        )
    print(f"  -> {out.name}  ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    print("Exporting segmentation test models …")
    export(AlphaModel(),     "seg_alpha.onnx",     ["alpha"])
    export(LogDModel(),      "seg_logd.onnx",      ["logD"])
    export(CpModel(),        "seg_cp.onnx",        ["cp"])
    export(ModelTypeModel(), "seg_modeltype.onnx", ["model_type"])
    export(CombinedModel(),  "seg_combined.onnx",  ["alpha", "logD", "cp", "model_type"])

    # Optional round-trip check
    try:
        import numpy as np
        import onnxruntime as ort
        print("\nVerifying (each output should be length T):")
        for name in ("seg_alpha.onnx", "seg_logd.onnx", "seg_cp.onnx",
                     "seg_modeltype.onnx", "seg_combined.onnx"):
            s = ort.InferenceSession(str(SCRIPT_DIR / name), providers=["CPUExecutionProvider"])
            for T in (37, 128):
                outs = s.run(None, {"input": np.random.randn(1, T, 2).astype("float32")})
                shapes = ", ".join(f"{o.name}={a.shape}" for o, a in zip(s.get_outputs(), outs))
                print(f"   {name}  T={T}: {shapes}")
    except Exception as ex:
        print(f"\n(Skipped onnxruntime check: {ex})")

    print("\nLoad them via  Trajectory Segmentation → Data Manager → ML model options → Load.")
