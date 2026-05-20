#!/usr/bin/env python3
"""
Convert the Keras GRU model (.h5) to ONNX format for the AnDi Fiji plugin.

Requirements (install once):
    pip install tensorflow tf2onnx onnx

Run from the ML_analysis/ directory:
    python convert_to_onnx.py

Output:
    ../src/main/resources/models/GRU_exponents.onnx

After running this script, rebuild the Maven project so the ONNX file gets
bundled into the JAR:
    mvn clean package
"""

import sys
from pathlib import Path

# ── paths ──────────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).resolve().parent
H5_PATH    = SCRIPT_DIR / "GRU_exponents_fbm_alpha=0.05-0.1_Tmax=30.h5"
OUT_DIR    = SCRIPT_DIR.parent / "src" / "main" / "resources" / "models"
ONNX_PATH  = OUT_DIR / "GRU_exponents.onnx"
SEQ_LEN    = 30   # must match Tmax the model was trained on

# ── checks ──────────────────────────────────────────────────────────────────
if not H5_PATH.exists():
    sys.exit(f"[ERROR] Model file not found: {H5_PATH}")

try:
    import tensorflow as tf
    import tf2onnx
    import onnx
except ImportError as e:
    sys.exit(f"[ERROR] Missing package: {e}\n"
             f"Install with: pip install tensorflow tf2onnx onnx")

# ── load model ──────────────────────────────────────────────────────────────
print(f"Loading {H5_PATH.name} ...")
model = tf.keras.models.load_model(str(H5_PATH), compile=False)
model.summary()

# ── convert ─────────────────────────────────────────────────────────────────
OUT_DIR.mkdir(parents=True, exist_ok=True)

# Dynamic batch size, fixed sequence length, 1 feature (1D position per step)
spec = (tf.TensorSpec((None, SEQ_LEN, 1), tf.float32, name="input"),)

print(f"\nConverting to ONNX  (input: batch × {SEQ_LEN} × 1) ...")
_, _ = tf2onnx.convert.from_keras(
    model,
    input_signature=spec,
    output_path=str(ONNX_PATH),
    opset=13,
)

# ── verify ───────────────────────────────────────────────────────────────────
print("Verifying ONNX model ...")
onnx_model = onnx.load(str(ONNX_PATH))
onnx.checker.check_model(onnx_model)

file_kb = ONNX_PATH.stat().st_size / 1024
print(f"\n✓  Saved  {ONNX_PATH}  ({file_kb:.1f} KB)")
print("  → Rebuild the Maven project to bundle it into the JAR:")
print("       mvn clean package")

# ── quick numerical sanity check ─────────────────────────────────────────────
try:
    import numpy as np
    import onnxruntime as ort
    from andi_datasets.utils_trajectories import normalize

    print("\nRunning sanity check with onnxruntime ...")
    sess = ort.InferenceSession(str(ONNX_PATH))
    input_name = sess.get_inputs()[0].name

    rng = np.random.default_rng(42)
    dummy = rng.normal(size=(4, SEQ_LEN)).astype(np.float64)
    dummy_norm = normalize(dummy).reshape(4, SEQ_LEN, 1).astype(np.float32)

    preds = sess.run(None, {input_name: dummy_norm})[0]
    print(f"  Sample predictions (α): {preds.flatten().round(3)}")
    print("  Sanity check passed ✓")
except ImportError:
    print("  (onnxruntime / andi_datasets not installed – skipping sanity check)")
