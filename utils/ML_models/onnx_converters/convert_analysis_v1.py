"""
Convert the v1 analysis model (two FCI-type networks) to one ONNX file for AnDi-J.

Run in the environment the model was developed in (conda env `kistep`, with onnx,
onnxscript and onnxruntime 1.26.0 installed):

    python convert_analysis_v1.py               # export + contract checks
    python convert_analysis_v1.py --benchmark   # ... + AnDi-1 2D benchmark (MAE, F1)

Inputs:   ../model_weights/analysis_v1_alpha.pth   exponent network (40-bin distribution)
          ../model_weights/analysis_v1_cls.pth     classification network (5 classes)
Output:   ../model_weights/analysis_v1.onnx

Then:
    cp ../model_weights/analysis_v1.onnx ../../../src/main/resources/models/andi-j-analysis-latest.onnx
    mvn clean package   (from the plugin root)

Model I/O contract (see the "ML Models" wiki page)
--------------------------------------------------
  Input  "input" : (B, T, 2) float32 raw positions (column 0 = x, column 1 = y);
                   B and T dynamic.
  Output "alpha" : (B, 1) anomalous exponent.
  Output "model" : (B, 5) diffusion-model scores, order ATTM, CTRW, FBM, LW, SBM;
                   predicted class = argmax.

What the graph does
-------------------
  1. Keeps the last MAX_T - 1 = 1000 positions and shifts the trajectory so that its
     first kept position is the origin (the networks were trained on trajectories that
     start at (0, 0)).
  2. Zero-pads the positions at the front, takes the displacements and divides them by
     the mean step size. The networks were trained with padding to MAX_T = 1001 points;
     the graph pads with only RECEPTIVE_FIELD + 1 zeros (capped at MAX_T points), which
     gives the same outputs: past one receptive field of zeros every activation is a
     constant, so the global max over time does not depend on how many such steps there
     are. Short trajectories therefore cost proportionally less.
  3. Runs each network on the 8 symmetric views of the displacements (sign flips of x
     and y, times swapping x and y) and averages the softmax probabilities.
  4. alpha = median of the exponent distribution over the 40-value grid, interpolated
     inside its bin.
  5. Class scores multiplied by the probability the exponent distribution puts on
     alpha <= 1: ATTM and CTRW by P(alpha <= 1), LW by 1 - P(alpha <= 1).
"""

import argparse
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

try:  # run as a module from the repository root
    from utils.ML_models.onnx_converters.model_analysis_v1 import (
        ALPHA_GRID, MAX_T, MODELS, RECEPTIVE_FIELD, load_model)
except ImportError:  # run directly from this folder
    sys.path.insert(0, str(Path(__file__).parent))
    from model_analysis_v1 import ALPHA_GRID, MAX_T, MODELS, RECEPTIVE_FIELD, load_model

SCRIPT_DIR = Path(__file__).parent
WEIGHTS = SCRIPT_DIR / '../model_weights'
ALPHA_PATH = WEIGHTS / 'analysis_v1_alpha.pth'
CLS_PATH = WEIGHTS / 'analysis_v1_cls.pth'
OUT_PATH = WEIGHTS / 'analysis_v1.onnx'
ANDI1_PATH = Path('~/github/andi_app/csv').expanduser()   # AnDi-1 challenge files
OPSET = 18
DUMMY_T = 50          # arbitrary; the T axis is exported dynamic
BIN_WIDTH = 0.05      # spacing of ALPHA_GRID


