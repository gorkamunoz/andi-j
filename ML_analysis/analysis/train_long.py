import torch
import torch.nn.functional as F
import math
from pathlib import Path
import torch.nn as nn
from step.models import XResAttn
from fastai.vision.all import Learner, DataLoaders, L
from torch.nn  import L1Loss
import time

dvc = 'cuda' if torch.cuda.is_available() else 'cpu'


############ MODEL DEFINITION ############

class ResBlock(nn.Module):
    def __init__(self, dim):
        super().__init__()
        self.net = nn.Sequential(nn.LayerNorm(dim), nn.Linear(dim, dim), nn.GELU())
    def forward(self, x): return x + self.net(x)

class SingleTrajModel(nn.Module):
    """Frozen XResAttn + per-layer dual-attn pooling + log(T) → residual MLP head."""
    def __init__(self, backbone, dropout=0.0, freeze_backbone=True):
        super().__init__()
        self.backbone = backbone
        if freeze_backbone:
            self.backbone.requires_grad_(False)
        self.backbone.eval()
        self.drop = nn.Dropout(dropout)
        hidden_dim = backbone.encoder.layers[0].self_attn.embed_dim
        n_layers = len(backbone.encoder.layers)
        self.attn_queries = nn.ModuleList([
            nn.Linear(hidden_dim, 2, bias=False) for _ in range(n_layers)
        ])
        feat_dim = hidden_dim * n_layers * 3 + 12  # mean+2attn; +7 physics +2 interactions +1 Q1vsQ4 +1 log_var_dr_ratio +1 log_kurtosis
        self.head = nn.Sequential(
                    nn.LayerNorm(feat_dim),
                    nn.Linear(feat_dim, 512),
                    nn.GELU(),
                    ResBlock(512),
                    nn.LayerNorm(512),
                    nn.Linear(512, 256),
                    nn.GELU(),
                    nn.Linear(256, 1)
                )

    def train(self, mode=True):
        super().train(mode)
        self.backbone.eval()
        return self

    def forward(self, x):
        T = x.shape[1]
        dtheta = x[:, :, 1]
        dr = x[:, :, 0]
        mean_cos_dth = dtheta.cos().mean(dim=1, keepdim=True)
        frac_large_turn = (dtheta.abs() > (math.pi / 2)).float().mean(dim=1, keepdim=True)
        half = T // 2
        log_dr_trend = torch.log(dr[:, half:].mean(dim=1, keepdim=True).clamp(min=1e-8) /
                                 dr[:, :half].mean(dim=1, keepdim=True).clamp(min=1e-8))
        mean_dr = dr.mean(dim=1, keepdim=True).clamp(min=1e-8)
        cv_dr = dr.std(dim=1, keepdim=True) / mean_dr
        frac_above_mean = (dr > mean_dr).float().mean(dim=1, keepdim=True)
        x = self.backbone.norm(x)
        x = self.backbone.convs(x.transpose(2, 1)).transpose(2, 1)
        src = self.backbone.pos_enc(x.transpose(1, 0))
        layer_outputs = []
        y = src
        for layer in self.backbone.encoder.layers:
            y = layer(y)
            layer_outputs.append(y.transpose(0, 1))
        layer_pools = []
        for i, z in enumerate(layer_outputs):
            scores = self.attn_queries[i](z)
            weights = F.softmax(scores, dim=1)
            layer_pools.append(z.mean(dim=1))
            layer_pools.append((z * weights[:, :, 0:1]).sum(dim=1))
            layer_pools.append((z * weights[:, :, 1:2]).sum(dim=1))
        out = torch.cat(layer_pools, dim=1)
        out = self.drop(out)
        log_t = torch.full((out.shape[0], 1), math.log(T), device=out.device)
        inv_t = torch.full((out.shape[0], 1), 1.0 / T, device=out.device)
        q1 = max(T // 4, 1)
        log_dr_Q1_Q4 = torch.log(dr[:, -q1:].mean(dim=1, keepdim=True).clamp(min=1e-8) /
                                  dr[:, :q1].mean(dim=1, keepdim=True).clamp(min=1e-8))
        interact_attm = cv_dr * frac_above_mean
        interact_sbm = log_dr_trend * (1.0 - frac_above_mean)
        # log-kurtosis: log(E[dr^4]/E[dr^2]^2); Gaussian(2D)→log(2)≈0.693; heavy-tail>>log(2)
        # LSE trick prevents fp32 overflow from large LW step sizes
        log_dr = torch.log(dr.clamp(min=1e-8))
        _v4 = 4 * log_dr; _m4 = _v4.max(dim=1, keepdim=True).values
        _v2 = 2 * log_dr; _m2 = _v2.max(dim=1, keepdim=True).values
        log_kurtosis = ((_m4 + torch.log((_v4 - _m4).exp().mean(dim=1, keepdim=True).clamp(min=1e-8))) -
                        2 * (_m2 + torch.log((_v2 - _m2).exp().mean(dim=1, keepdim=True).clamp(min=1e-8))))
        # SBM: D(t)~t^(a-1) → variance of dr also changes; subdiff a<1 → var ratio < 0
        log_var_dr_ratio = torch.log(dr[:, half:].var(dim=1, keepdim=True).clamp(min=1e-8) /
                                     dr[:, :half].var(dim=1, keepdim=True).clamp(min=1e-8))
        out = torch.cat([out, log_t, inv_t, mean_cos_dth, frac_large_turn, log_dr_trend, cv_dr, frac_above_mean, interact_attm, interact_sbm, log_dr_Q1_Q4, log_var_dr_ratio, log_kurtosis], dim=1)
        return self.head(out)

if __name__ == '__main__':
    # Load Previous model
    dim = 2
    dls = DataLoaders.from_dsets([], [])

    model_alpha = XResAttn(dim, n_class=1, stem_szs=(32,), conv_blocks=[1, 1, 1],
                block_szs=[128, 256, 512], dropout=0., pos_enc=False, n_encoder_layers=4,
                dim_ff=512, nhead_enc=8, norm=False, linear_layers=[],yrange=(-0.1,2.1)).to('cuda')

    learn_alpha  = Learner(dls, model_alpha,  loss_func=L1Loss(), model_dir='')
    model_alpha_path = Path('xresattn_alpha_2d_varlen_relpolar_yrange_m01_21_big22E2')
    learn_alpha.load(model_alpha_path)
    learn_alpha.model.eval();

    backbone_single = XResAttn(dim, n_class=1, stem_szs=(32,), conv_blocks=[1, 1, 1],
    block_szs=[128, 256, 512], dropout=0., pos_enc=False, n_encoder_layers=4,
    dim_ff=512, nhead_enc=8, norm=False, linear_layers=[])

    model_single = SingleTrajModel(backbone_single, dropout=0.1).to('cuda')
    model_single.backbone.load_state_dict(learn_alpha.model.state_dict())

####### DATA PREPARATION ############
def get_angle(from_vec, to_vec):
    x1, y1 = from_vec[...,0], from_vec[...,1]
    x2, y2 = to_vec[...,0], to_vec[...,1]
    dot = x1*x2 + y1*y2
    det = x1*y2 - y1*x2
    return np.arctan2(det, dot)

def cartesian_to_relative_polar(trajs_cart):
    disps_cart = trajs_cart[:, 1:] - trajs_cart[:, :-1]
    polar_coords = np.zeros_like(disps_cart)
    polar_coords[...,0] = np.hypot(disps_cart[..., 0], disps_cart[..., 1])
    v1,v2 = trajs_cart[:,1:-1]-trajs_cart[:,:-2], trajs_cart[:,2:]-trajs_cart[:,1:-1]
    polar_coords[:,1:,1] = get_angle(v1,v2)
    return polar_coords

from collections import defaultdict
from torch.utils.data import Dataset, DataLoader, Sampler
import numpy as np

class TrajDataset(Dataset):
    def __init__(self, X, Y, idxs, augment=False):
        self.X = [X[i].squeeze(0) for i in idxs]
        self.Y = Y[idxs]
        self.lengths = [x.shape[0] for x in self.X]
        self.augment = augment
    def __len__(self): return len(self.X)
    def __getitem__(self, i):
        x = self.X[i]
        if self.augment and torch.rand(1).item() < 0.5:
            x = x.clone()
            x[:, 1] = -x[:, 1]
        return x, self.Y[i:i+1]

class BucketBatchSampler(Sampler):
    def __init__(self, lengths, bs, shuffle=True):
        self.bs, self.shuffle = bs, shuffle
        buckets = defaultdict(list)
        for i, l in enumerate(lengths):
            buckets[l].append(i)
        self.buckets = list(buckets.values())

    def _make_batches(self):
        batches = []
        for bucket in self.buckets:
            idxs = list(bucket)
            if self.shuffle: np.random.shuffle(idxs)
            for j in range(0, len(idxs), self.bs):
                batches.append(idxs[j : j + self.bs])
        if self.shuffle: np.random.shuffle(batches)
        return batches

    def __iter__(self):
        yield from self._make_batches()

    def __len__(self):
        return sum(math.ceil(len(b) / self.bs) for b in self.buckets)

def collate_fn(batch):
    xs, ys = zip(*batch)
    return torch.stack(xs), torch.stack(ys)

from dataset import challenge_theory_dataset_no_noise

if __name__ == '__main__':
    X1, Y1, X2, Y2, X3, Y3 = challenge_theory_dataset_no_noise(N=100000, tasks=1, dimensions=2, min_T=10, max_T=200, load_dataset=True)

    inputs = X1[1]
    outputs = torch.tensor(Y1[1]).to(dvc)

    tensor_X = []
    polar_X = []
    for i in inputs:
        traj = torch.tensor(i).reshape(2, -1).T.unsqueeze(0)
        traj += torch.randn_like(traj)*0.001
        tensor_X.append(traj)
        polar_X.append(torch.tensor(cartesian_to_relative_polar(traj), dtype=torch.float).to(dvc))
    polar_X = L(polar_X)

    bs_train = 64
    val_frac = 0.2
    n = len(polar_X)
    perm = np.random.permutation(n)
    n_val = int(val_frac * n)
    val_idxs, train_idxs = perm[:n_val], perm[n_val:]

    train_ds = TrajDataset(polar_X, outputs, train_idxs, augment=True)
    val_ds   = TrajDataset(polar_X, outputs, val_idxs,   augment=False)

    train_dl = DataLoader(train_ds,
                        batch_sampler=BucketBatchSampler(train_ds.lengths, bs_train, shuffle=True),
                        collate_fn=collate_fn)
    val_dl   = DataLoader(val_ds,
                        batch_sampler=BucketBatchSampler(val_ds.lengths, bs_train, shuffle=False),
                        collate_fn=collate_fn)

    dls_single = DataLoaders(train_dl, val_dl, device=dvc)


############### TRAINING #################

from fastai.callback.core import Callback, CancelFitException


MAX_SECONDS = 45 * 60

class TimeLimitCallback(Callback):
    def __init__(self, max_seconds=MAX_SECONDS): self.max_seconds = max_seconds
    def before_fit(self): self.start = time.time()
    def after_batch(self):
        if time.time() - self.start > self.max_seconds:
            raise CancelFitException()

class LogAndEarlyStopCallback(Callback):
    order = 60  # run after Recorder (order=50) so recorder.values[-1] is populated
    """Print per-epoch train/val loss and stop on overfitting."""
    def __init__(self, patience=10, overfit_gap=0.05, overfit_patience=5):
        self.patience = patience
        self.overfit_gap = overfit_gap
        self.overfit_patience = overfit_patience
        self.best_val = float('inf')
        self.no_improve = 0
        self.overfit_streak = 0

    def after_epoch(self):
        vals = self.recorder.values[-1]
        train_loss = float(vals[0])
        val_loss   = float(vals[1]) if len(vals) > 1 else float('nan')
        gap = val_loss - train_loss
        elapsed = time.time() - t_start
        print(f"epoch {self.epoch+1:4d}  train={train_loss:.6f}  val={val_loss:.6f}  gap={gap:+.6f}  t={elapsed:.0f}s", flush=True)

        # plateau early stop
        if val_loss < self.best_val - 1e-5:
            self.best_val = val_loss
            self.no_improve = 0
        else:
            self.no_improve += 1

        # critical overfitting stop
        if gap > self.overfit_gap:
            self.overfit_streak += 1
        else:
            self.overfit_streak = 0

        if self.no_improve >= self.patience:
            print(f"EARLY STOP: val loss plateaued for {self.patience} consecutive epochs", flush=True)
            raise CancelFitException()

        if self.overfit_streak >= self.overfit_patience:
            print(f"EARLY STOP: val-train gap > {self.overfit_gap:.2f} for {self.overfit_patience} consecutive epochs", flush=True)
            raise CancelFitException()

if __name__ == '__main__':
    model_single = SingleTrajModel(backbone_single, dropout=0.1, freeze_backbone=False).to('cuda')

    def splitter(m):
        return [list(m.backbone.parameters()),
                list(m.attn_queries.parameters()) + list(m.head.parameters())]

    from fastai.callback.all import GradientClip, SaveModelCallback
    learn_single = Learner(dls_single, model_single, loss_func=L1Loss(), wd=1e-3, splitter=splitter)

    # Constant LR warm restarts (~7 cycles in 45 min)
    CYCLE_EPOCHS = 15
    SECS_PER_EPOCH_EST = 26

    t_start = time.time()
    cycle = 0
    global_best_val = float('inf')

    while True:
        remaining = MAX_SECONDS - (time.time() - t_start)
        if remaining < CYCLE_EPOCHS * SECS_PER_EPOCH_EST + 120:
            break
        cycle += 1
        print(f"\n--- Cycle {cycle}  t={time.time()-t_start:.0f}s ---", flush=True)
        cb_save = SaveModelCallback(monitor='valid_loss', fname='best_long', with_opt=False)
        with learn_single.no_bar(), learn_single.no_logging():
            learn_single.fit_one_cycle(CYCLE_EPOCHS, lr_max=[1e-4, 1e-2],
                                    cbs=[GradientClip(3.0), LogAndEarlyStopCallback(), cb_save])
        # track global best
        try:
            cur_best = cb_save.best
            if cur_best < global_best_val:
                global_best_val = cur_best
                learn_single.save('global_best_long')
                print(f"  -> New global best val: {global_best_val:.6f}", flush=True)
        except Exception:
            pass

    t_end = time.time()
    print(f"\nTotal cycles: {cycle}  global best val: {global_best_val:.6f}", flush=True)

    # restore global best checkpoint
    try:
        learn_single.load('global_best_long')
    except Exception:
        try:
            learn_single.load('best_long')
        except Exception:
            pass


    ####### SAVE BEST MODEL #################
    torch.save(learn_single.model.state_dict(), 'best_model_single_traj.pth')
    print("Best model saved to best_model_single_traj.pth", flush=True)

    ####### EVALUATION #################
    from prepare import evaluate
    evaluate(learn_single, val_dl, t_start, t_end)
