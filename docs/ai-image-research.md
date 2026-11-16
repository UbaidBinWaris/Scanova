# Image AI for scanned pages: research and decision

Which models find, flatten and clean pages, measured on real photos and on the
server's own CPU, with sources and licences. Server AI for text (chat,
embeddings, OCR) is a separate study: [ai-research.md](ai-research.md).
Status: decided 2026-11-16; page detection and flattening are in the app, and
server clean-up (GCDRNet) is built: a runtime on the server that holds the model in
memory only while the owner has "Page clean-up" switched on in the app.

## Decision

| Step | Model | Licence | Size | Runs on | Result |
|---|---|---|---|---|---|
| Find the page | **DocAligner lcnet100** (heatmap), official decoding | Apache-2.0 | 4.6 MB | phone (OpenCV dnn) | SmartDoc 2015: Jaccard **0.989**, 100 % of 1,180 real frames within 2 % of the diagonal |
| Flatten curved and book pages | **UVDoc**, on the page cut out with a 5 % margin, as a mesh model | MIT | 16 MB (float16 weights) | phone (OpenCV dnn), bundled | Book100: MS-SSIM 0.270 → **0.454**; flattens a page automatically when it is bent |
| Clean up (shadows, colour, bleed-through) | the app's classical filter (`DocumentEnhancer`) | ours | – | phone | second best everywhere, 0.45 s, 0.3 GB |
| Clean up on the server, optional | **GCDRNet**, both networks as one ONNX graph | MIT | 22 MB | server (ONNX Runtime), only while the owner has it on | best on all four test sets (+1.1 to +3.0 dB over the classical filter); 1.2 s and 0.6 GB for a 1,200 px page, 6.8 s and 2.2 GB for the app's 2,400 px page |

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
| `gcnet/checkpoint.pkl` | the GCDRNet authors' OneDrive folder (linked from their README); identical to the Hugging Face mirror FahNos/GCDRnet | `dbc1257f63b93ff1e55b148d9a6897929f93c253feac00454d81c67e7dbf910a` |
| `drnet/checkpoint.pkl` | same | `fc31351af8deb40e490e8dfd450e29f84c11ffd64ff7e35ce57d21124bd5d53d` |
| `gcdrnet.onnx` (22,223,885 B) | ours: both checkpoints exported by `apps/ai-image/tools/export_gcdrnet.py`, in `apps/ai-image/model/` | `36573a09a9e6d905e12ce7e0c71e859cd11b92aa81d9eff637c8d6f8bdeb8aeb` |

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

### Flattening on the phone: the mesh model and when to use it

The published ONNX conversion of UVDoc takes the whole photo and returns the
flattened image. The app needs the *mesh* instead: stored in the page's recipe,
it lets the page be re-rendered, edited and synced without the model, at full
resolution. So the official PyTorch weights (`model/best_model.pkl` at commit
`4c9b82b`, SHA-256 `7e90861b…`, byte-identical to the file the benchmark used)
were exported with UVDoc's own model code to an ONNX file with a fixed
1 × 3 × 712 × 488 input and the 45 × 31 mesh as output
(`apps/android/feature/scan/eval/uvdoc_export.py`). Its weights are stored as
float16 behind a Cast to float32, which halves the file and leaves the maths in
float32. Preprocessing and upsampling follow UVDoc's `demo.py` and `utils.py`
(RGB scaled to 0..1, bilinear resize, `align_corners = true`).

| Check (6 Book100 photos) | Largest mesh difference from PyTorch | Time (server CPU) |
|---|---|---|
| ONNX Runtime 1.32, float32 | 1.7 × 10⁻⁶ | 0.08–0.10 s |
| OpenCV 5.0 dnn, float32 | 1.6 × 10⁻⁶ | 0.19–0.21 s |
| **OpenCV 5.0 dnn, float16 weights (shipped)** | **1.9 × 10⁻⁴** (≈ 0.3 px on a 3,000 px page) | 0.17–0.21 s |

| Pipeline on Book100 (100 photos) | MS-SSIM | SSIM | Seconds |
|---|---|---|---|
| published ONNX, loose 5 % box (above) | 0.454 | 0.498 | 0.30 |
| mesh model, float32, OpenCV dnn | 0.4537 | 0.5048 | 0.25 |
| **mesh model, float16 weights, OpenCV dnn (shipped)** | **0.4536** | **0.5048** | 0.25 |

