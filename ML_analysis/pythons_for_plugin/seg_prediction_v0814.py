"""
UCL-Net (UNet3+) inference bridge.

This file is self-contained: it defines the model architecture,
preprocessing, and postprocessing so it can be used as a reference
for ONNX export or standalone inference.

Model:  ucl_step/model/model_best.pth
Input:  dfs_traj  (list of DataFrames from challenge_phenom_dataset)
Output: per-step alpha, per-step D, and change-point positions per trajectory
"""

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from scipy import signal

# ── Device ────────────────────────────────────────────────────────────────────

dvc = 'cuda' if torch.cuda.is_available() else 'cpu'

# ── Model architecture ────────────────────────────────────────────────────────

def _conv(in_ch, out_ch):
    return nn.Sequential(nn.Conv1d(in_ch, out_ch, 3, padding=1), nn.ReLU())


class UNet3P(nn.Module):
    """
    U-Net 3+ (1-D).
    Input:  (B, 2, T)  — 2-channel displacement trajectory (dx, dy)
    Output:
        cp          (B, T, 1)  — sigmoid change-point probability per timestep
        kac         (B, T, 3)  — per-timestep [alpha, K (diffusion coeff), state]
        model_type  (B, T, 5)  — softmax over 5 phenomenological model classes
    """
    def __init__(self, filters=(16, 32, 64, 64, 128, 128), skip_ch=512, dec_ch=512):
        super().__init__()
        D = len(filters)
        self.D = D
        self.filters = list(filters)

        enc_chs = [2] + list(filters)
        self.enc = nn.ModuleList([_conv(enc_chs[i], filters[i]) for i in range(D)])

        self.skips = nn.ModuleDict({
            f'{a}_{b}': _conv(filters[a], skip_ch)
            for a in range(D - 1) for b in range(a, D - 1)
        })

        self.ups = nn.ModuleDict()
        for b in range(1, D):
            in_ch = filters[-1] if b == D - 1 else dec_ch
            for b2 in range(b):
                s = 2 ** (b - b2)
                self.ups[f'{b}_{b2}'] = nn.Sequential(
                    nn.ConvTranspose1d(in_ch, skip_ch, s, s),
                    _conv(skip_ch, skip_ch),
                )

        self.dec_conv = nn.ModuleList([_conv(D * skip_ch, dec_ch) for _ in range(D - 1)])
        self.dec_bn   = nn.ModuleList([nn.BatchNorm1d(dec_ch) for _ in range(D - 1)])
        self.pool = nn.MaxPool1d(2, 2)
        self.head = nn.Conv1d(dec_ch, 9, 1)

    def forward(self, x):
        enc = []
        for i, block in enumerate(self.enc):
            x = block(x)
            enc.append(x)
            if i < self.D - 1:
                x = self.pool(x)

        dec_in = [[] for _ in range(self.D)]
        for a in range(self.D - 1):
            for b in range(a, self.D - 1):
                feat = enc[a]
                ps = 2 ** (b - a)
                if ps > 1:
                    feat = F.max_pool1d(feat, ps, ps)
                dec_in[b].append(self.skips[f'{a}_{b}'](feat))

        dec = [None] * self.D
        dec[-1] = enc[-1]

        for b in range(self.D - 1, -1, -1):
            if b < self.D - 1:
                h = torch.cat(dec_in[b], dim=1)
                h = self.dec_conv[b](h)
                h = F.relu(self.dec_bn[b](h))
                dec[b] = h
            if b > 0:
                for b2 in range(b - 1, -1, -1):
                    dec_in[b2].append(self.ups[f'{b}_{b2}'](dec[b]))

        out = self.head(dec[0]).transpose(1, 2)   # (B, T, 9)
        cp         = torch.sigmoid(out[..., :1])
        kac        = out[..., 1:4]
        model_type = F.softmax(out[..., 4:], dim=-1)
        return cp, kac, model_type


# ── Preprocessing ─────────────────────────────────────────────────────────────

MAX_TRAJ_LEN = 224   # model was trained on T=224 padded trajectories


def diff_trajs(trajs):
    """Convert positions to displacements; pad last step with zeros. (N,T,2)->(N,T,2)"""
    d = np.diff(trajs, axis=1)
    return np.concatenate([d, np.zeros_like(d[:, -1:])], axis=1)


def traj_to_input(traj_xy):
    """
    Convert a single trajectory array to model input tensor.

    Parameters
    ----------
    traj_xy : np.ndarray, shape (T, 2)
        Cartesian x,y positions.

    Returns
    -------
    torch.Tensor, shape (1, 2, T)
        Ready to pass to UNet3P.forward().
    """
    disp = diff_trajs(traj_xy[np.newaxis])          # (1, T, 2)
    return torch.tensor(disp, dtype=torch.float32).permute(0, 2, 1)  # (1, 2, T)


# ── Postprocessing ────────────────────────────────────────────────────────────

MIN_PEAK_HEIGHT = 0.25


def find_changepoints(cp_probs, min_height=MIN_PEAK_HEIGHT, distance=2):
    """
    Detect change-point locations from per-step sigmoid probabilities.

    Parameters
    ----------
    cp_probs : np.ndarray, shape (T,)

    Returns
    -------
    np.ndarray of int — timestep indices of detected change points
    """
    peaks = signal.find_peaks(cp_probs, height=min_height, distance=distance)[0]
    peaks = peaks[(peaks > 2) & (peaks < len(cp_probs) - 2)]
    return peaks


