"""Architecture of the v1 single-trajectory analysis networks (FCI-type).

Two networks share this architecture: one predicts the anomalous exponent as a
distribution over the 40 values of ``ALPHA_GRID``, the other the 5 diffusion-model
classes. Both take normalised displacements shaped ``(B, MAX_T - 1, 2)``.

Parallel dilated causal convolution blocks, concatenated, max-pooled over time, and
three dense layers (Granik et al. 2019, after Bai et al. 2018). The layer order and the
batch-norm constants reproduce the Keras originals the weights descend from.
"""

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

ALPHA_GRID = np.round(np.arange(0.05, 2.001, 0.05), 2)   # the 40 exponents of AnDi-1
MODELS = ['attm', 'ctrw', 'fbm', 'lw', 'sbm']
MAX_T = 1001                                           # padded trajectory width (points)

BLOCK_KERNELS = (2, 3, 4, 10, 20)   # receptive fields 16, 31, 46, 136, 286
DILATIONS = (1, 2, 4, 8)
N_FILTERS = 64
BN_EPS, BN_MOMENTUM = 1e-3, 0.01


class CausalConv1d(nn.Module):
    """Conv1d with Keras' padding='causal': output step t sees only inputs <= t."""

    def __init__(self, c_in, c_out, k, dilation):
        super().__init__()
        self.pad = (k - 1) * dilation
        self.conv = nn.Conv1d(c_in, c_out, k, dilation=dilation)

    def forward(self, x):
        return self.conv(F.pad(x, (self.pad, 0)))


class ConvBlock(nn.Module):
    """A dilation ladder (conv -> relu -> BN, Keras order) plus a 1x1 bypass."""

    def __init__(self, k, c_in=2, filters=N_FILTERS):
        super().__init__()
        chans = [c_in] + [filters] * (len(DILATIONS) - 1)
        self.convs = nn.ModuleList([CausalConv1d(c, filters, k, d)
                                    for c, d in zip(chans, DILATIONS)])
        self.bns = nn.ModuleList([nn.BatchNorm1d(filters, eps=BN_EPS, momentum=BN_MOMENTUM)
                                  for _ in DILATIONS])
        self.bypass = nn.Conv1d(c_in, filters, 1)

    def forward(self, x):
        h = x
        for conv, bn in zip(self.convs, self.bns):
            h = bn(F.relu(conv(h)))
        return F.relu(self.bypass(x)) + h


class ConvTrunk(nn.Module):
    """The parallel conv blocks, concatenated along the channel axis."""

    def __init__(self):
        super().__init__()
        self.blocks = nn.ModuleList([ConvBlock(k) for k in BLOCK_KERNELS])

    def forward(self, x):
        return torch.cat([block(x) for block in self.blocks], dim=1)


class FCINet2D(nn.Module):
    """Two trunks, fed (dx, dy) and (dy, dx), combined by an elementwise max.

    Input ``(B, T, 2)`` normalised displacements; output raw scores ``(B, n_out)``.
    """

    def __init__(self, n_out):
        super().__init__()
        self.trunk, self.trunk_swapped = ConvTrunk(), ConvTrunk()
        self.dense = nn.Linear(N_FILTERS * len(BLOCK_KERNELS), 512)
        self.dense_1 = nn.Linear(512, 256)
        self.dense_2 = nn.Linear(256, n_out)

    def forward(self, x):
        x = x.transpose(1, 2)                             # (B, T, 2) -> (B, 2, T)
        c1 = self.trunk(x).amax(dim=2)                    # global max pool over time
        c2 = self.trunk_swapped(x.flip(1)).amax(dim=2)    # flip(1) swaps dx and dy
        h = F.relu(self.dense(torch.maximum(c1, c2)))
        return self.dense_2(F.relu(self.dense_1(h)))


def load_model(path, n_out, device='cpu'):
    """An FCINet2D with ``n_out`` outputs, weights from ``path``, in eval mode."""
    net = FCINet2D(n_out)
    net.load_state_dict(torch.load(str(path), map_location=device))
    return net.to(device).eval()
