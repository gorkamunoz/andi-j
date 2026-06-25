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


class V2CombinedModel(nn.Module):
    """
    Frozen Cls-Exp17 backbone + pooling + shared_encoder + cls_head
    + 5 per-type alpha regressors.

    Forward -> (B, 11):
      cols 0-4  : per-type alpha predictions
      cols 5-9  : cls logits
      col  10   : soft-routed alpha (sum of softmax(logits) * per-type preds)
    """
    def __init__(self, backbone, n_models=5, dropout=0.1):
        super().__init__()
        self.backbone = backbone
        self.drop = nn.Dropout(dropout)

        hidden_dim = backbone.encoder.layers[0].self_attn.embed_dim  # 512
        n_layers   = len(backbone.encoder.layers)                     # 4
        self.attn_queries = nn.ModuleList([
            nn.Linear(hidden_dim, 2, bias=False) for _ in range(n_layers)
        ])
        feat_dim = hidden_dim * n_layers * 3 + 12  # 6156
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

    def forward(self, x):
        T      = x.shape[1]
        dr_max = x[:, :, 0].max(dim=1, keepdim=True).values.clamp(min=1e-8)
        x      = x.clone()
        x[:, :, 0] = x[:, :, 0] / dr_max
        dtheta = x[:, :, 1]
        dr     = x[:, :, 0]

        mean_cos_dth    = dtheta.cos().mean(dim=1, keepdim=True)
        frac_large_turn = (dtheta.abs() > (math.pi / 2)).float().mean(dim=1, keepdim=True)
        half            = T // 2
        log_dr_trend    = torch.log(dr[:, half:].mean(dim=1, keepdim=True).clamp(min=1e-8) /
                                    dr[:, :half].mean(dim=1, keepdim=True).clamp(min=1e-8))
        mean_dr         = dr.mean(dim=1, keepdim=True).clamp(min=1e-8)
        cv_dr           = dr.std(dim=1, keepdim=True) / mean_dr
        frac_above_mean = (dr > mean_dr).float().mean(dim=1, keepdim=True)
        q1              = max(T // 4, 1)
        log_dr_Q1_Q4    = torch.log(dr[:, -q1:].mean(dim=1, keepdim=True).clamp(min=1e-8) /
                                    dr[:, :q1].mean(dim=1, keepdim=True).clamp(min=1e-8))
        interact_attm   = cv_dr * frac_above_mean
        interact_sbm    = log_dr_trend * (1.0 - frac_above_mean)
        log_var_dr_ratio = torch.log(dr[:, half:].var(dim=1, keepdim=True).clamp(min=1e-8) /
                                     dr[:, :half].var(dim=1, keepdim=True).clamp(min=1e-8))
        log_dr_f = torch.log(dr.clamp(min=1e-8))
        _v4 = 4 * log_dr_f; _m4 = _v4.max(dim=1, keepdim=True).values
        _v2 = 2 * log_dr_f; _m2 = _v2.max(dim=1, keepdim=True).values
        log_kurtosis = ((_m4 + torch.log((_v4 - _m4).exp().mean(dim=1, keepdim=True).clamp(min=1e-8))) -
                        2 * (_m2 + torch.log((_v2 - _m2).exp().mean(dim=1, keepdim=True).clamp(min=1e-8))))

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
        out = self.drop(out)

        log_t    = torch.full((out.shape[0], 1), math.log(T), device=out.device)
        inv_t    = torch.full((out.shape[0], 1), 1.0 / T,    device=out.device)
        features = torch.cat([out, log_t, inv_t, mean_cos_dth, frac_large_turn,
                               log_dr_trend, cv_dr, frac_above_mean, interact_attm,
                               interact_sbm, log_dr_Q1_Q4, log_var_dr_ratio, log_kurtosis], dim=1)

        shared     = self.shared_encoder(features)
        logits     = self.cls_head(shared)
        probs      = F.softmax(logits, dim=-1)
        reg_out    = torch.cat([reg(shared) for reg in self.regressors], dim=1)
        soft_alpha = (probs * reg_out).sum(dim=1, keepdim=True)
        return torch.cat([reg_out, logits, soft_alpha], dim=1)
    

if __name__ == "__main__":
    
    # MODEL DEFINITION
    backbone = XResAttn(
    2, n_class=1, stem_szs=(32,), conv_blocks=[1, 1, 1],
    block_szs=[128, 256, 512], dropout=0., pos_enc=False,
    n_encoder_layers=4, dim_ff=512, nhead_enc=8, norm=False, linear_layers=[]
)

    MODEL_PATH = '../model_weights/V2-Exp14_model.pth'
    model_single = V2CombinedModel(backbone, n_models=5, dropout=0.1).to(dvc)
    model_single.load_state_dict(torch.load(MODEL_PATH, map_location=dvc))
    model_single.eval()
    print(f'Loaded {MODEL_PATH}')
    total = sum(p.numel() for p in model_single.parameters())
    print(f'Parameters: {total:,}')



    # EXAMPLE USAGE
    from andi_datasets.datasets_challenge import challenge_theory_dataset
    from tqdm.auto import tqdm

    X1, Y1, X2, Y2, X3, Y3 = challenge_theory_dataset(N = 1000, tasks = [1,2], dimensions = 2, min_T = 10, max_T = 200, load_dataset = False)

    # Prepare data in polar representation
    inputs_alpha = X1[1]
    inputs_model = X2[1]


    polar_X_alpha = []
    polar_X_model = []
    for i_a, i_m in zip(inputs_alpha, inputs_model):
        traj_a = torch.tensor(i_a).reshape(2, -1).T.unsqueeze(0)
        polar_X_alpha.append(torch.tensor(cartesian_to_relative_polar(traj_a), dtype=torch.float).to(dvc))

        traj_m = torch.tensor(i_m).reshape(2, -1).T.unsqueeze(0)
        polar_X_model.append(torch.tensor(cartesian_to_relative_polar(traj_m), dtype=torch.float).to(dvc))

    ae = []
    acc = []
    with torch.no_grad():
        for traj_a, true_a, traj_m, true_m in zip(tqdm(polar_X_alpha), Y1[1], polar_X_model, Y2[1]):

            out = model_single(traj_a).cpu()[0]
            ae.append(np.abs(float(out[10])-true_a))
            
            out = model_single(traj_m).cpu()[0]
            pred = int(F.softmax(out[5:10], dim=0).numpy().argmax())
            acc.append(pred == true_m) 

print(np.mean(ae), np.mean(acc))
