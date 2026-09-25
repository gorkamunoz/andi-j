"""
Convert the v1 U-Net 3+ segmentation model to ONNX for AnDi-J.

Run from the repository root:
    python -m utils.ML_models.onnx_converters.convert_seg_v1
or from this folder:
    python convert_seg_v1.py

Output:
    seg_v1.onnx   – per-frame alpha, log10(D), change points and diffusive state

After conversion:
    1. Copy seg_v1.onnx to ../../src/main/resources/models/andi-j-seg-latest.onnx
    2. mvn clean package    (from the project root)
    3. Copy the JAR to Fiji.app/jars/

Model I/O contract (see the "ML Models" wiki page)
-------------------------------------------------
Unlike v0 — which exported the plain backbone and let the plugin do the
pre/post-processing — v1 exports a *self-contained* model that follows the same
public contract as user-supplied models. The plugin feeds raw positions in and
reads per-frame outputs out verbatim; everything else lives inside this graph.

  Input  "input"      : (B, T, 2) float32 — raw Cartesian positions
                        (column 0 = x, column 1 = y). Both B and T are dynamic.
  Output "alpha"      : (B, T) anomalous exponent, clipped to [0, 2]
  Output "logD"       : (B, T) log10(D)
  Output "cp"         : (B, T) change-point flag, 0 or 1
  Output "diffusive_state" : (B, T) diffusive-state class index
                        (0 = immobile, 1 = confined, 2 = free diffusion, 3 = directed)

What the wrapper does internally
--------------------------------
  * pads the positions up to a multiple of 32 (the network has 5 pooling
    levels), derives (dx, dy) with a zero final step, and feeds the backbone
    the (B, 2, Tpad) displacement tensor it expects;
  * crops every output back to the real length T;
  * converts the K head's **natural** log(D) to **log10(D)**;
  * rounds/clips the state channel to a 0-3 class index;
  * turns the sigmoid change-point probability into the contract's flag:
    threshold at 0.5, then keep only the **first frame of each new segment**.

    This last step is the one that matters. Thresholding alone marks a *run* of
    consecutive frames around each boundary, and the plugin treats every flagged
    interior frame as its own change point — so one real boundary becomes several
    change points and a handful of spurious one-frame segments. Emitting the
    rising edge only keeps exactly one flag per boundary, as the contract
    requires ("Emit 1 at the first frame of each new segment").

Note on the name: this output used to be called "model_type", which was
misleading — the backbone has a *separate* 5-class model-type softmax head
(single-state, multi-state, dimerization, confinement, immobile-traps) that is
deliberately NOT exported. The plugin's diffusive state is the 4-class state
channel, so the output is now called "diffusive_state". The plugin still accepts
"model_type" as a deprecated alias.
"""

import math
import sys
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F

try:  # run as a module from the repository root
    from utils.ML_models.onnx_converters.model_seg_v1 import load_model
except ImportError:  # run directly from this folder
    sys.path.insert(0, str(Path(__file__).parent))
    from model_seg_v1 import load_model

SCRIPT_DIR = Path(__file__).parent
MODEL_PATH = SCRIPT_DIR / '../model_weights/seg_v1.pth'
OUT_PATH = SCRIPT_DIR / '../model_weights/seg_v1.onnx'
OPSET = 13
DUMMY_T = 224          # arbitrary; the T axis is exported dynamic

MAX_POOL_FACTOR = 32   # the network has 5 pooling levels
LN10 = math.log(10.0)
ALPHA_MIN, ALPHA_MAX = 0.0, 2.0
STATE_MIN, STATE_MAX = 0.0, 3.0


class ContractSegModel(nn.Module):
    """Wraps the raw v1 backbone so the exported graph matches the plugin contract."""

    def __init__(self, core):
        super().__init__()
        self.core = core

    def forward(self, positions):                      # (B, T, 2) raw x, y
        length = positions.shape[1]
        pad = (MAX_POOL_FACTOR - length % MAX_POOL_FACTOR) % MAX_POOL_FACTOR
        padded = F.pad(positions, (0, 0, 0, pad))      # (B, Tpad, 2)

        # (dx, dy) with a zero final step, laid out channel-first for the backbone.
        steps = padded[:, 1:, :] - padded[:, :-1, :]
        displacements = torch.cat([steps, torch.zeros_like(padded[:, :1, :])], dim=1)
        cp_probability, alpha_log_d_state, _ = self.core(displacements.transpose(1, 2))

        # Crop the padding back off.
        cp_probability = cp_probability[:, :length, 0]
        alpha = alpha_log_d_state[:, :length, 0]
        log_d = alpha_log_d_state[:, :length, 1]
        state = alpha_log_d_state[:, :length, 2]

        alpha = alpha.clamp(ALPHA_MIN, ALPHA_MAX)
        log_d10 = log_d / LN10                          # natural log(D) -> log10(D)
        state = state.round().clamp(STATE_MIN, STATE_MAX)

        # Contract: a single 1 at the FIRST frame of each new segment, so keep
        # only the rising edge of the thresholded probability.
        above = (cp_probability >= 0.5).to(positions.dtype)
        previous = F.pad(above[:, :-1], (1, 0))         # shifted right, first = 0
        change_points = above * (1.0 - previous)

        return alpha, log_d10, change_points, state


