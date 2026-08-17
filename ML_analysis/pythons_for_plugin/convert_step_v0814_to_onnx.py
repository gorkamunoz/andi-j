"""
Convert the UCL-Net (UNet3+) segmentation model to ONNX for AnDi-J.

Run from this folder (ML_analysis/pythons_for_plugin/):
    python convert_step_v0814_to_onnx.py

Output:
    ucl_step.onnx   – per-step α, D, change-point prob, and model-type

After conversion:
    1. Copy ucl_step.onnx to ../../src/main/resources/models/
    2. mvn clean package    (from the project root)
    3. Copy the JAR(s) to Fiji.app/jars/

Model I/O contract (as consumed by the plugin)
----------------------------------------------
  Input  "input"      : (1, 2, T) float32 — the *displacement* trajectory
                        (channel 0 = dx, channel 1 = dy). T MUST be a multiple
                        of 32 (the plugin pads each trajectory accordingly).
  Output "cp"         : (1, T, 1)  sigmoid change-point probability per step
  Output "kac"        : (1, T, 3)  per step [alpha, D, state]
  Output "model_type" : (1, T, 5)  softmax over 5 model classes

Test-time augmentation (8 symmetry views = 4×90° rotations × 2 flips) is baked
into the exported graph and averaged, so the plugin runs a single forward pass
per trajectory (matching predict_trajectory(..., use_tta=True)).
"""

import os
from pathlib import Path

import torch
import torch.nn as nn

# Reuse the reference architecture / loader
from step_prediction_v0814 import UNet3P, load_model

SCRIPT_DIR = Path(__file__).parent
MODEL_PATH = SCRIPT_DIR / '..' / '..' / '..' / 'ucl_step' / 'model' / 'model_best.pth'
OUT_PATH   = SCRIPT_DIR / 'ucl_step.onnx'
OPSET      = 13
DUMMY_T    = 224     # multiple of 32 for tracing; the T axis is exported dynamic


class TTAWrapper(nn.Module):
    """
    Wraps UNet3P and averages the outputs over the 8 symmetry views
    (4 rotations by 90° × 2 x-flips). α, D, state and model-type are
    rotation-invariant per step, so the views are averaged directly.
    """
    # (cos, sin) for k·90°, k = 0..3
    ANGLES = [(1.0, 0.0), (0.0, 1.0), (-1.0, 0.0), (0.0, -1.0)]

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):                       # x: (1, 2, T)
        views = []
        for c, s in self.ANGLES:
            for sx in (1.0, -1.0):
                xr0 = (x[:, 0] * c - x[:, 1] * s) * sx
                xr1 =  x[:, 0] * s + x[:, 1] * c
                views.append(torch.stack([xr0, xr1], dim=1))   # (1, 2, T)
        x8 = torch.cat(views, dim=0)            # (8, 2, T)
        cp, kac, mod = self.model(x8)           # (8, T, ·)
        cp  = cp.mean(dim=0, keepdim=True)       # (1, T, 1)
        kac = kac.mean(dim=0, keepdim=True)      # (1, T, 3)
        mod = mod.mean(dim=0, keepdim=True)      # (1, T, 5)
        return cp, kac, mod


if __name__ == '__main__':
    print(f'Loading {MODEL_PATH} ...')
    model   = load_model(str(MODEL_PATH), device='cpu')
    wrapper = TTAWrapper(model).eval()

    dummy = torch.zeros(1, 2, DUMMY_T)
    print(f'Exporting to {OUT_PATH.name} (opset {OPSET}) ...')
    with torch.no_grad():
        torch.onnx.export(
            wrapper, dummy, str(OUT_PATH),
            input_names=['input'],
            output_names=['cp', 'kac', 'model_type'],
            dynamic_axes={
                'input':      {2: 'T'},
                'cp':         {1: 'T'},
                'kac':        {1: 'T'},
                'model_type': {1: 'T'},
            },
            opset_version=OPSET,
            do_constant_folding=True,
        )
    kb = OUT_PATH.stat().st_size // 1024
    print(f'  -> {OUT_PATH.name}  ({kb} kB)')

    # ── Verify the ONNX matches PyTorch across several lengths ─────────────
    try:
        import numpy as np
        import onnxruntime as ort
        sess = ort.InferenceSession(str(OUT_PATH), providers=['CPUExecutionProvider'])
        print('\nVerifying ONNX vs PyTorch (max abs diff should be ~1e-6):')
        worst = 0.0
        for T in (32, 64, 224):
            x = torch.randn(1, 2, T)
            with torch.no_grad():
                cp_t, kac_t, mod_t = wrapper(x)
            cp_o, kac_o, mod_o = sess.run(None, {'input': x.numpy()})
            d = max(np.abs(cp_t.numpy() - cp_o).max(),
                    np.abs(kac_t.numpy() - kac_o).max(),
                    np.abs(mod_t.numpy() - mod_o).max())
            worst = max(worst, d)
            print(f'   T={T:3d}  max|Δ| = {d:.2e}')
        print('   OK' if worst < 1e-3 else f'   WARNING: large diff {worst:.2e} — export may be unfaithful!')
    except Exception as ex:  # onnxruntime not installed, etc.
        print(f'\n(Skipped ONNX verification: {ex})')

    print()
    print('Reminder: the plugin pads each trajectory length up to a multiple of 32')
    print('before inference (the model has 5 pooling levels).')
    print()
    print('Next: cp ucl_step.onnx ../../src/main/resources/models/ ; mvn clean package')
