"""Inference bridge for the v1 U-Net 3+ trajectory-segmentation model."""

from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

MAX_POOL_FACTOR = 32


def _conv(in_channels, out_channels):
    return nn.Sequential(nn.Conv1d(in_channels, out_channels, 3, padding=1), nn.ReLU())


class UNet3P(nn.Module):
    """v1 model. Input is a displacement tensor shaped ``(B, 2, T)``."""

    def __init__(self, filters=(16, 32, 64, 64, 128, 128), skip_ch=512, dec_ch=512):
        super().__init__()
        depth = len(filters)
        self.depth = depth

        encoder_channels = [2] + list(filters)
        self.enc = nn.ModuleList([_conv(encoder_channels[i], filters[i]) for i in range(depth)])
        self.skips = nn.ModuleDict({
            f"{source}_{target}": _conv(filters[source], skip_ch)
            for source in range(depth - 1) for target in range(source, depth - 1)
        })
        self.ups = nn.ModuleDict()
        for source in range(1, depth):
            input_channels = filters[-1] if source == depth - 1 else dec_ch
            for target in range(source):
                scale = 2 ** (source - target)
                self.ups[f"{source}_{target}"] = nn.Sequential(
                    nn.ConvTranspose1d(input_channels, skip_ch, scale, scale),
                    _conv(skip_ch, skip_ch),
                )

        self.dec_conv = nn.ModuleList([_conv(depth * skip_ch, dec_ch) for _ in range(depth - 1)])
        self.dec_bn = nn.ModuleList([nn.BatchNorm1d(dec_ch) for _ in range(depth - 1)])
        self.pool = nn.MaxPool1d(2, 2)
        self.head = nn.Conv1d(dec_ch, 9, 1)
        self.k_head = nn.Sequential(nn.Conv1d(dec_ch, 128, 1), nn.ReLU(), nn.Conv1d(128, 1, 1))

    def forward(self, displacements):
        encoder = []
        features = displacements
        for index, block in enumerate(self.enc):
            features = block(features)
            encoder.append(features)
            if index < self.depth - 1:
                features = self.pool(features)

        decoder_inputs = [[] for _ in range(self.depth)]
        for source in range(self.depth - 1):
            for target in range(source, self.depth - 1):
                features = encoder[source]
                pool_size = 2 ** (target - source)
                if pool_size > 1:
                    features = F.max_pool1d(features, pool_size, pool_size)
                decoder_inputs[target].append(self.skips[f"{source}_{target}"](features))

        decoder = [None] * self.depth
        decoder[-1] = encoder[-1]
        for source in range(self.depth - 1, -1, -1):
            if source < self.depth - 1:
                features = torch.cat(decoder_inputs[source], dim=1)
                features = self.dec_conv[source](features)
                decoder[source] = F.relu(self.dec_bn[source](features))
            if source > 0:
                for target in range(source - 1, -1, -1):
                    decoder_inputs[target].append(self.ups[f"{source}_{target}"](decoder[source]))

        output = self.head(decoder[0]).transpose(1, 2)
        cp = torch.sigmoid(output[..., :1])
        log_d = self.k_head(decoder[0]).transpose(1, 2).clamp(-30.0, 11.0)
        alpha_log_d_state = torch.cat([output[..., 1:2], log_d, output[..., 3:4]], dim=-1)
        model_class = F.softmax(output[..., 4:], dim=-1)
        return cp, alpha_log_d_state, model_class


def load_model(model_path=Path(__file__).with_name("model_best.pth"), device="cpu"):
    """Load the v1 checkpoint and return an evaluation-mode ``UNet3P``."""
    model = UNet3P().to(device)
    checkpoint = torch.load(model_path, map_location=device)
    weights = checkpoint["model"] if isinstance(checkpoint, dict) and "model" in checkpoint else checkpoint
    model.load_state_dict(weights)
    return model.eval()


def positions_to_displacements(positions):
    """Convert raw positions shaped ``(B, T, 2)`` to padded displacements."""
    steps = positions[:, 1:, :] - positions[:, :-1, :]
    return torch.cat([steps, torch.zeros_like(positions[:, :1, :])], dim=1)


@torch.no_grad()
def predict_trajectory(model, trajectory_xy, device="cpu"):
    """Predict per-frame ``alpha``, natural ``log_d``, CP probability, and state."""
    positions = torch.as_tensor(trajectory_xy, dtype=torch.float32, device=device).unsqueeze(0)
    original_length = positions.shape[1]
    padded_length = ((original_length + MAX_POOL_FACTOR - 1) // MAX_POOL_FACTOR) * MAX_POOL_FACTOR
    positions = F.pad(positions, (0, 0, 0, padded_length - original_length))
    displacements = positions_to_displacements(positions).transpose(1, 2)
    cp, alpha_log_d_state, _ = model(displacements)
    return {
        "alpha": alpha_log_d_state[0, :original_length, 0].cpu().numpy(),
        "log_d": alpha_log_d_state[0, :original_length, 1].cpu().numpy(),
        "cp_probability": cp[0, :original_length, 0].cpu().numpy(),
        "state": alpha_log_d_state[0, :original_length, 2].cpu().numpy(),
    }


if __name__ == "__main__":
    import sys

    sys.path.insert(0, str(Path(__file__).parent.parent / "software"))
    from andi_datasets.datasets_challenge import challenge_phenom_dataset

    model = load_model()
    dfs_traj, _, _ = challenge_phenom_dataset(
        save_data=False, experiments=[1, 0, 0, 0, 0], return_timestep_labs=True, num_fovs=1
    )
    trajectory = dfs_traj[0].query("traj_idx == 0")[["x", "y"]].to_numpy()
    prediction = predict_trajectory(model, trajectory)
    print({name: values.shape for name, values in prediction.items()})