import numpy as np
import torch
from torch.nn import L1Loss


def evaluate(model, dataloader, t_start, t_end):
    model.model.eval()
    mae_flat = L1Loss()

    save_mae = []
    for batch in dataloader:
        x, y = batch

        with torch.no_grad():
            pred = model.model(x)

        mae = mae_flat(y, pred).cpu()

        save_mae.append([mae, x.shape[1]])
    save_mae = np.array(save_mae)   
    # group save_mae in 4 groups based on min and max of trajectory length
    edges = [10, 50, 100, 150, 200]  # 4 groups → 5 edges

    save_mae_grouped = []
    for i in range(4):
        group = save_mae[(save_mae[:,1] > edges[i]-1) & (save_mae[:,1] <= edges[i+1]-1)]
        save_mae_grouped.append([group[:,0].mean(), edges[i], edges[i+1]])    

    print("---")
    print('mae: {:.6f}'.format(save_mae[:,0].mean()))
    for mae, edge_start, edge_end in save_mae_grouped:
        print(f"mae_{edge_start}_{edge_end}:  {mae:.6f}")
    print(f"total_seconds:    {t_end - t_start:.1f}")
    backbone_params = set(id(p) for p in model.backbone.parameters())
    non_backbone_params = sum(p.numel() for p in model.parameters() if id(p) not in backbone_params)
    print(f"Non-backbone parameters: {non_backbone_params:,}")