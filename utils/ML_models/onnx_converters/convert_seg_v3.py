"""
Convert the v3 segmentation model (U-Net 3+, weights seg_v3.pth) to ONNX for AnDi-J.

Run from this folder (conda env `ucl_andi`: torch, onnx, onnxruntime, andi_datasets,
scikit-learn):

    python convert_seg_v3.py                       # export + contract checks
    python convert_seg_v3.py --benchmark DATA      # ... + AnDi-2 scores on DATA

DATA is a 2nd AnDi Challenge dataset folder holding track_2/exp_*/trajs_fov_*.csv and
the matching traj_labs_fov_*.txt (e.g. the challenge-phase data).

Input:   ../model_weights/seg_v3.pth
Output:  ../model_weights/seg_v3.onnx

Then:
    cp ../model_weights/seg_v3.onnx ../../../src/main/resources/models/andi-j-seg-latest.onnx
    mvn clean package   (from the plugin root)

Model I/O contract (see the "ML Models" wiki page)
--------------------------------------------------
  Input  "input"           : (1, T, 2) float32 raw positions; T dynamic.
  Output "alpha"           : (1, T) anomalous exponent, in [0, 1.999]
  Output "logD"            : (1, T) log10(D)
  Output "cp"              : (1, T) change-point flag, 1 at the first frame of a segment
  Output "diffusive_state" : (1, T, 4) probabilities (immobile, confined, free, directed)
  Output "seg_start", "seg_length", "seg_alpha", "seg_logD", "seg_state" : (1, S),
                             S = T; rows with seg_length 0 are padding.

What the graph does
-------------------
  1. Displacements (dx, dy) with a zero final step, zero-padded to a multiple of 32.
  2. The U-Net runs on the 8 views of the D4 group (4 rotations x 2 reflections),
     carried on the batch axis; per-frame outputs are averaged over the views (the
     diffusive state as softmax probabilities).
  3. alpha = 0.85 * alpha without the temporal branch + 0.15 * alpha with it, clipped
     to [0, 1.999]; logD converted from natural log to log10; frames with alpha > 1.9
     are given the directed state.
  4. Change points: strict local maxima of the change-point probability >= 0.32, at
     least 3 frames from the start and 2 from the end.
  5. Segments: per segment, the median of each per-frame channel; seg_alpha blends
     the two alpha medians as in step 3, seg_logD is clipped to [-12, 6] and
     seg_state is the argmax of the median state probabilities (directed when
     seg_alpha > 1.9).

The batch axis is fixed at 1 (the plugin runs one trajectory per call), so there is no
batched-vs-single check; trajectories run in parallel instead.
"""

import argparse
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

try:  # run as a module from the repository root
    from utils.ML_models.onnx_converters.model_seg_v3 import load_model
except ImportError:  # run directly from this folder
    sys.path.insert(0, str(Path(__file__).parent))
    from model_seg_v3 import load_model

SCRIPT_DIR = Path(__file__).parent
MODEL_PATH = SCRIPT_DIR / '../model_weights/seg_v3.pth'
OUT_PATH = SCRIPT_DIR / '../model_weights/seg_v3.onnx'
OPSET = 13
DUMMY_T = 224          # arbitrary; the T axis is exported dynamic
MAX_POOL_FACTOR = 32   # 6 encoder levels, so the U-Net needs a length divisible by 32
BIG = 1.0e30           # stands in for +inf when masking non-members out of a sort
MIN_PEAK_HEIGHT = 0.32  # change-point probability threshold
ALPHA_BRANCH_W = 0.15   # weight of the alpha with the temporal branch

OUTPUT_NAMES = ["alpha", "logD", "cp", "diffusive_state",
                "seg_start", "seg_length", "seg_alpha", "seg_logD", "seg_state"]


def d4_views():
    """The 8 D4 symmetries as (8, 2, 2) matrices acting on (dx, dy)."""
    mats = []
    for k in range(4):
        c = float(np.cos(k * np.pi / 2))
        s = float(np.sin(k * np.pi / 2))
        for sx in (1.0, -1.0):
            # dx' = (dx c - dy s) sx ;  dy' = dx s + dy c
            mats.append([[sx * c, -sx * s], [s, c]])
    return torch.tensor(mats, dtype=torch.float32)


