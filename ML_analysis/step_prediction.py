import torch
from pathlib import Path
from step.models import XResAttn
import numpy as np

dvc = 'cuda' if torch.cuda.is_available() else 'cpu'
# STEPalpha: Anomalous exponent model
model_kistep = XResAttn(2, n_class=1, stem_szs=(32,), conv_blocks=[1, 1, 1],
             block_szs=[128, 256, 512], dropout=0., pos_enc=False, n_encoder_layers=4,
             dim_ff=512, nhead_enc=8, norm=False, linear_layers=[],yrange=(-0.1,2.1)).to('cuda')

model_kistep.load_state_dict(torch.load('xresattn_alpha_2d_varlen_relpolar_yrange_m01_21_big22E2.pth')['model'])



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



###### EXAMPLE USAGE ######
from andi_datasets.datasets_challenge import challenge_phenom_dataset


dfs_traj, _, _ = challenge_phenom_dataset(save_data = False, # If to save the files
                                          experiments = [2, 2,2,2,2],
                                                                return_timestep_labs = True,
                                                                num_fovs = 2, # Number of FOVs
                                                                )


dataset = []
alphas = []
preds = []
for df in dfs_traj:
    # Each df has keys: ['traj_idx', 'frame', 'x', 'y', 'alpha', 'D', 'state']
    for idx in df.traj_idx.unique():
        # Transform the trajectory to relative polar coordinates
        traj_cart = df[df.traj_idx == idx][['x', 'y']].values  # (T, 2)
        traj_polar = torch.tensor(cartesian_to_relative_polar(traj_cart[np.newaxis,...]), dtype=torch.float).to(dvc)    # (T-1, 2)
        dataset.append(traj_polar)
        # Predict the anomalous exponent alpha for this trajectory
        with torch.no_grad():
            traj_pred = model_kistep(traj_polar).item()
            preds.append(traj_pred)
        # Get the true alpha for this trajectory
        traj_alpha = df[df.traj_idx == idx].alpha.values
        alphas.append(traj_alpha)