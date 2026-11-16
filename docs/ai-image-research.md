# Image AI for scanned pages: research and decision

Which models find, flatten and clean pages, measured on real photos and on the
server's own CPU, with sources and licences. Server AI for text (chat,
embeddings, OCR) is a separate study: [ai-research.md](ai-research.md).
Status: decided 2026-11-16; page detection is in the app, flattening is next.

## Decision

| Step | Model | Licence | Size | Runs on | Result |
|---|---|---|---|---|---|
| Find the page | **DocAligner lcnet100** (heatmap), official decoding | Apache-2.0 | 4.6 MB | phone (OpenCV dnn) | SmartDoc 2015: Jaccard **0.989**, 100 % of 1,180 real frames within 2 % of the diagonal |
| Flatten curved and book pages | **UVDoc**, on the page cut out with a 5 % margin | MIT | 32 MB | phone (OpenCV dnn), as a download | Book100: MS-SSIM 0.270 → **0.454** |
| Clean up (shadows, colour, bleed-through) | the app's classical filter (`DocumentEnhancer`) | ours | – | phone | second best everywhere, 0.45 s, 0.3 GB |
| Clean up, later and optional | **GCDRNet** | MIT | 114 MB | server | best on all four test sets (+1.1 to +3.0 dB over the classical filter), 1.7 s and 2 GB per page |

No generative model touches a page: every model here maps pixels to pixels or
predicts geometry, so it cannot invent text.

## Hardware it has to fit

The server (measured 2026-11-16): Intel i5-1235U (10 cores, 12 threads, AVX2 and
AVX-VNNI), 15.7 GB RAM shared with other household services, no dedicated GPU.
The Iris Xe iGPU uses system RAM (the "8 GB" Windows shows is shared memory);
OpenVINO sees it only with Intel's compute runtime installed (needs root), and
none of the chosen models needs it. Every benchmark job ran at `nice 19` under a
hard memory cap without swap (systemd user scope), so it could not squeeze the
other services. The phone runs the models through OpenCV 5's `dnn` module, which
the app already ships: outputs match ONNX Runtime to 1.5 × 10⁻⁶ (lcnet100) and
pixel for pixel (UVDoc), so no new native library is needed.

## Candidates and licences

Only permissive licences qualify: the app and the server are open source and
may be used commercially.

