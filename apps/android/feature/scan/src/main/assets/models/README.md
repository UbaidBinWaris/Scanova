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

## uvdoc-mesh.onnx

Flattening of curled pages used by `PageFlattener` (docs/ai-image-research.md).

- Model: UVDoc (Verhoeven, Magne, Sorkine-Hornung, SIGGRAPH Asia 2023), by Tanguy Magne,
  https://github.com/tanguymagne/UVDoc (official weights `model/best_model.pkl` at commit
  4c9b82b, SHA-256 `7e90861b8a516eb4bc51f84bd889cb77275743d2d1d3ca8091951ec9f2b7da23`).
- Licence: MIT, copy in `uvdoc-LICENSE.txt`.
- Converted, not retrained: exported to ONNX by `feature/scan/eval/uvdoc_export.py` with
  the page mesh as its output (the published ONNX returns a flattened image instead), and
  the weights stored as float16 with a Cast to float32 in front of each, so the file is
  half the size and the maths stays float32. Book100 MS-SSIM 0.4536 against 0.4537 with
  float32 weights; largest mesh difference 1.9 × 10⁻⁴.
- SHA-256: `d2cff00114e406445e85fc685f8b8ef4d255361109c7a5a14367b1f2b57316ce`
  (16,041,896 bytes). `ModelAssetsTest` fails if the file changes.
- Input `image`: 1 × 3 × 712 × 488, RGB scaled to 0..1 (the page's box with a 5 % margin,
  resized bilinearly). Outputs: `mesh`, 1 × 2 × 45 × 31 (x, y of each point in −1..1 of
  the input, corners on the corner pixels), and `surface`, 1 × 3 × 45 × 31 (unused).
