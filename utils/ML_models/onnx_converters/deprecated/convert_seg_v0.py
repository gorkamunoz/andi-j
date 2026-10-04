"""
Convert the UCL-Net (UNet3+) segmentation model to ONNX for AnDi-J.

Run from this folder (ML_analysis/pythons_for_plugin/):
    python convert_model_segmentation_prerelease_to_onnx.py

Output:
    seg_v0.onnx   – per-step α, D, change-point prob, and model-type

After conversion:
    1. Copy seg_v0.onnx to ../../src/main/resources/models/
    2. mvn clean package    (from the project root)
    3. Copy the JAR(s) to Fiji.app/jars/

Model I/O contract (as consumed by the plugin)
----------------------------------------------
  Input  "input"      : (B, 2, T) float32 — the *displacement* trajectory
                        (channel 0 = dx, channel 1 = dy). T MUST be a multiple
                        of 32 (the plugin pads each trajectory accordingly).
                        Both the batch axis B and the length axis T are dynamic,
                        so the plugin can run many trajectories in one call.
  Output "cp"         : (B, T, 1)  sigmoid change-point probability per step
  Output "kac"        : (B, T, 3)  per step [alpha, D, state]
  Output "model_type" : (B, T, 5)  softmax over 5 model classes

Test-time augmentation is NOT baked in: this is the plain backbone. The plugin
applies the 8-view TTA (4×90° rotations × 2 flips) itself when the "TTA" option
is enabled in the Data Manager, by feeding the rotated views as extra batch rows
and averaging — reproducing predict_trajectory(use_tta=True). With TTA off it
runs a single view (≈8× faster).
"""

from pathlib import Path

import torch

# Reuse the reference architecture / loader
from utils.ML_models.onnx_converters.deprecated.model_seg_v0 import UNet3P, load_model

SCRIPT_DIR = Path(__file__).parent
MODEL_PATH = SCRIPT_DIR / '../../model_weights/seg_v0.pth'
OUT_PATH   = SCRIPT_DIR / '../../model_weights/seg_v0.onnx'
OPSET      = 13
DUMMY_T    = 224     # multiple of 32 for tracing; the T axis is exported dynamic


if __name__ == '__main__':
    print(f'Loading {MODEL_PATH} ...')
    model = load_model(str(MODEL_PATH), device='cpu').eval()

    dummy = torch.zeros(1, 2, DUMMY_T)
    print(f'Exporting to {OUT_PATH.name} (opset {OPSET}, dynamic batch + length) ...')
    with torch.no_grad():
        torch.onnx.export(
            model, dummy, str(OUT_PATH),
            input_names=['input'],
            output_names=['cp', 'kac', 'model_type'],
            dynamic_axes={
                'input':      {0: 'B', 2: 'T'},
                'cp':         {0: 'B', 1: 'T'},
                'kac':        {0: 'B', 1: 'T'},
                'model_type': {0: 'B', 1: 'T'},
            },
            opset_version=OPSET,
            do_constant_folding=True,
        )
    kb = OUT_PATH.stat().st_size // 1024
    print(f'  -> {OUT_PATH.name}  ({kb} kB)')

    # ── Verify the ONNX matches PyTorch across several batch sizes / lengths ──
    try:
        import numpy as np
        import onnxruntime as ort
        sess = ort.InferenceSession(str(OUT_PATH), providers=['CPUExecutionProvider'])
        print('\nVerifying ONNX vs PyTorch (max abs diff should be ~1e-6):')
        worst = 0.0
        for B, T in ((1, 32), (4, 64), (8, 224)):
            x = torch.randn(B, 2, T)
            with torch.no_grad():
                cp_t, kac_t, mod_t = model(x)
            cp_o, kac_o, mod_o = sess.run(None, {'input': x.numpy()})
            d = max(np.abs(cp_t.numpy() - cp_o).max(),
                    np.abs(kac_t.numpy() - kac_o).max(),
                    np.abs(mod_t.numpy() - mod_o).max())
            worst = max(worst, d)
            print(f'   B={B} T={T:3d}  max|Δ| = {d:.2e}')
        print('   OK' if worst < 1e-3 else f'   WARNING: large diff {worst:.2e} — export may be unfaithful!')
    except Exception as ex:  # onnxruntime not installed, etc.
        print(f'\n(Skipped ONNX verification: {ex})')

    print()
    print('Reminder: the plugin pads each trajectory length up to a multiple of 32')
    print('before inference (the model has 5 pooling levels), and applies TTA itself')
    print('when enabled in the Data Manager.')
    print()
    print('Next: cp seg_v0.onnx ../../src/main/resources/models/ ; mvn clean package')
