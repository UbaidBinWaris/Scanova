# On-device models

## docaligner-lcnet100.onnx

Page corner detection used by `AiPageDetector` (docs/ai-image-research.md).

- Model: DocAligner `lcnet100_h_e_bifpn_256_fp32` (heatmap regression), by DocsaidLab,
  https://github.com/DocsaidLab/DocAligner (checked at commit 3275b0f, 2026-01-13).
- Licence: Apache License 2.0, copy in `docaligner-LICENSE.txt`. Unmodified.
- Obtained from DocsaidLab's own download (the Google Drive id in
  `docaligner/heatmap_reg/infer.py`).
- SHA-256: `f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e`
  (4,767,987 bytes). `ModelAssetsTest` fails if the file changes.
- Input: 1 × 3 × 256 × 256, BGR scaled to 0..1. Output: `heatmap`, 1 × 4 × 128 × 128
  (top-left, top-right, bottom-right, bottom-left), decoded by `HeatmapDecoder`.