# ── Model loading ─────────────────────────────────────────────────────────────

def load_model(model_path, device=dvc):
    model = UNet3P().to(device)
    state = torch.load(model_path, map_location=device)
    weights = state['model'] if (isinstance(state, dict) and 'model' in state) else state
    model.load_state_dict(weights)
    model.eval()
    return model


# ── Inference ─────────────────────────────────────────────────────────────────

@torch.no_grad()
def predict_trajectory(model, traj_xy, device=dvc, use_tta=True):
    """
    Run the model on one trajectory and return per-step predictions.

    Parameters
    ----------
    model   : UNet3P (loaded and in eval mode)
    traj_xy : np.ndarray, shape (T, 2)  — x,y positions
    use_tta : bool — average over 8 symmetry views (4 rotations × 2 x-flips)

    Returns
    -------
    dict with keys:
        alpha_per_step  : np.ndarray (T,)   — anomalous exponent α per timestep
        D_per_step      : np.ndarray (T,)   — diffusion coefficient K per timestep
        cp_prob         : np.ndarray (T,)   — change-point probability per timestep
        changepoints    : np.ndarray (int)  — timestep indices of detected CPs
        model_type_prob : np.ndarray (T, 5) — softmax probs for each model class
    """
    X = traj_to_input(traj_xy).to(device)   # (1, 2, T)

    if use_tta:
        cp_sum  = torch.zeros(1, X.shape[2], 1, device=device)
        kac_sum = torch.zeros(1, X.shape[2], 3, device=device)
        mod_sum = torch.zeros(1, X.shape[2], 5, device=device)
        for k in range(4):
            c, s = float(np.cos(k * np.pi / 2)), float(np.sin(k * np.pi / 2))
            for sx in (1.0, -1.0):
                Xr = torch.stack(
                    [(X[:, 0] * c - X[:, 1] * s) * sx,
                      X[:, 0] * s + X[:, 1] * c], dim=1)
                cp, kac, mod = model(Xr)
                cp_sum  += cp
                kac_sum += kac
                mod_sum += mod
        cp_np  = (cp_sum  / 8).squeeze(0).cpu().numpy()   # (T, 1)
        kac_np = (kac_sum / 8).squeeze(0).cpu().numpy()   # (T, 3)
        mod_np = (mod_sum / 8).squeeze(0).cpu().numpy()   # (T, 5)
    else:
        cp, kac, mod = model(X)
        cp_np  = cp.squeeze(0).cpu().numpy()
        kac_np = kac.squeeze(0).cpu().numpy()
        mod_np = mod.squeeze(0).cpu().numpy()

    alpha = np.clip(kac_np[:, 0], 0.0, 1.999)
    D     = np.clip(kac_np[:, 1], 1e-12, 1e6)
    cp_prob = cp_np[:, 0]

    return {
        'alpha_per_step':  alpha,
        'D_per_step':      D,
        'cp_prob':         cp_prob,
        'changepoints':    find_changepoints(cp_prob),
        'model_type_prob': mod_np,
    }


# ── Example usage ─────────────────────────────────────────────────────────────
#
# Model class labels for model_type_prob:
#   0: single_state
#   1: multi_state
#   2: dimerization
#   3: confinement
#   4: immobile_traps

if __name__ == '__main__':
    import sys, os
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', '..', 'ucl_step', 'software'))
    from andi_datasets.datasets_challenge import challenge_phenom_dataset

    MODEL_PATH = os.path.join(
        os.path.dirname(__file__), '..', '..', '..', 'ucl_step', 'model', 'model_best.pth')

    model = load_model(MODEL_PATH, device=dvc)

    dfs_traj, _, _ = challenge_phenom_dataset(
        save_data=False,
        experiments=[2, 2, 2, 2, 2],
        return_timestep_labs=True,
        num_fovs=2,
    )

    for fov_idx, df in enumerate(dfs_traj):
        # df columns: ['traj_idx', 'frame', 'x', 'y', 'alpha', 'D', 'state']
        for traj_idx in df.traj_idx.unique():
            traj_xy = df[df.traj_idx == traj_idx][['x', 'y']].values   # (T, 2)

            preds = predict_trajectory(model, traj_xy, device=dvc, use_tta=True)

            print(f'FOV {fov_idx}  traj {traj_idx}  T={len(traj_xy)}')
            print(f'  alpha:        mean={preds["alpha_per_step"].mean():.3f}  '
                  f'shape={preds["alpha_per_step"].shape}')
            print(f'  D:            mean={preds["D_per_step"].mean():.3e}  '
                  f'shape={preds["D_per_step"].shape}')
            print(f'  cp_prob:      max={preds["cp_prob"].max():.3f}  '
                  f'shape={preds["cp_prob"].shape}')
            print(f'  changepoints: {preds["changepoints"].tolist()}')
            print(f'  model_type:   {["single_state","multi_state","dimerization","confinement","immobile_traps"][preds["model_type_prob"].mean(axis=0).argmax()]}')
            print()