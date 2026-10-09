"""Architecture of the v4 trajectory-segmentation network (1-D U-Net 3+).

The network is the one of ``model_seg_v2.py``; the v4 weights (``seg_v4.pth``) differ
from v2 only in the diffusive-state readout (``state_mlp`` and ``state_logits``). See
``log_seg.md``.

Input ``(B, 2, T)`` displacements, ``T`` divisible by 32. Outputs, per frame:
``cp`` (B, T, 1) change-point probability, ``k_alpha_cls`` (B, T, 8) and
``model_type`` (B, T, 5). The 8 channels of ``k_alpha_cls`` are: 0 alpha with the
temporal branch, 1 natural log(D), 2 diffusive-state regression, 3:7 diffusive-state
logits, 7 alpha without the temporal branch. ``model_type`` is not used by AnDi-J.

Trunk: U-Net 3+ with 6 levels and full-scale skip connections (after U-AnD-ME,
Asghar et al. 2025). Heads on the top decoder level:
  * alpha: linear readout + residual MLP + log(D)-conditioned MLP, plus a temporal
    branch of three dilated convolutions;
  * log(D): MLP, clamped to [-30, 11];
  * diffusive state: regression ``s`` turned into logits ``-(s - c)^2 / 0.25`` for
    the classes c = 0..3, plus a learned linear correction.
"""

import torch
import torch.nn as nn
import torch.nn.functional as F


def _conv(in_ch, out_ch):
    return nn.Sequential(nn.Conv1d(in_ch, out_ch, 3, padding=1), nn.ReLU())


class UNet3P(nn.Module):
    """1-D U-Net 3+; see the module docstring for inputs and outputs."""

    def __init__(self, filters=(16, 32, 64, 64, 128, 128), skip_ch=512, dec_ch=512):
        super().__init__()
        D = len(filters)
        self.D = D
        self.filters = list(filters)

        # Encoder: one conv block per level
        enc_chs = [2] + list(filters)
        self.enc = nn.ModuleList([_conv(enc_chs[i], filters[i]) for i in range(D)])

        # Skip blocks: encoder level a -> decoder level b  (a <= b < D-1)
        self.skips = nn.ModuleDict({
            f'{a}_{b}': _conv(filters[a], skip_ch)
            for a in range(D - 1) for b in range(a, D - 1)
        })

        # Upsample blocks: decoder level b -> decoder level b2  (b2 < b)
        self.ups = nn.ModuleDict()
        for b in range(1, D):
            in_ch = filters[-1] if b == D - 1 else dec_ch
            for b2 in range(b):
                s = 2 ** (b - b2)
                self.ups[f'{b}_{b2}'] = nn.Sequential(
                    nn.ConvTranspose1d(in_ch, skip_ch, s, s),
                    _conv(skip_ch, skip_ch),
                )

        # Decoder blocks: D inputs x skip_ch channels -> dec_ch
        self.dec_conv = nn.ModuleList([_conv(D * skip_ch, dec_ch) for _ in range(D - 1)])
        self.dec_bn = nn.ModuleList([nn.BatchNorm1d(dec_ch) for _ in range(D - 1)])

        self.pool = nn.MaxPool1d(2, 2)
        self.head = nn.Conv1d(dec_ch, 9, 1)          # cp (0), state regression (3), model type (4:9)
        self.alpha_head = nn.Conv1d(dec_ch, 1, 1)
        self.alpha_mlp = nn.Sequential(
            nn.Conv1d(dec_ch, 128, 1), nn.ReLU(),
            nn.Conv1d(128, 1, 1))
        self.state_mlp = nn.Sequential(
            nn.Conv1d(dec_ch, 128, 1), nn.ReLU(),
            nn.Conv1d(128, 1, 1))
        self.state_logits = nn.Conv1d(dec_ch, 4, 1)
        self.state_T = 0.25
        # Temporal alpha branch: kernel 5 at dilations 1/2/4 (receptive field ~29 frames).
        self.alpha_temporal = nn.Sequential(
            nn.Conv1d(dec_ch, 64, 5, padding=2, dilation=1), nn.ReLU(),
            nn.Conv1d(64, 64, 5, padding=4, dilation=2), nn.ReLU(),
            nn.Conv1d(64, 64, 5, padding=8, dilation=4), nn.ReLU(),
            nn.Conv1d(64, 1, 1))
        self.alpha_dcond = nn.Sequential(
            nn.Conv1d(dec_ch + 1, 128, 1), nn.ReLU(),
            nn.Conv1d(128, 1, 1))
        self.k_head = nn.Sequential(
            nn.Conv1d(dec_ch, 128, 1), nn.ReLU(),
            nn.Conv1d(128, 1, 1))

    def forward(self, x):
        # Encode
        enc = []
        for i, block in enumerate(self.enc):
            x = block(x)
            enc.append(x)
            if i < self.D - 1:
                x = self.pool(x)

        # Skip connections for each decoder level
        dec_in = [[] for _ in range(self.D)]
        for a in range(self.D - 1):
            for b in range(a, self.D - 1):
                feat = enc[a]
                ps = 2 ** (b - a)
                if ps > 1:
                    feat = F.max_pool1d(feat, ps, ps)
                dec_in[b].append(self.skips[f'{a}_{b}'](feat))

        # Decode (bridge = enc[-1])
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

        out = self.head(dec[0]).transpose(1, 2)                                 # (B, T, 9)
        cp = torch.sigmoid(out[..., :1])
        logD = self.k_head(dec[0]).transpose(1, 2).clamp(-30.0, 11.0)          # natural log
        _dfeat = torch.cat([dec[0], logD.transpose(1, 2).detach()], dim=1)
        alpha_base = (self.alpha_head(dec[0]) + self.alpha_mlp(dec[0])
                      + self.alpha_dcond(_dfeat)).transpose(1, 2)
        alpha = alpha_base + self.alpha_temporal(dec[0]).transpose(1, 2)
        state = out[..., 3:4] + self.state_mlp(dec[0]).transpose(1, 2)
        _levels = torch.arange(4.0, device=state.device).view(1, 1, 4)
        s_logits = (-((state - _levels) ** 2) / self.state_T
                    + self.state_logits(dec[0]).transpose(1, 2))               # (B, T, 4)
        k_alpha_cls = torch.cat([alpha, logD, state, s_logits, alpha_base], dim=-1)
        model_type = F.softmax(out[..., 4:], dim=-1)
        return cp, k_alpha_cls, model_type


def load_model(path, device='cpu'):
    """A UNet3P with the weights stored under ``"model"`` in ``path``, in eval mode."""
    model = UNet3P()
    model.load_state_dict(torch.load(str(path), map_location=device)['model'])
    return model.to(device).eval()
