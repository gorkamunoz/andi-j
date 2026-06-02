"""
Convert the two STEP PyTorch models to ONNX for use with ONNX Runtime in Fiji.

Run from the ML_analysis/ directory:
    python convert_step_to_onnx.py

Outputs
-------
    step_alpha.onnx   – per-frame anomalous exponent α predictor
    step_logd.onnx    – per-frame log₁₀(D) predictor

After conversion:
    1. Copy both .onnx files to src/main/resources/models/
    2. Rebuild:  mvn clean package
    3. Deploy both JARs to Fiji.app/jars/

Note: the models are exported on CPU so no GPU is required here.
"""

import torch
from pathlib import Path
from step.models import XResAttn

SCRIPT_DIR = Path(__file__).parent
OPSET      = 13
DUMMY_LEN  = 50   # arbitrary trace length for tracing; seq_len axis will be dynamic


# ---------------------------------------------------------------------------
# Model builders
# ---------------------------------------------------------------------------

def _build_alpha(device='cpu'):
    m = XResAttn(
        2, n_class=1,
        stem_szs=(32,), conv_blocks=[1, 1, 1],
        block_szs=[128, 256, 512],
        dropout=0., pos_enc=False,
        n_encoder_layers=4, dim_ff=512, nhead_enc=8,
        norm=False, linear_layers=[],
        yrange=(-0.1, 2.1),
    ).to(device)
    ckpt = SCRIPT_DIR / 'xresattn_alpha_2d_varlen_relpolar_yrange_m01_21_big22E2.pth'
    m.load_state_dict(torch.load(str(ckpt), map_location=device)['model'])
    m.eval()
    return m


def _build_logd(device='cpu'):
    m = XResAttn(
        2, n_class=1,
        stem_szs=(64,), conv_blocks=[1, 1, 1],
        block_szs=[128, 256, 512],
        dropout=0., pos_enc=False,
        n_encoder_layers=4, dim_ff=512, nhead_enc=8,
        norm=False, linear_layers=[],
        yrange=(-6.5, 2.5),
    ).to(device)
    ckpt = SCRIPT_DIR / 'xresattn_2d_varlen_relpolar_log10r_yrange_m65_25_big17E2.pth'
    m.load_state_dict(torch.load(str(ckpt), map_location=device)['model'])
    m.eval()
    return m


# ---------------------------------------------------------------------------
# Export helper
# ---------------------------------------------------------------------------

def export(model, out_path: Path, label: str):
    dummy = torch.zeros(1, DUMMY_LEN, 2)
    with torch.no_grad():
        torch.onnx.export(
            model, dummy, str(out_path),
            input_names=['input'],
            output_names=['output'],
            dynamic_axes={
                'input':  {0: 'batch', 1: 'seq_len'},
                'output': {0: 'batch', 1: 'seq_len'},
            },
            opset_version=OPSET,
            do_constant_folding=True,
        )
    kb = out_path.stat().st_size // 1024
    print(f'  {label:12s}  ->  {out_path.name}  ({kb} kB)')


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if __name__ == '__main__':
    print('Loading STEP models on CPU ...')
    alpha_model = _build_alpha()
    logd_model  = _build_logd()

    print('Exporting to ONNX ...')
    export(alpha_model, SCRIPT_DIR / 'step_alpha.onnx', 'STEPalpha')
    export(logd_model,  SCRIPT_DIR / 'step_logd.onnx',  'STEPlogD')

    print('\nDone. Next steps:')
    print('  1. cp step_alpha.onnx step_logd.onnx ../src/main/resources/models/')
    print('  2. mvn clean package')
    print('  3. Copy both JARs to Fiji.app/jars/')