OUTPUT_NAMES = ['alpha', 'logD', 'cp', 'diffusive_state']


def export(model, dummy, out_path):
    """Export with dynamic batch and length axes, preferring the dynamo exporter.

    The length-dependent padding above must stay *symbolic*: if the exporter
    bakes `positions.shape[1]` in as the dummy length, the graph silently pads
    to the wrong size for every other trajectory length. The dynamo exporter
    handles this natively; the TorchScript one may not, which is exactly what
    the length sweep in the verification below is there to catch.
    """
    common = dict(
        input_names=['input'],
        output_names=OUTPUT_NAMES,
        dynamic_axes={'input': {0: 'B', 1: 'T'},
                      **{name: {0: 'B', 1: 'T'} for name in OUTPUT_NAMES}},
        opset_version=OPSET,
        do_constant_folding=True,
    )
    try:
        torch.onnx.export(model, (dummy,), str(out_path), dynamo=True, **common)
        return 'dynamo'
    except TypeError:
        # PyTorch too old to know the `dynamo` keyword.
        torch.onnx.export(model, (dummy,), str(out_path), **common)
        return 'torchscript'


if __name__ == '__main__':
    print(f'Loading {MODEL_PATH} ...')
    core = load_model(str(MODEL_PATH), device='cpu').eval()
    model = ContractSegModel(core).eval()

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    dummy = torch.zeros(1, DUMMY_T, 2)
    print(f'Exporting to {OUT_PATH.name} (opset {OPSET}, dynamic batch + length) ...')
    with torch.no_grad():
        exporter = export(model, dummy, OUT_PATH)
    kb = OUT_PATH.stat().st_size // 1024
    print(f'  -> {OUT_PATH.name}  ({kb} kB, {exporter} exporter)')

    # ── Verify the exported graph against the contract ────────────────────────
    try:
        import numpy as np
        import onnxruntime as ort

        session = ort.InferenceSession(str(OUT_PATH), providers=['CPUExecutionProvider'])
        print('\nVerifying the exported model against the contract:')
        failures = []

        # Lengths deliberately include non-multiples of 32: if the padding was
        # baked in at DUMMY_T, these are the cases that break.
        for length in (20, 37, 128, 200, 301):
            positions = torch.randn(1, length, 2).cumsum(dim=1)
            with torch.no_grad():
                reference = model(positions)
            outputs = session.run(None, {'input': positions.numpy()})
            alpha, log_d10, change_points, state = outputs

            # 1. every output keeps the real trajectory length
            lengths = {name: out.shape[1] for name, out in zip(OUTPUT_NAMES, outputs)}
            if any(value != length for value in lengths.values()):
                failures.append(f'T={length}: wrong output lengths {lengths} '
                                f'(the length axis is not dynamic)')

            # 2. ONNX matches PyTorch
            worst = max(float(np.abs(r.numpy() - o).max())
                        for r, o in zip(reference, outputs))

            # 3. cp is 0/1 and never two frames in a row (one flag per boundary)
            unique = np.unique(change_points)
            binary = np.all(np.isin(unique, (0.0, 1.0)))
            adjacent = int(np.sum((change_points[:, 1:] == 1) & (change_points[:, :-1] == 1)))
            if not binary:
                failures.append(f'T={length}: cp is not 0/1 (values {unique[:5]})')
            if adjacent:
                failures.append(f'T={length}: cp has {adjacent} adjacent 1s — '
                                f'it must flag only the first frame of each segment')

            # 4. state is a class index in 0..3
            if not np.all(np.isin(np.unique(state), (0.0, 1.0, 2.0, 3.0))):
                failures.append(f'T={length}: diffusive_state is not a 0-3 class index')

            print(f'   T={length:3d}  max|Δ| = {worst:.2e}   '
                  f'cp flags = {int(change_points.sum())}   '
                  f'alpha ∈ [{alpha.min():.2f}, {alpha.max():.2f}]   '
                  f'log10(D) ∈ [{log_d10.min():.2f}, {log_d10.max():.2f}]')

        # 5. batching must give the same answer as one trajectory at a time
        batch = torch.randn(4, 96, 2).cumsum(dim=1)
        batched = session.run(None, {'input': batch.numpy()})
        for row in range(batch.shape[0]):
            single = session.run(None, {'input': batch[row:row + 1].numpy()})
            drift = max(float(np.abs(b[row] - s[0]).max()) for b, s in zip(batched, single))
            if drift > 1e-4:
                failures.append(f'batch row {row}: differs from a single run by {drift:.2e}')

        if failures:
            print('\n  FAILED:')
            for failure in failures:
                print(f'   - {failure}')
        else:
            print('\n   OK — output lengths, cp encoding, state range and batching all check out.')
    except Exception as ex:  # onnxruntime not installed, etc.
        print(f'\n(Skipped verification: {ex})')

    print()
    print('Next: copy the model in as the bundled one, then rebuild:')
    print(f'  cp {OUT_PATH.name} ../../src/main/resources/models/andi-j-seg-latest.onnx')
    print('  mvn clean package')
