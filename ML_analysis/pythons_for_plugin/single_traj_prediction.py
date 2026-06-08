import torch
import torch.nn.functional as F
import math
import torch.nn as nn
from step.models import XResAttn
import numpy as np

dvc = 'cuda' if torch.cuda.is_available() else 'cpu'

####### DATASET PREPARATION #######
# The model gets as input a trajectory in relative polar coordinates

def get_angle(from_vec, # T, d=2
              to_vec,   # T, d=2
              ):
    x1, y1 = from_vec[...,0], from_vec[...,1]
    x2, y2 = to_vec[...,0], to_vec[...,1]
    dot = x1*x2 + y1*y2      # Dot product between [x1, y1] and [x2, y2]
    det = x1*y2 - y1*x2      # Determinant (cross2d) # 2.53 micros, njit 256 ns
    return np.arctan2(det, dot)

# We want a relative description of the trajectory
# the modulus of the displacement Delta r, and the change of angle between successive displacements Delta theta 
def cartesian_to_relative_polar(trajs_cart):
    """
    Converts 2D trajectories in cartesian coordinates to
    a relative representation of polar coordinates.

    Parameters:
    - cartesian_coordinates:
    A numpy array of shape (N, T, 2) where
    N is the number of trajectories,
    T is the number of time steps, and
    2 represents the x and y coordinates.

    Returns:
    - An array containing:
    the radius (rho) of each point, and
    the angle from previous direction (theta) of each point in radians.
    """
    # take displacements of the trajectory
    disps_cart = trajs_cart[:, 1:] - trajs_cart[:, :-1]
    # Allocate
    polar_coords = np.zeros_like(disps_cart)

    # Calculate the magnitude (rho) of each point
    polar_coords[...,0] = np.hypot(disps_cart[..., 0], disps_cart[..., 1])  #  dr

    # Calculate the angle (theta) between segments
    v1,v2 = trajs_cart[:,1:-1]-trajs_cart[:,:-2], trajs_cart[:,2:]-trajs_cart[:,1:-1]
    polar_coords[:,1:,1] = get_angle(v1,v2) # d theta

    return polar_coords


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
        feat_dim = hidden_dim * n_layers * 3 + 1
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
        with torch.no_grad():
            x = self.backbone.norm(x)
            x = self.backbone.convs(x.transpose(2, 1)).transpose(2, 1)  # (batch, T', hidden)
            src = self.backbone.pos_enc(x.transpose(1, 0))               # (T', batch, hidden)
            layer_outputs = []
            y = src
            for layer in self.backbone.encoder.layers:
                y = layer(y)
                layer_outputs.append(y.transpose(0, 1))   # (batch, T', hidden)
        # Dual attention pooling outside no_grad so attn_queries receive gradients
        layer_pools = []
        for i, z in enumerate(layer_outputs):
            scores = self.attn_queries[i](z)                        # (batch, T', 2)
            weights = F.softmax(scores, dim=1)                      # (batch, T', 2)
            layer_pools.append(z.mean(dim=1))                       # mean
            layer_pools.append((z * weights[:, :, 0:1]).sum(dim=1)) # attn head 1
            layer_pools.append((z * weights[:, :, 1:2]).sum(dim=1)) # attn head 2
        out = torch.cat(layer_pools, dim=1)
        out = self.drop(out)
        log_t = torch.full((out.shape[0], 1), math.log(T), device=out.device)
        out = torch.cat([out, log_t], dim=1)
        return self.head(out)
    

if __name__ == "__main__":
    
    # MODEL DEFINITION
    backbone_single = XResAttn(2, n_class=1, stem_szs=(32,), conv_blocks=[1, 1, 1],
    block_szs=[128, 256, 512], dropout=0., pos_enc=False, n_encoder_layers=4,
    dim_ff=512, nhead_enc=8, norm=False, linear_layers=[])

    model_single = SingleTrajModel(backbone_single).to('cuda')

    model_single.load_state_dict(torch.load("kistep_adapted_single_exp8.pth"))



    # EXAMPLE USAGE
    from andi_datasets.datasets_challenge import challenge_theory_dataset

    X1, Y1, X2, Y2, X3, Y3 = challenge_theory_dataset(N = 1000, tasks = 1, dimensions = 2,
                                            min_T = 10, max_T = 200, load_dataset = False)

    # Prepare data in polar representation
    inputs = X1[1]
    outputs = torch.tensor(Y1[1]).to(dvc)

    tensor_X = []
    for i in inputs:
        traj = torch.tensor(i).reshape(2, -1).T.unsqueeze(0) # This gives a tensor of shape (1, T, 2) for each trajectory, where T is the length of the trajectory. 
        tensor_X.append(traj)
    lengths = [t.shape[1] for t in tensor_X]
    polar_X = [torch.tensor(cartesian_to_relative_polar(traj), dtype=torch.float).to(dvc) for traj in tensor_X]

    preds = []
    for traj in polar_X:
        pred = model_single(traj)
        preds.append(pred)