| Model | Task | Licence | Status |
|---|---|---|---|
| [DocAligner](https://github.com/DocsaidLab/DocAligner) lcnet050 (point), lcnet100, fastvit_t8, fastvit_sa24 (heatmap) | page corners | Apache-2.0 | measured |
| [UVDoc](https://github.com/tanguymagne/UVDoc) (SIGGRAPH Asia 2023), [ONNX by PaddlePaddle](https://huggingface.co/PaddlePaddle/UVDoc_onnx) | dewarping | MIT | measured |
| [DocRes](https://github.com/ZZZHANG-jx/DocRes) (CVPR 2024) | appearance, deshadowing, dewarping, deblurring | MIT | measured, rejected (memory) |
| [GCDRNet](https://github.com/ZZZHANG-jx/GCDRNet) (IEEE TAI 2023) | appearance | MIT | measured |
| [DocShadow-SD7K](https://github.com/CXH-Research/DocShadow-SD7K) (ICCV 2023) | shadow removal | MIT | measured, rejected (quality) |
| PaperEdge, DewarpNet | dewarping | MIT | not measured: older than UVDoc and behind it in UVDoc's own comparison |
| DocTr, DocTr++, DocScanner, DocGeoNet | dewarping | non-commercial | excluded |
| D2Dewarp (CVPR 2026) | dewarping | CC BY-NC-ND | excluded |
| BookNet (TMM 2026) | book dewarping | non-commercial (as published) | excluded |

Model files and their SHA-256 (pinned wherever they are fetched):

| File | Source | SHA-256 |
|---|---|---|
| `lcnet100_h_e_bifpn_256_fp32.onnx` (4,767,987 B) | DocsaidLab's own download (Google Drive id in `docaligner/heatmap_reg/infer.py`); identical to the Hugging Face mirror | `f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e` |
| `uvdoc.onnx` (31,684,150 B) | PaddlePaddle/UVDoc_onnx | `54cab30dc2cf347b4f3d6971c833b1c3d84f5ef17280f1f6d15796bff20e63d6` |

## Test sets

| Set | What | Size | Licence / use |
|---|---|---|---|
| [SmartDoc 2015 Challenge 1](https://github.com/jchazalon/smartdoc15-ch1-dataset) | real hand-held phone video frames, 5 backgrounds (one cluttered), ground-truth corners | every 20th frame: 1,180 | CC BY 4.0. Archive checked against its SHA-256. DocAligner's authors use it as their test set |
| Book100 | real photos of curved book pages and their flat scans | 100 pairs | research data, evaluation only, not redistributed |
| RealDAE (from the GCDRNet paper) | real photos with colour casts, shadows, bleed-through, and clean targets | 20 per task (150 for the cheap methods) | research data, evaluation only |
| DocUNet (aligned by DocAligner) | real distorted documents and their scans, aligned | 20 (130 for the cheap methods) | research data, evaluation only |
| Synthetic detection set (ours) | pages pasted on dark, light and white desks, with hand shadows and curls | 40 | **retired**, see below |

Metrics: corner error as a share of the photo diagonal ("within 2 %") and the
Jaccard index in the page's own frame (SmartDoc's official measure, so results
compare with published ones); MS-SSIM at an area of 598,400 px (DocUNet
protocol) for flattening; PSNR and SSIM for appearance; median seconds per page
and peak RAM on the server.

## Results

### Finding the page (SmartDoc 2015, 1,180 real frames)

| Detector | Jaccard | Within 2 % | Cluttered background | Seconds (server CPU) |
|---|---|---|---|---|
| **lcnet100, official decoding** | **0.989** | **100 %** | 0.98–0.99 | 0.031–0.048 |
| fastvit_t8, official decoding | 0.989 | 100 % | 0.99 | 0.015–0.035 |
| fastvit_sa24 (83 MB) | 0.993 | 100 % | 0.99 | 0.074 |
| lcnet050 (point regression) | 0.960 | 99.6 % | 0.92 | 0.015 |
| the app's classical detector (Canny + contours) | 0.184 | 18.7 % | 0.00 | 0.003 |

The published numbers (DocAligner benchmark: 0.9892, 0.9906, 0.9937, 0.9596)
are reproduced to the third decimal, which also confirms the model files.
lcnet100 is chosen over fastvit_t8 because it is a third of the size with the
same score, and it was better at finding book pages (below).

Two lessons, both recorded so they are not repeated:

- **Use the model's own preprocessing.** BGR scaled to 0..1, bilinear 256 × 256,
  and each corner as the centroid of its heatmap's largest blob above 0.3. An
  input format picked by tuning (RGB with ImageNet normalisation) scored only
  0.749.
- **The synthetic set misleads.** It ranked the models the other way round and
  suggested that wrong input format. Pasted pages are not photos, so detection
  is judged on real frames only.

The app decodes on the 128 × 128 heatmap (value-weighted blob centroid) instead
of a full-size resize, and falls back to the peak where a corner stays below 0.3,
so a faint corner still yields a page. That scored the same 0.989 at a third of
the time.

### Flattening (Book100, 100 real book photos)

| Pipeline | MS-SSIM | SSIM | Seconds | Peak RAM |
|---|---|---|---|---|
| **lcnet100 → box with 5 % margin → UVDoc** | **0.454** | 0.498 | 0.30 | 0.9 GB |
| same, 12 % margin | 0.450 | 0.497 | 0.34 | 0.9 GB |
| UVDoc on the whole photo | 0.431 | 0.485 | 0.30 | 0.8 GB |
| lcnet100 → tight 4-corner crop → UVDoc | 0.392 | 0.459 | 0.28 | 0.9 GB |
| lcnet100 → tight crop only | 0.385 | 0.450 | 0.04 | 0.6 GB |
| the photo as it is | 0.270 | 0.331 | – | – |

A curved page bulges outside the straight lines between its corners. A tight
crop cuts those parts off and removes the background UVDoc reads the curve from,
so flattening runs on a loose box around the page. On synthetic bent pages with
exact ground truth, UVDoc's ONNX and PyTorch versions agree (0.393 vs 0.391).

### Clean-up (same 20 images per set for every method)

PSNR in dB (higher is better); SSIM in brackets.

| Method | Colour casts | Shadows | Bleed-through | DocUNet | Seconds | Peak RAM |
|---|---|---|---|---|---|---|
| **GCDRNet** | **22.4** (0.92) | **24.0** (0.95) | **27.3** (0.98) | **19.3** (0.84) | 1.5–1.8 | 1.8–2.0 GB |
| classical filter (the app's) | 18.8 (0.91) | 22.2 (0.93) | 24.3 (0.94) | 18.2 (0.83) | 0.43–0.64 | 0.3–0.4 GB |
| GCDRNet then classical | 19.3 (0.87) | – | 24.6 (0.97) | 17.8 (0.82) | 1.9–2.3 | 2.0 GB |
| DocShadow-SD7K | – | 12.7 (0.84) | – | 13.6 (0.79) | 4.1–4.7 | 1.6 GB |
| photo as it is | 16.4 (0.92) | 9.3 (0.79) | 11.0 (0.84) | 13.3 (0.78) | – | – |

DocRes reached 24.1 and 28.6 dB on its first two colour-cast pages, but took
about 200 s per page and 13 GB of RAM at 1,600 px, which pushed the shared server
into swap. It was stopped, and every heavy job since runs under a memory cap.
DocShadow scores below doing nothing on these sets (it is trained on SD7K's
studio shadows). Stacking the classical filter after GCDRNet makes it worse.

## What is built, and what is next

1. **Page detection (built):** lcnet100 in the app (`feature/scan/imaging/AiPageDetector`),
   with the decoding above. Photos and imports use the AI outline; the live
   camera keeps the fast classical detector for every frame and asks the AI when
   that finds nothing. Where both agree, the classical outline (sharper edges at
   full resolution) is kept.
2. **Flattening (next):** UVDoc as a download from the paired server (32 MB
   would grow every app update by half), checked against the SHA-256 above, and
   applied as a non-destructive step in the page recipe with an explicit
   "Flatten curved page" action, so flat pages are never touched.
3. **Server clean-up (optional, later):** GCDRNet as an opt-in server step,
   loaded on demand like the chat model (§11.8), since it needs 2 GB per page.

To reproduce: the benchmark harness (methods, metrics, dataset loaders) ran in
`~/scanova-ai-bench` on the server and is described above. Datasets are fetched
from the sources listed and are never committed.
