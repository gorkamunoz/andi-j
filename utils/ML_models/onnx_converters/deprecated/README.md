# Deprecated converters

Converters and architectures of earlier bundled models, kept for the record. The
current plugin does not run the models they export. The bundled models are built by
`../convert_analysis_v1.py` and `../convert_seg_v2.py`; `../log_analysis.md` and
`../log_seg.md` describe every version.

| Files | Model |
|---|---|
| `convert_analysis_v0.py`, `model_analysis_v0.py` | analysis v0 |
| `convert_seg_v0.py`, `model_seg_v0.py` | segmentation v0 |
| `convert_seg_v1.py`, `model_seg_v1.py` | segmentation v1 |

Weights are read from `../../model_weights/`, as for the current converters.
