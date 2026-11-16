# Page clean-up model

## gcdrnet.onnx

The image AI runtime's model (`apps/ai-image/server.py`, docs/ai-image-research.md):
it removes shadows, colour casts and bleed-through from a flattened page.

- Model: GCDRNet (Zhang, Liang, Ding, Jin, IEEE Transactions on Artificial
  Intelligence, 2023), by Jiaxin Zhang, https://github.com/ZZZHANG-jx/GCDRNet
  (code checked at commit 415c97a). Its two networks use UNeXt (Valanarasu and
  Patel, MICCAI 2022) as their architecture.
- Licences: MIT, copies in `gcdrnet-LICENSE.txt` (GCDRNet) and `unext-LICENSE.txt`
  (UNeXt's architecture code).
- Weights: the authors' checkpoints, `gcnet/checkpoint.pkl` (SHA-256
  `dbc1257f63b93ff1e55b148d9a6897929f93c253feac00454d81c67e7dbf910a`) and
  `drnet/checkpoint.pkl` (`fc31351af8deb40e490e8dfd450e29f84c11ffd64ff7e35ce57d21124bd5d53d`),
  downloaded from the authors' OneDrive folder on 2026-11-16 and byte-identical to the
  Hugging Face mirror FahNos/GCDRnet.
- Converted, not retrained: both networks chained as in the authors' `infer.py` and
  exported to one ONNX graph by `apps/ai-image/tools/export_gcdrnet.py`. The checkpoints
  also hold training state; the network itself is 22 MB. ONNX Runtime matches PyTorch
  to 2.1 × 10⁻⁵ (0.01 grey levels) on RealDAE photos.
- SHA-256: `36573a09a9e6d905e12ce7e0c71e859cd11b92aa81d9eff637c8d6f8bdeb8aeb`
  (22,223,885 bytes); reproduced byte for byte by a second export. `test_server.py`
  fails if the file changes.
- Input `image`: 1 × 3 × H × W, BGR scaled to 0..1, H and W multiples of 32 (the page
  padded at its top and left by repeating the edge). Output `enhanced`: the same shape.
- Cost on the server's CPU (i5-1235U, ONNX Runtime 1.32 on the benchmark, 2 threads): 1.2 s and 0.6 GB
  for a 1,200 px page, 6.8 s and 2.2 GB for the app's 2,400 px page.
