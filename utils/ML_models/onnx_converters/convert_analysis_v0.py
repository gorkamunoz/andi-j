"""
Convert the  combined single-trajectory model to ONNX for ONNX Runtime in Fiji.


Output:
    model_analysis_v0.onnx   – one model, any trajectory length

Model contract (single input, single output)
---------------------------------------------
    input  : float32  (batch, T-1, 2)   relative-polar features
    output : float32  (batch, 11)
        cols 0-4  : per-type alpha regressions
        cols 5-9  : classification logits (one per diffusion model)
        col  10   : soft-routed alpha  ← use this as THE alpha prediction
    diffusion-model class = argmax(logits) = argmax(output[5:10])

Dynamic-length note
-------------------
V2CombinedModel.forward derives several quantities from T = x.shape[1]
(log_t, inv_t, and half/quarter-window features).  The standard ONNX tracer
would freeze those at the dummy length.  The export wrapper below reimplements
exactly those pieces with shape-derived, mask-based reductions so the exported
graph is correct for any sequence length.  Everything else is copied verbatim
from single_traj_prediction_v2.py, so the numerics are identical.
"""

import math
import torch
import torch.nn as nn
import torch.nn.functional as F
from pathlib import Path
from step.models import XResAttn

SCRIPT_DIR = Path(__file__).parent
MODEL_PATH = SCRIPT_DIR / '../model_weights/analysis_v0.pth'
OUT_PATH   = SCRIPT_DIR / '../model_weights/model_analysis_v0.onnx'
OPSET      = 17
DUMMY_LEN  = 50


# ---------------------------------------------------------------------------
# Model definition (verbatim from single_traj_prediction_v2.py)
# ---------------------------------------------------------------------------

class ResBlock(nn.Module):
    def __init__(self, dim):
        super().__init__()
        self.net = nn.Sequential(nn.LayerNorm(dim), nn.Linear(dim, dim), nn.GELU())
    def forward(self, x): return x + self.net(x)


class V2CombinedModel(nn.Module):
    def __init__(self, backbone, n_models=5, dropout=0.1):
        super().__init__()
        self.backbone = backbone
        self.drop = nn.Dropout(dropout)
        hidden_dim = backbone.encoder.layers[0].self_attn.embed_dim
        n_layers   = len(backbone.encoder.layers)
        self.attn_queries = nn.ModuleList([
            nn.Linear(hidden_dim, 2, bias=False) for _ in range(n_layers)
        ])
        feat_dim = hidden_dim * n_layers * 3 + 12
        self.shared_encoder = nn.Sequential(
            nn.LayerNorm(feat_dim), nn.Linear(feat_dim, 512), nn.GELU(), ResBlock(512)
        )
        self.cls_head = nn.Sequential(
            nn.LayerNorm(512), nn.Dropout(dropout),
            nn.Linear(512, 256), nn.GELU(), nn.Linear(256, n_models)
        )
        self.regressors = nn.ModuleList([
            nn.Sequential(nn.LayerNorm(512), nn.Linear(512, 128), nn.GELU(), nn.Linear(128, 1))
            for _ in range(n_models)
        ])

    # The original forward is only needed for loading/eval; the export wrapper
    # below supplies a dynamic-length equivalent.
    def forward(self, x):  # pragma: no cover (not used for export)
        raise NotImplementedError


# ---------------------------------------------------------------------------
# Dynamic-length export wrapper
# ---------------------------------------------------------------------------