def masked_median(values, member, count):
    """Median of `values` over each segment's frames, as np.median (even counts average
    the two middle values).

    values (C, T), member (S, T) one-hot segment membership, count (S,) frames per
    segment. Non-members are pushed past the end of an ascending sort, so a segment's
    own frames occupy positions 0..count-1 and the median is read off by index.
    """
    ordered = torch.sort(torch.where(member.unsqueeze(0), values.unsqueeze(1), BIG),
                         dim=-1).values                                  # (C, S, T)
    low = torch.floor((count - 1.0) / 2.0).clamp(min=0.0).to(torch.int64)
    high = torch.floor(count / 2.0).clamp(min=0.0).to(torch.int64)
    gather = lambda i: torch.gather(ordered, 2, i.view(1, -1, 1).repeat(ordered.shape[0], 1, 1))
    median = 0.5 * (gather(low) + gather(high)).squeeze(-1)               # (C, S)
    return torch.where(count > 0.0, median, torch.zeros_like(median))


class AndiJSegmentationModel(nn.Module):
    """Raw positions in, the per-frame and segment outputs of the plugin contract out."""

    def __init__(self, model):
        super().__init__()
        self.model = model
        self.register_buffer("views", d4_views())

    def forward(self, positions):
        original_length = positions.shape[1]
        # Zero final step: same as padding the trajectory with its own endpoint.
        displacements = torch.cat(
            [positions[:, 1:] - positions[:, :-1], torch.zeros_like(positions[:, :1])], dim=1)
        displacements = F.pad(displacements[0].transpose(0, 1),
                              (0, -original_length % MAX_POOL_FACTOR))        # (2, padded)
        # The 8 views ride the batch axis, so the U-Net runs once.
        cp_probability, alpha_log_d_state, _ = self.model(self.views @ displacements)

        # Channels: 0 = alpha with the temporal branch, 1 = log(D), 2 = state regression,
        # 3:7 = state logits, 7 = alpha without the branch. The state is averaged over the
        # views in probability space.
        alpha_log_d_state = alpha_log_d_state[:, :original_length]
        alpha_log_d_state = torch.cat(
            [alpha_log_d_state[..., :3], torch.softmax(alpha_log_d_state[..., 3:7], dim=-1),
             alpha_log_d_state[..., 7:]], dim=-1).mean(0, keepdim=True)
        cp_probability = cp_probability[:, :original_length].mean(0, keepdim=True)

        # ── the four required per-frame outputs ──────────────────────────────────────
        alpha = ((1.0 - ALPHA_BRANCH_W) * alpha_log_d_state[..., 7]
                 + ALPHA_BRANCH_W * alpha_log_d_state[..., 0]).clamp(0.0, 1.999)
        log_d = alpha_log_d_state[..., 1] / torch.log(torch.tensor(10.0))
        state_probability = alpha_log_d_state[..., 3:7]
        # Frames with alpha above 1.9 are directed (one-hot vote for class 3).
        diffusive_state = torch.where(alpha.unsqueeze(-1) > 1.9,
                                      torch.tensor([0.0, 0.0, 0.0, 1.0]), state_probability)

        # Change points: strict local maxima above the threshold (they are therefore at
        # least 2 frames apart), one flag at the first frame of each new segment.
        p = cp_probability[..., 0]
        previous = F.pad(p[:, :-1], (1, 0), value=-1.0)
        following = F.pad(p[:, 1:], (0, 1), value=-1.0)
        cp = (p >= MIN_PEAK_HEIGHT) & (p > previous) & (p > following)
        # Peaks within 2 frames of either end are dropped. The frame index comes from the
        # input, never from the traced dummy length.
        index = torch.cumsum(torch.ones_like(p), dim=1) - 1.0
        # torch.where, not .to(dtype): a Cast leaves the batch axis symbolic in the
        # exported graph, and the contract asks for a literal (1, T).
        cp = cp & (index >= 3.0) & (index <= index[:, -1:] - 2.0)
        cp = torch.where(cp, torch.ones_like(p), torch.zeros_like(p))[0].unsqueeze(0)

        # ── the optional segment-level outputs ───────────────────────────────────────
        # The running count of cp flags is the segment each frame belongs to. Rows are
        # padded out to T; unused ones get seg_length 0, which the plugin skips.
        belongs_to = torch.cumsum(cp, dim=1)                                  # (1, T)
        member = belongs_to == index.transpose(0, 1)                          # (S, T), S = T
        seg_length = member.to(positions.dtype).sum(dim=1)                    # (S,)
        seg_start = torch.where(member, index, torch.full_like(index, BIG)).min(dim=1).values
        seg_start = torch.where(seg_length > 0.0, seg_start, torch.zeros_like(seg_start))

        # Median of each channel over the segment, then the two alpha medians blended.
        per_frame = torch.cat([alpha_log_d_state[..., 7:], alpha_log_d_state[..., :1],
                               alpha_log_d_state[..., 1:2], state_probability],
                              dim=-1)[0].transpose(0, 1)                      # (7, T)
        median = masked_median(per_frame, member, seg_length)
        seg_alpha = ((1.0 - ALPHA_BRANCH_W) * median[0]
                     + ALPHA_BRANCH_W * median[1]).clamp(0.0, 1.999)
        # D clipped to [1e-12, 1e6], i.e. log10(D) to [-12, 6].
        seg_log_d = (median[2] / torch.log(torch.tensor(10.0))).clamp(-12.0, 6.0)
        seg_state = median[3:7].argmax(dim=0)
        seg_state = torch.where(seg_alpha > 1.9, torch.full_like(seg_state, 3), seg_state)

        return (alpha, log_d, cp, diffusive_state,
                seg_start.to(torch.int64).unsqueeze(0), seg_length.to(torch.int64).unsqueeze(0),
                seg_alpha.unsqueeze(0), seg_log_d.unsqueeze(0), seg_state.unsqueeze(0))


