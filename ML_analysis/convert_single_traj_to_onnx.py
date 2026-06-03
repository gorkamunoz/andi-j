"""
Convert the SingleTrajModel PyTorch model to ONNX for use with ONNX Runtime in Fiji.

Run from ML_analysis/:
    python convert_single_traj_to_onnx.py

Output:
    single_traj_alpha.onnx   – predicts one α per trajectory, any length

After conversion:
    1. Copy single_traj_alpha.onnx to src/main/resources/models/
    2. mvn clean package
    3. Copy both JARs to Fiji.app/jars/

Design note
-----------
SingleTrajModel.forward() computes  log_t = math.log(x.shape[1])  using a
Python-level shape lookup.  PyTorch's ONNX tracer concretises Python integers,
so this value would be baked in as a constant for the dummy sequence length
chosen at export time.

The wrapper below pulls log_t out as an explicit second model input so the
sequence length remains fully dynamic.  The Java side computes
    log_t = (float) Math.log(seqLen)
and passes it as a (1,1) float tensor alongside the feature tensor.
"""

import math
import torch
import torch.nn as nn
import torch.nn.functional as F
from pathlib import Path
from step.models import XResAttn

SCRIPT_DIR = Path(__file__).parent
OPSET      = 13
DUMMY_LEN  = 50   # arbitrary; seq_len is dynamic in the exported model


# ---------------------------------------------------------------------------
# Model definitions (copied verbatim from single_traj_prediction.py)
# ---------------------------------------------------------------------------

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
        n_layers   = len(backbone.encoder.layers)
        self.attn_queries = nn.ModuleList([
            nn.Linear(hidden_dim, 2, bias=False) for _ in range(n_layers)
        ])
        feat_dim = hidden_dim * n_layers * 3 + 1
        self.head = nn.Sequential(
            nn.LayerNorm(feat_dim),
            nn.Linear(feat_dim, 512), nn.GELU(),
            ResBlock(512),
            nn.LayerNorm(512),
            nn.Linear(512, 256), nn.GELU(),
            nn.Linear(256, 1),
        )

    def train(self, mode=True):
        super().train(mode)
        self.backbone.eval()
        return self

    def forward(self, x):
        T = x.shape[1]
        with torch.no_grad():
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
            scores  = self.attn_queries[i](z)
            weights = F.softmax(scores, dim=1)
            layer_pools.append(z.mean(dim=1))
            layer_pools.append((z * weights[:, :, 0:1]).sum(dim=1))
            layer_pools.append((z * weights[:, :, 1:2]).sum(dim=1))
        out   = torch.cat(layer_pools, dim=1)
        out   = self.drop(out)
        log_t = torch.full((out.shape[0], 1), math.log(T), device=out.device)
        out   = torch.cat([out, log_t], dim=1)
        return self.head(out)


# ---------------------------------------------------------------------------
# ONNX export wrapper
# ---------------------------------------------------------------------------

class _OnnxWrapper(nn.Module):
    """
    Two-input wrapper for ONNX export.

    Inputs
    ------
    x     : float32  (batch, seq_len, 2)   relative-polar features
    log_t : float32  (batch, 1)            precomputed log(seq_len)

    Output
    ------
              float32  (batch, 1)           predicted α
    """
    def __init__(self, model: SingleTrajModel):
        super().__init__()
        self.backbone      = model.backbone
        self.drop          = model.drop
        self.attn_queries  = model.attn_queries
        self.head          = model.head

    def forward(self, x, log_t):
        # Backbone feature extraction (frozen – identical to SingleTrajModel.forward)
        with torch.no_grad():
            h = self.backbone.norm(x)
            h = self.backbone.convs(h.transpose(2, 1)).transpose(2, 1)   # (B, T', C)
            src = self.backbone.pos_enc(h.transpose(1, 0))                # (T', B, C)
            layer_outputs = []
            y = src
            for layer in self.backbone.encoder.layers:
                y = layer(y)
                layer_outputs.append(y.transpose(0, 1))                   # (B, T', C)

        # Dual-attention pooling
        layer_pools = []
        for i, z in enumerate(layer_outputs):
            scores  = self.attn_queries[i](z)           # (B, T', 2)
            weights = F.softmax(scores, dim=1)          # (B, T', 2)
            layer_pools.append(z.mean(dim=1))                              # mean pool
            layer_pools.append((z * weights[:, :, 0:1]).sum(dim=1))       # attn head 1
            layer_pools.append((z * weights[:, :, 1:2]).sum(dim=1))       # attn head 2

        out = torch.cat(layer_pools, dim=1)
        out = self.drop(out)
        out = torch.cat([out, log_t], dim=1)   # log_t supplied as external input
        return self.head(out)


# ---------------------------------------------------------------------------
# Build, load, export
# ---------------------------------------------------------------------------

def _build_model(device='cpu'):
    backbone = XResAttn(
        2, n_class=1,
        stem_szs=(32,), conv_blocks=[1, 1, 1],
        block_szs=[128, 256, 512],
        dropout=0., pos_enc=False,
        n_encoder_layers=4, dim_ff=512, nhead_enc=8,
        norm=False, linear_layers=[],
    ).to(device)
    model = SingleTrajModel(backbone).to(device)
    ckpt  = SCRIPT_DIR / 'kistep_adapted_single_exp8.pth'
    model.load_state_dict(torch.load(str(ckpt), map_location=device))
    model.eval()
    return model


if __name__ == '__main__':
    print('Loading model on CPU ...')
    model   = _build_model('cpu')
    wrapper = _OnnxWrapper(model).eval()

    dummy_x     = torch.zeros(1, DUMMY_LEN, 2)
    dummy_log_t = torch.tensor([[math.log(DUMMY_LEN)]])

    out_path = SCRIPT_DIR / 'single_traj_alpha.onnx'
    print(f'Exporting to {out_path.name} (opset {OPSET}) ...')

    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (dummy_x, dummy_log_t),
            str(out_path),
            input_names=['input', 'log_t'],
            output_names=['output'],
            dynamic_axes={
                'input': {0: 'batch', 1: 'seq_len'},
                'log_t': {0: 'batch'},
            },
            opset_version=OPSET,
            do_constant_folding=True,
        )

    kb = out_path.stat().st_size // 1024
    print(f'  -> {out_path.name}  ({kb} kB)')
    print()
    print('Next steps:')
    print('  1. cp single_traj_alpha.onnx ../src/main/resources/models/')
    print('  2. mvn clean package')
    print('  3. Copy both JARs to Fiji.app/jars/')