class ContractAnalysisModel(nn.Module):
    """Raw positions in, (alpha, class scores) out: the plugin contract."""

    def __init__(self, alpha_net, cls_net):
        super().__init__()
        self.alpha_net, self.cls_net = alpha_net, cls_net
        self.register_buffer('grid', torch.tensor(ALPHA_GRID, dtype=torch.float32))
        self.register_buffer('sub', torch.tensor(ALPHA_GRID <= 1.0, dtype=torch.float32))
        signs = [[sx, sy] for sx in (1., -1.) for sy in (1., -1.)]
        self.register_buffer('signs', torch.tensor(signs).view(4, 1, 1, 2))

    def displacements(self, positions):                   # (B, T, 2) -> (B, L, 2)
        positions = positions[:, -(MAX_T - 1):, :]
        origin = positions[:, 0, :].unsqueeze(1)          # (B, 1, 2)
        positions = positions - origin
        zeros = torch.zeros_like(origin).repeat(1, RECEPTIVE_FIELD + 1, 1)
        xy = torch.cat([zeros, positions], dim=1)[:, -MAX_T:, :]
        d = xy[:, 1:, :] - xy[:, :-1, :]
        # mean step: summed step lengths over the number of non-zero x positions
        n = (xy[:, :, 0] != 0).sum(dim=1).clamp(min=1).to(d.dtype)
        mean_step = torch.sqrt((d ** 2).sum(-1)).sum(dim=1) / n
        return d / mean_step.clamp(min=1e-12)[:, None, None]

    def symmetric_mean(self, net, x):
        """Softmax probabilities averaged over the 8 sign-flip / axis-swap views."""
        flips = (x.unsqueeze(0) * self.signs)              # (4, B, L, 2)
        views = torch.cat([flips, flips.flip(-1)], dim=0)  # (8, B, L, 2)
        batch = x.shape[0]
        out = net(views.reshape(-1, x.shape[1], 2)).softmax(-1)
        return out.reshape(8, batch, -1).mean(0)

    def forward(self, positions):
        x = self.displacements(positions)
        p_alpha = self.symmetric_mean(self.alpha_net, x)   # (B, 40)
        p_cls = self.symmetric_mean(self.cls_net, x)       # (B, 5)

        cum = p_alpha.cumsum(-1)
        idx = (cum < 0.5).sum(-1, keepdim=True).clamp(max=len(ALPHA_GRID) - 1)
        over = cum.gather(-1, idx) - 0.5
        frac = (over / p_alpha.gather(-1, idx).clamp(min=1e-6)).clamp(0.0, 1.0)
        alpha = self.grid[idx] - BIN_WIDTH * frac          # (B, 1)

        sub = (p_alpha * self.sub).sum(-1, keepdim=True)   # P(alpha <= 1)
        one = torch.ones_like(sub)
        prior = torch.cat([sub, sub, one, 1.0 - sub, one], dim=1)
        return alpha, p_cls * prior.clamp(min=1e-6)


OUTPUT_NAMES = ['alpha', 'model']


def export(model, out_path):
    """Export with dynamic batch and length axes."""
    dummy = torch.randn(1, DUMMY_T, 2).cumsum(dim=1)
    dims = {0: torch.export.Dim('B', min=1), 1: torch.export.Dim('T', min=2, max=100_000)}
    with torch.no_grad():
        torch.onnx.export(model, (dummy,), str(out_path), dynamo=True, external_data=False,
                          input_names=['input'], output_names=OUTPUT_NAMES,
                          dynamic_shapes={'positions': dims}, opset_version=OPSET)


def full_padding_reference(model, positions):
    """The outputs with the training-time padding to MAX_T points."""
    positions = positions[:, -(MAX_T - 1):, :]
    positions = positions - positions[:, 0, :].unsqueeze(1)
    xy = torch.cat([torch.zeros(positions.shape[0], MAX_T, 2), positions], dim=1)[:, -MAX_T:]
    d = xy[:, 1:] - xy[:, :-1]
    n = (xy[:, :, 0] != 0).sum(dim=1).clamp(min=1).float()
    x = d / (torch.sqrt((d ** 2).sum(-1)).sum(dim=1) / n).clamp(min=1e-12)[:, None, None]
    original, model.displacements = model.displacements, lambda _: x
    try:
        return [r.numpy() for r in model(positions)]
    finally:
        model.displacements = original