def export(model, out_path):
    """TorchScript export with a dynamic length axis (batch is always 1)."""
    axes = {name: {1: "S" if name.startswith("seg_") else "T"} for name in OUTPUT_NAMES}
    torch.onnx.export(
        model,
        torch.zeros(1, DUMMY_T, 2),
        str(out_path),
        input_names=["input"],
        output_names=OUTPUT_NAMES,
        dynamic_axes={"input": {1: "T"}, **axes},
        opset_version=OPSET,
        do_constant_folding=True,
        dynamo=False,
    )


def verify(model, session):
    """Contract checks: ONNX = PyTorch at many lengths, shapes, invariances."""
    failures = []
    names = [o.name for o in session.get_outputs()]
    if names != OUTPUT_NAMES:
        failures.append(f'output names {names}')
    for length in (2, 10, 37, 200, 224, 1000):
        # slow diffusion, then 10x faster from the middle on: one real change point
        scale = torch.where(torch.arange(length) < length // 2, 0.1, 1.0).view(1, -1, 1)
        positions = (torch.randn(1, length, 2) * scale).cumsum(dim=1)
        with torch.no_grad():
            reference = [r.numpy() for r in model(positions)]
        outputs = session.run(None, {'input': positions.numpy()})
        for name, out in zip(OUTPUT_NAMES, outputs):
            expected = (1, length, 4) if name == 'diffusive_state' else (1, length)
            if out.shape != expected:
                failures.append(f'T={length}: {name} has shape {out.shape}, expected {expected}')
        worst = max(float(np.abs(r.astype(float) - o.astype(float)).max())
                    for r, o in zip(reference, outputs))
        if worst > 1e-4:
            failures.append(f'T={length}: ONNX differs from PyTorch by {worst:.2e}')
        segments = int((outputs[5] > 0).sum())
        print(f'   T={length:4d}  max|Δ| = {worst:.1e}   change points = {int(outputs[2].sum())}'
              f'   segments = {segments}')

    # Translation, rotation and reflection must not change the prediction.
    positions = np.cumsum(np.random.randn(1, 300, 2), axis=1).astype('float32')
    base = session.run(None, {'input': positions})
    transforms = {'shift': positions + np.float32([30.0, -7.0]),
                  'rotate 90': np.stack([-positions[..., 1], positions[..., 0]], -1),
                  'mirror x': np.stack([-positions[..., 0], positions[..., 1]], -1)}
    for label, moved in transforms.items():
        out = session.run(None, {'input': moved.astype('float32')})
        drift = max(float(np.abs(a.astype(float) - b.astype(float)).max())
                    for a, b in zip(base[:4], out[:4]))
        print(f'   {label:9s}: max|Δ| = {drift:.1e}')
        if drift > 1e-3:
            failures.append(f'{label}: prediction changes by {drift:.2e}')

    for length in (20, 200, 1000):
        x = np.cumsum(np.random.randn(1, length, 2), axis=1).astype('float32')
        session.run(None, {'input': x})
        started = time.perf_counter()
        for _ in range(3):
            session.run(None, {'input': x})
        print(f'   CPU time T={length:4d}: {(time.perf_counter() - started) / 3 * 1000:.0f} ms')
    return failures


IMMOBILE_ALPHA_MAX = 0.1   # the plugin's rule for the bundled model: alpha < 0.1 -> immobile
STATE_NAMES = ['immobile', 'confined', 'free', 'directed']


def _segments(outputs):
    """(D, alpha, state, end) per segment, from the seg_* outputs of one trajectory."""
    start, length = outputs[4][0], outputs[5][0]
    segs = []
    for i in np.flatnonzero(length > 0):
        segs.append((float(10.0 ** outputs[7][0][i]), float(outputs[6][0][i]),
                     int(outputs[8][0][i]), int(start[i] + length[i])))
    return segs


def _read_labels(path):
    """traj_idx -> (changepoints incl. T, alphas, Ds, states) from a traj_labs_fov file."""
    labels = {}
    for line in open(path):
        v = line.strip().split(',')
        rest = [float(x) for x in v[1:]]
        n = len(rest) // 4
        labels[int(float(v[0]))] = (np.array([int(rest[4 * i + 3]) for i in range(n)]),
                                    np.array([rest[4 * i + 1] for i in range(n)]),
                                    np.array([rest[4 * i] for i in range(n)]),
                                    np.array([rest[4 * i + 2] for i in range(n)]))
    return labels


def _score(pred_dir, data_path, out_dir, per_traj, labels):
    """Official scores (codalab_scoring_local) and per-class state F1 of one submission."""
    from andi_datasets.utils_challenge import codalab_scoring_local, segment_property_errors
    from sklearn.metrics import f1_score
    codalab_scoring_local(submit_dir=str(pred_dir), truth_dir=str(data_path),
                          output_dir=str(out_dir), scores_filename='scores.txt',
                          html_filename='scores.html')
    names = {'cp': 'rmse_cp', 'JI': 'ji_cp', 'alpha': 'mae_alpha', 'D': 'msle_D',
             'state': 'f1_state'}
    scores = {}
    for line in open(out_dir / 'scores.txt'):
        key, _, val = line.partition(':')
        if key.startswith('tr2.ta1.') and key[8:].strip() in names:
            scores[names[key[8:].strip()]] = float(val)
    gt, pr = [], []
    for key, segs in per_traj.items():
        cp_t, a_t, d_t, s_t = labels[key]
        cp_p = np.array([s[3] for s in segs]); a_p = np.array([s[1] for s in segs])
        d_p = np.array([s[0] for s in segs]); s_p = np.array([float(s[2]) for s in segs])
        _, _, pairs = segment_property_errors(cp_t.astype(int), a_t, d_t, s_t,
                                              cp_p.astype(int), a_p, d_p, s_p,
                                              return_pairs=True)
        if len(pairs):
            gt.append(pairs[:, 0]); pr.append(pairs[:, 1])
    f1 = f1_score(np.concatenate(gt).astype(int), np.concatenate(pr).astype(int),
                  labels=[0, 1, 2, 3], average=None, zero_division=0)
    scores.update({f'f1_{n}': float(v) for n, v in zip(STATE_NAMES, f1)})
    scores['f1_macro'] = float(f1.mean())
    return scores


def benchmark(data_path, out_dir):
    """AnDi-2 scores of the exported model on `data_path`: as exported, and with the
    plugin's immobile rule applied to the segments."""
    import os
    import shutil
    from concurrent.futures import ThreadPoolExecutor
    import onnxruntime as ort
    import pandas as pd

    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    session = ort.InferenceSession(str(OUT_PATH), options, providers=['CPUExecutionProvider'])
    out_dir = Path(out_dir)
    shutil.rmtree(out_dir, ignore_errors=True)

    track2 = Path(data_path) / 'track_2'
    jobs = []        # (exp, fov, traj_idx, positions)
    labels = {}
    for exp in sorted((p.name for p in track2.iterdir() if p.name.startswith('exp_')),
                      key=lambda e: int(e.split('_')[1])):
        for csv in sorted((track2 / exp).glob('trajs_fov_*.csv'),
                          key=lambda p: int(p.stem.split('_')[2])):
            fov = csv.stem.split('_')[2]
            for ti, lab in _read_labels(track2 / exp / f'traj_labs_fov_{fov}.txt').items():
                labels[(exp, fov, ti)] = lab
            df = pd.read_csv(csv).sort_values(['traj_idx', 'frame'])
            for ti, g in df.groupby('traj_idx', sort=True):
                jobs.append((exp, fov, int(ti), g[['x', 'y']].to_numpy(np.float32)[None]))
    print(f'   {len(jobs)} trajectories ...')
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=os.cpu_count()) as pool:
        segs = list(pool.map(lambda j: _segments(session.run(None, {'input': j[3]})), jobs))
    print(f'   inference: {(time.perf_counter() - started) / len(jobs) * 1000:.1f} ms '
          f'per trajectory (wall, {os.cpu_count()} threads)')

    results = {}
    for variant in ('as exported', 'immobile rule'):
        per_traj, files = {}, {}
        for (exp, fov, ti, _), s in zip(jobs, segs):
            if variant == 'immobile rule':
                s = [(d, a, 0 if a < IMMOBILE_ALPHA_MAX else st, end) for d, a, st, end in s]
            per_traj[(exp, fov, ti)] = s
            line = ','.join([str(ti)] + [f'{v}' for seg in s for v in seg])
            files.setdefault((exp, fov), []).append(line)
        pred_dir = out_dir / variant.replace(' ', '_') / 'predictions'
        for (exp, fov), lines in files.items():
            path = pred_dir / 'track_2' / exp / f'fov_{fov}.txt'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('\n'.join(lines) + '\n')
        results[variant] = _score(pred_dir, data_path, pred_dir.parent, per_traj, labels)
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('--benchmark', metavar='DATA',
                        help='also score the ONNX model on this AnDi-2 dataset folder')
    parser.add_argument('--out', default='benchmark_seg_v3',
                        help='folder for the benchmark predictions and scores')
    args = parser.parse_args()

    print(f'Loading {MODEL_PATH.name} ...')
    model = AndiJSegmentationModel(load_model(MODEL_PATH)).eval()

    print(f'Exporting to {OUT_PATH.name} (opset {OPSET}, dynamic length) ...')
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
    print('   OK: names, shapes, PyTorch parity and invariances all check out.')

    if args.benchmark:
        print(f'\nAnDi-2 benchmark on {args.benchmark}:')
        keys = ['rmse_cp', 'ji_cp', 'mae_alpha', 'msle_D', 'f1_state',
                'f1_immobile', 'f1_confined', 'f1_free', 'f1_directed', 'f1_macro']
        for variant, scores in benchmark(args.benchmark, args.out).items():
            print(f'   {variant}:')
            for k in keys:
                print(f'     {k:12s} {scores[k]:.4f}')

    print('\nNext: copy the model in as the bundled one, then rebuild:')
    print(f'  cp {OUT_PATH} ../../../src/main/resources/models/andi-j-seg-latest.onnx')
    print('  mvn clean package')