On an Android 17 emulator the model loads from the app's assets and runs through
the app's OpenCV 5.1 (`PageFlatteningDeviceTest`).

**When a page is bent.** UVDoc also flattens flat pages, and on a flat page its
mesh only adds a little noise, so the app flattens a page only when it is bent.
The score: fit the best homography from the mesh's regular lattice to its points
(least squares, refined on the distances, as OpenCV's `findHomography` does) and
take the root-mean-square distance as a share of the page's diagonal. A flat page
in perspective is a homography, so it scores near 0. Measured on the 100 curved
Book100 pages and 300 flat SmartDoc 2015 frames (backgrounds 1 to 4; the sample
missed the cluttered background 5):

| Score | Curved: 5th / 50th / 95th percentile | Flat: 5th / 50th / 95th percentile | Best split |
|---|---|---|---|
| **2D mesh against best homography** | 0.0046 / 0.0086 / 0.0113 | 0.0017 / 0.0027 / 0.0054 | 7 % of curved missed, 8 % of flat flattened |
| 3D surface (UVDoc's second output) against best plane | 0.022 / 0.037 / 0.054 | 0.013 / 0.022 / 0.036 | 19 % and 16 % |

| Threshold (2D score) | 0.0040 | 0.0045 | 0.0048 | **0.0050** | 0.0055 | 0.0060 |
|---|---|---|---|---|---|---|
| curved pages missed | 3 % | 5 % | 7 % | **9 %** | 12 % | 14 % |
| flat pages flattened | 16 % | 11 % | 8 % | **6 %** | 5 % | 4 % |

The app uses 0.005 (`PageFlattening.BENT`). A flat page just above it is flattened
along a mesh that is nearly a perspective view anyway, so a wrong "bent" costs
little; a missed curl is one tap on "Flatten". The Kotlin score reproduces the
benchmark's on real UVDoc meshes within 2 % (`MeshGeometryTest`, with three meshes
from these sets as numbers, no images). Pages with no outline (nothing found, or
"whole photo") are not judged automatically: the threshold was measured on page
boxes, not on screenshots or scans that fill the frame.

**The flattened page's proportions.** The mesh gives where the page lies, not how
wide the paper is: a curl foreshortens it. Measured against the flat scans of the
100 Book100 pages (width ÷ height):

| Estimate | Median error | Within 5 % | Mean (negative: too narrow) |
|---|---|---|---|
| **perspective estimate from the mesh's corners (as for a cropped page; used)** | **4.9 %** | **50 %** | −3.5 % |
| mean edge lengths along the mesh | 5.1 % | 47 % | −3.9 % |
| longest edges along the mesh | 6.7 % | 39 % | +2.8 % |
| UVDoc's 3D surface (its second output) | 33.7 % | 0 % | −33 % |

UVDoc's 3D surface is not to scale, so it is not used. The corner estimate keeps a
small bias (3.5 % too narrow on average); it is not corrected by a factor tuned on
these 100 pages.

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

### Clean-up on the server: the model file, its cost and the runtime

The benchmark ran the authors' PyTorch code. The server runs one ONNX graph
instead (`apps/ai-image/model/gcdrnet.onnx`), converted, not retrained:
`apps/ai-image/tools/export_gcdrnet.py` loads the authors' code at commit
`415c97a` and chains both networks exactly as their `infer.py` does (GCNet
estimates the page's shading at full resolution, the photo is divided by it, and
DRNet restores the detail from the photo and that division). The checkpoints also
hold training state; the network itself is 22 MB.

| Check | Result |
|---|---|
| Weights | the authors' checkpoints (`gcnet` `dbc1257f…`, `drnet` `fc31351a…`), verified byte-identical to the authors' OneDrive downloads on 2026-11-16; the export script refuses any other file |
| Export | 22,223,885 bytes, SHA-256 `36573a09a9e6d905e12ce7e0c71e859cd11b92aa81d9eff637c8d6f8bdeb8aeb`, reproduced byte for byte by a second export (torch 2.15.0+cpu, onnx 1.23.2, timm 1.0.31); `test_server.py` fails if the file changes |
| ONNX Runtime against PyTorch | largest difference 2.1 × 10⁻⁵ (0.01 grey levels) on RealDAE photos |

Cost on the server's CPU (i5-1235U, ONNX Runtime 1.32, 2 threads), by the page's
longer side:

| Page | Seconds | Peak RAM |
|---|---|---|
| 1,200 px | 1.2 | 0.6 GB |
| 1,600 px | 3.2 | 1.1 GB |
| 2,000 px | 5.6 | 2.1 GB |
| **2,400 px (the app's page)** | **6.8** | **2.2 GB** |

The built container (ONNX Runtime 1.31.1 as pinned in `requirements.txt`, 4
threads, hardened, 3 GiB cap), measured on the development PC:

| Moment | Container memory |
|---|---|
| started, model not loaded | 13.7 MiB |
| cleaning a 2,400 × 1,700 page (7.0 s) | peak 1.87 GiB |
| after unloading | 24 MiB |

So the model is affordable only on demand, like the chat model (ARCHITECTURE.md
§11.8): the runtime (`apps/ai-image/server.py`) works like llama.cpp's router.
It holds no model; loading starts a child process with ONNX Runtime, and
unloading ends that process, so all of its memory goes back to the server. The
API loads it only while the owner has "Page clean-up" on and unloads it 15
minutes after their app was closed.

Privacy and hardening, because a page is a plaintext document:

- An API key on everything except `/health` (the AI runtimes' shared key file),
  compared in constant time.
- Only JPEG and PNG, at most 16 MB, 2,600 px on the longer side and 5.2
  megapixels (Pillow's decompression-bomb limit is set to the same); anything
  else is refused before the model sees it. One page at a time.
- Nothing about a page's content is logged: request lines carry the path and
  status, a cleaned page its seconds and output size. Error answers never echo
  the input, and the API never reads them.
- ONNX Runtime's telemetry is switched off, although the container has no route
  out anyway: `data_net` only (an internal network), no published port.
- Non-root (uid 10003), read-only root filesystem, every capability dropped,
  memory cap without swap (`AI_IMAGE_MEMORY_LIMIT`, 3 GiB).
- Supply chain: the Python base image pinned by digest, NumPy, Pillow and ONNX
  Runtime as hash-checked wheels with pip removed afterwards; CI runs the tests
  against the real model, Trivy scans the image, Dependabot watches the
  packages and the base image.

## What is built, and what is next

1. **Page detection (built):** lcnet100 in the app (`feature/scan/imaging/AiPageDetector`),
   with the decoding above. Photos and imports use the AI outline; the live
   camera keeps the fast classical detector for every frame and asks the AI when
   that finds nothing. Where both agree, the classical outline (sharper edges at
   full resolution) is kept.
2. **Flattening (built):** the 16 MB mesh model in the app
   (`feature/scan/imaging/PageFlattener`), bundled rather than downloaded (the
   owner's choice, 2026-11-16: it works offline and before pairing; the APK grows
   by 16 MB instead of 32 MB thanks to the float16 weights). The mesh is a
   non-destructive step of the page recipe (`PageEdit.flatten`); a page is
   flattened automatically when its score is 0.005 or more, and "Flatten" on the
   Page tab turns it off or forces it on.
3. **Server clean-up (built):** GCDRNet in its own runtime on the server
   (`apps/ai-image`, Compose service `ai-image`), built and started by the
   deploy when `AI_IMAGE_PROVIDER=gcdrnet`, with the model in memory only while
   the owner has "Page clean-up" switched on in the app (ARCHITECTURE.md
   §11.8), since a page needs up to 2.2 GB. While it is on, the scan review and
   the editor offer a "Server clean-up" look: the flattened page goes to the
   server (`POST /api/v1/ai/images/enhance`) and the cleaned image is used.
   When clean-up is not available, the page falls back to the Auto look.
   Turning it on, step by step: [server-ai-guide.md](server-ai-guide.md).

To reproduce: the benchmark harness (methods, metrics, dataset loaders) ran in
`~/scanova-ai-bench` on the server and is described above. Datasets are fetched
from the sources listed and are never committed.