def verify(model, session):
    """Contract checks: ONNX = PyTorch = full padding, shift invariance, batching."""
    failures = []
    for length in (2, 10, 37, 200, 999, 1000, 1001, 1500):
        positions = torch.randn(1, length, 2).cumsum(dim=1)
        with torch.no_grad():
            reference = [r.numpy() for r in model(positions)]
        outputs = session.run(None, {'input': positions.numpy()})
        shapes = [o.shape for o in outputs]
        if shapes != [(1, 1), (1, 5)]:
            failures.append(f'T={length}: output shapes {shapes}')
        worst = max(float(np.abs(r - o).max()) for r, o in zip(reference, outputs))
        if worst > 1e-4:
            failures.append(f'T={length}: ONNX differs from PyTorch by {worst:.2e}')
        with torch.no_grad():
            padded = full_padding_reference(model, positions)
        gap = max(float(np.abs(r - o).max()) for r, o in zip(padded, outputs))
        if gap > 1e-4:
            failures.append(f'T={length}: differs from full padding by {gap:.2e}')
        print(f'   T={length:4d}  max|Δ| = {worst:.1e}  vs full padding {gap:.1e}   alpha = {outputs[0][0, 0]:.3f}   '
              f'model = {MODELS[int(outputs[1].argmax())]}')

    # Absolute positions (e.g. µm in a TrackMate file) must give the same answer.
    positions = np.cumsum(np.random.randn(1, 300, 2), axis=1).astype('float32')
    base = session.run(None, {'input': positions})
    moved = session.run(None, {'input': positions + np.float32([35.2, -12.7])})
    drift = max(float(np.abs(a - b).max()) for a, b in zip(base, moved))
    print(f'   shift by (35.2, -12.7): max|Δ| = {drift:.1e}')
    if drift > 1e-3:
        failures.append(f'shifted trajectory differs by {drift:.2e}')

    batch = np.cumsum(np.random.randn(4, 120, 2), axis=1).astype('float32')
    batched = session.run(None, {'input': batch})
    for row in range(len(batch)):
        single = session.run(None, {'input': batch[row:row + 1]})
        drift = max(float(np.abs(b[row] - s[0]).max()) for b, s in zip(batched, single))
        if drift > 1e-4:
            failures.append(f'batch row {row} differs from a single run by {drift:.2e}')

    print('   CPU time per trajectory:')
    for length in (20, 100, 300, 1000):
        cells = []
        for batch in (1, 32):
            x = np.cumsum(np.random.randn(batch, length, 2), axis=1).astype('float32')
            session.run(None, {'input': x})
            started = time.perf_counter()
            for _ in range(5):
                session.run(None, {'input': x})
            cells.append(f'batch {batch}: {(time.perf_counter() - started) / 5 / batch * 1000:4.0f} ms')
        print(f'     T={length:4d}   ' + '   '.join(cells))
    return failures


def benchmark(session, path):
    """MAE (task 1) and micro-F1 (task 2) on the 5000 scored AnDi-1 2D trajectories."""
    import pandas as pd
    from andi_datasets.datasets_challenge import challenge_theory_dataset

    X1, _, X2, _, _, _ = challenge_theory_dataset(
        load_dataset=True, path_datasets=str(path) + '/', tasks=[1, 2], dimensions=[2])
    results = {}
    for task, trajs, csv in ((1, X1[1], 'task1_2d.csv'), (2, X2[1], 'task2_2d.csv')):
        df = pd.read_csv(path / csv)
        alpha, model = np.empty(len(df)), np.empty(len(df), int)
        by_length = {}
        for i, t in enumerate(trajs[:len(df)]):
            T = len(t) // 2
            by_length.setdefault(T, []).append((i, np.stack([t[:T], t[T:]], axis=1)))
        for T, items in by_length.items():
            for start in range(0, len(items), 64):
                chunk = items[start:start + 64]
                x = np.stack([xy for _, xy in chunk]).astype('float32')
                a, m = session.run(None, {'input': x})
                for (i, _), ai, mi in zip(chunk, a[:, 0], m.argmax(1)):
                    alpha[i], model[i] = ai, mi
        if task == 1:
            results['mae_full'] = float(np.abs(alpha - df.alpha.values).mean())
        else:
            results['f1_full'] = float((model == df.model.values.astype(int)).mean())
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[1])
    parser.add_argument('--benchmark', action='store_true',
                        help='also score the ONNX model on the AnDi-1 2D benchmark')
    parser.add_argument('--andi1', type=Path, default=ANDI1_PATH,
                        help='folder with the AnDi-1 challenge files')
    args = parser.parse_args()

    print(f'Loading {ALPHA_PATH.name} and {CLS_PATH.name} ...')
    model = ContractAnalysisModel(load_model(ALPHA_PATH, len(ALPHA_GRID)),
                                  load_model(CLS_PATH, len(MODELS))).eval()

    print(f'Exporting to {OUT_PATH.name} (opset {OPSET}, dynamic batch + length) ...')
    export(model, OUT_PATH)
    print(f'  -> {OUT_PATH.name}  ({OUT_PATH.stat().st_size // 1024} kB)')

    import onnxruntime as ort
    session = ort.InferenceSession(str(OUT_PATH), providers=['CPUExecutionProvider'])
    print('\nVerifying the exported model against the contract:')
    failures = verify(model, session)
    if failures:
        print('\n  FAILED:')
        for failure in failures:
            print(f'   - {failure}')
        sys.exit(1)
    print('   OK: shapes, PyTorch parity, shift invariance and batching all check out.')

    if args.benchmark:
        print('\nAnDi-1 2D benchmark (5000 trajectories per task):')
        for name, value in benchmark(session, args.andi1).items():
            print(f'   {name}: {value:.4f}')

    print('\nNext: copy the model in as the bundled one, then rebuild:')
    print(f'  cp {OUT_PATH} ../../../src/main/resources/models/andi-j-analysis-latest.onnx')
    print('  mvn clean package')