class _ExportWrapper(nn.Module):
    def __init__(self, m: V2CombinedModel):
        super().__init__()
        self.backbone       = m.backbone
        self.drop           = m.drop          # eval() => no-op, kept for parity
        self.attn_queries   = m.attn_queries
        self.shared_encoder = m.shared_encoder
        self.cls_head       = m.cls_head
        self.regressors     = m.regressors

    def forward(self, x):
        # ── dr normalisation (no in-place, export-friendly) ──────────────
        dr_max = x[:, :, 0].max(dim=1, keepdim=True).values.clamp(min=1e-8)
        dr     = x[:, :, 0] / dr_max
        dtheta = x[:, :, 1]
        x      = torch.stack([dr, dtheta], dim=2)

        # ── dynamic-length helpers (replace Python-int T usage) ──────────
        ones = torch.ones_like(dr)                       # (B, L)
        Lf   = ones.sum(dim=1, keepdim=True)             # (B, 1) = L
        pos  = torch.cumsum(ones, dim=1) - 1.0           # (B, L) = 0..L-1

        half = torch.floor(Lf / 2.0)
        qf   = torch.clamp(torch.floor(Lf / 4.0), min=1.0)
        m_first  = (pos <  half).float()
        m_second = (pos >= half).float()
        m_q1     = (pos <  qf).float()
        m_q4     = (pos >= (Lf - qf)).float()

        def msum(mask):  return (dr * mask).sum(dim=1, keepdim=True)
        def mmean(mask): return msum(mask) / mask.sum(dim=1, keepdim=True).clamp(min=1.0)
        def mvar(mask):
            n = mask.sum(dim=1, keepdim=True)
            mu = msum(mask) / n.clamp(min=1.0)
            return (((dr - mu) ** 2) * mask).sum(dim=1, keepdim=True) / (n - 1.0).clamp(min=1.0)

        # ── handcrafted features (identical formulas, masks for slices) ──
        mean_cos_dth    = dtheta.cos().mean(dim=1, keepdim=True)
        frac_large_turn = (dtheta.abs() > (math.pi / 2)).float().mean(dim=1, keepdim=True)
        log_dr_trend    = torch.log(mmean(m_second).clamp(min=1e-8) /
                                    mmean(m_first).clamp(min=1e-8))
        mean_dr         = dr.mean(dim=1, keepdim=True).clamp(min=1e-8)
        cv_dr           = dr.std(dim=1, keepdim=True) / mean_dr
        frac_above_mean = (dr > mean_dr).float().mean(dim=1, keepdim=True)
        log_dr_Q1_Q4    = torch.log(mmean(m_q4).clamp(min=1e-8) /
                                    mmean(m_q1).clamp(min=1e-8))
        interact_attm   = cv_dr * frac_above_mean
        interact_sbm    = log_dr_trend * (1.0 - frac_above_mean)
        log_var_dr_ratio = torch.log(mvar(m_second).clamp(min=1e-8) /
                                     mvar(m_first).clamp(min=1e-8))
        log_dr_f = torch.log(dr.clamp(min=1e-8))
        _v4 = 4 * log_dr_f; _m4 = _v4.max(dim=1, keepdim=True).values
        _v2 = 2 * log_dr_f; _m2 = _v2.max(dim=1, keepdim=True).values
        log_kurtosis = ((_m4 + torch.log((_v4 - _m4).exp().mean(dim=1, keepdim=True).clamp(min=1e-8))) -
                        2 * (_m2 + torch.log((_v2 - _m2).exp().mean(dim=1, keepdim=True).clamp(min=1e-8))))

        # ── frozen backbone + per-layer dual-attention pooling ───────────
        xb  = self.backbone.norm(x)
        xb  = self.backbone.convs(xb.transpose(2, 1)).transpose(2, 1)
        src = self.backbone.pos_enc(xb.transpose(1, 0))
        y   = src
        layer_outputs = []
        for layer in self.backbone.encoder.layers:
            y = layer(y)
            layer_outputs.append(y.transpose(0, 1))

        layer_pools = []
        for i, z in enumerate(layer_outputs):
            scores  = self.attn_queries[i](z)
            weights = F.softmax(scores, dim=1)
            layer_pools.append(z.mean(dim=1))
            layer_pools.append((z * weights[:, :, 0:1]).sum(dim=1))
            layer_pools.append((z * weights[:, :, 1:2]).sum(dim=1))
        out = torch.cat(layer_pools, dim=1)

        log_t = torch.log(Lf)     # (B,1) == log(L)
        inv_t = 1.0 / Lf          # (B,1)
        features = torch.cat([out, log_t, inv_t, mean_cos_dth, frac_large_turn,
                               log_dr_trend, cv_dr, frac_above_mean, interact_attm,
                               interact_sbm, log_dr_Q1_Q4, log_var_dr_ratio, log_kurtosis], dim=1)

        shared     = self.shared_encoder(features)
        logits     = self.cls_head(shared)
        probs      = F.softmax(logits, dim=-1)
        reg_out    = torch.cat([reg(shared) for reg in self.regressors], dim=1)
        soft_alpha = (probs * reg_out).sum(dim=1, keepdim=True)
        return torch.cat([reg_out, logits, soft_alpha], dim=1)


# ---------------------------------------------------------------------------
# Build, load, export
# ---------------------------------------------------------------------------

if __name__ == '__main__':
    backbone = XResAttn(
        2, n_class=1, stem_szs=(32,), conv_blocks=[1, 1, 1],
        block_szs=[128, 256, 512], dropout=0., pos_enc=False,
        n_encoder_layers=4, dim_ff=512, nhead_enc=8, norm=False, linear_layers=[])
    model = V2CombinedModel(backbone, n_models=5, dropout=0.1)
    model.load_state_dict(torch.load(str(MODEL_PATH), map_location='cpu'))
    model.eval()
    print(f'Loaded {MODEL_PATH}')

    wrapper = _ExportWrapper(model).eval()
    dummy = torch.zeros(1, DUMMY_LEN, 2)

    with torch.no_grad():
        torch.onnx.export(
            wrapper, dummy, str(OUT_PATH),
            input_names=['input'],
            output_names=['output'],
            dynamic_axes={'input': {0: 'batch', 1: 'seq_len'},
                          'output': {0: 'batch'}},
            opset_version=OPSET,
            do_constant_folding=True,
        )
    kb = OUT_PATH.stat().st_size // 1024
    print(f'  -> {OUT_PATH.name}  ({kb} kB)')
    print()
    print('Next steps:')
    print('  1. cp model_analysis_v0.onnx ../../src/main/resources/models/')
    print('  2. mvn clean package   (from the project root)')
    print('  3. Copy both JARs to Fiji.app/jars/')
