# AI research for milestone 2 — sources

Verified **2026-11-16** for [ARCHITECTURE.md §11](ARCHITECTURE.md#11-ai-milestone-2--research-based-plan).
Model facts come from the Hugging Face API (licence metadata, file sizes, SHA-256 of
every GGUF file) and the model cards; llama.cpp facts from its v1.0.0 source tree; the
rest from the linked pages. Published benchmarks only shortlist: the pick is made by
[tools/ai-eval](../tools/ai-eval/README.md) on the server (§11.6).

## 1. The server

| Fact | Value | Source |
|---|---|---|
| CPU | Intel Core i5-1235U: 2 P-cores + 8 E-cores, 12 threads, AVX2 (no AVX-512) | [Intel ARK][ark] |
| Memory | 15 GB, shared; the CPU takes DDR4-3200, DDR5-4800, LPDDR4x-4267 or LPDDR5-5200, **2 channels** | [Intel ARK][ark] |
| iGPU | Iris Xe *only with dual-channel memory*, otherwise branded UHD | [Intel ARK][ark] footnote |
| Disk | 238 GB NVMe, LUKS | server notes (2026-11-15) |
| Still unknown | memory type and whether both channels are populated (`sudo dmidecode -t memory`; or read it off the decode speed in test 13) | |

Memory caps already on the box: edge 0.25, postgres 2, OpenBao 0.5, api 1, worker 3,
web 0.5 GB (Garage is the server's own, outside the stack). With the operating system
and the other services on the shared server, the AI budget stays **≤ 6 GiB** for both
runtimes together: `AI_CHAT_MEMORY_LIMIT=4608m` + `AI_EMBED_MEMORY_LIMIT=1536m`.

### Expected speed (estimate, to be replaced by test 13)

Token generation on a CPU reads every active weight once per token, so it is bound by
memory bandwidth: tokens/s ≈ efficiency × bandwidth ÷ bytes per token. For a 4B model
at 4-bit (≈ 2.6 GB read per token) and the usual 50–70 % efficiency:

| Memory | Bandwidth | Generation | One-page analysis (≈1 200 prompt + 250 output tokens) |
|---|---|---|---|
| DDR4-3200, dual channel | 51.2 GB/s | ≈ 10–14 tokens/s | ≈ 40–60 s |
| DDR5-4800 / LPDDR5-5200, dual | 77–83 GB/s | ≈ 15–22 tokens/s | ≈ 30–45 s |
| DDR4-3200, single channel | 25.6 GB/s | ≈ 5–7 tokens/s | ≈ 70–100 s |

Prompt processing is compute-bound (tens of tokens/s on these cores). Page
transcription by the vision model adds ≈ 1 100 image tokens and the image encoder:
1.5–3 minutes per page. No published llama.cpp benchmark for the i5-1235U was found
(web search, 2026-11-16); Vulkan on Iris Xe helps prompt processing on some reports
and not on others ([llama.cpp Vulkan discussion][vulkan]), so it stays a benchmark
question (image `server-vulkan-v1.0.0-b12359-b1b770d@sha256:784bb87c…`, needs `/dev/dri`).

## 2. Runtime: llama.cpp `llama-server`

- **Version:** [v1.0.0][llama-v1] (2026-11-13, commit `b1b770d`, build b12359), the first
  stable release. It also fixed `response_format` for the Gemma 4 chat parser (#31915)
  and hardened the server (SSRF, CORS, DNS rebinding).
- **Image:** `ghcr.io/ggml-org/llama.cpp:server-v1.0.0-b12359-b1b770d@sha256:e3032ea0712da96dec820c770626385de4322dd06619fb5829256a504bbc9375`
  (multi-arch index; ≈ 281 MB compressed for amd64). CPU build with
  `GGML_CPU_ALL_VARIANTS` (picks the best kernel set at run time); entrypoint
  `/app/llama-server`; runs as root unless told otherwise → Compose sets uid 10003.
- **What Scanova relies on** (checked in `tools/server/README.md`, `server-http.cpp`,
  `server-context.cpp`, `common/arg.cpp` at v1.0.0, and by a smoke run of the real image
  on 2026-11-16):

| Need | How | Note |
|---|---|---|
| API key | `--api-key-file FILE` (one key per line) | only `/health` and `/v1/health` are public |
| JSON-schema output | `response_format: {type: "json_schema", json_schema: {schema}}` | grammar-constrained; 100 % valid JSON even from a 256M model in the smoke run |
| Embeddings | `--embeddings`, `POST /v1/embeddings` (L2-normalised) | pooling read from GGUF metadata: Qwen3-Embedding last, e5 mean, EmbeddingGemma 2 mean, bge-m3 CLS (headers checked) |
| Vision | `--mmproj FILE`, `--image-max-tokens N` | |
| No prompt in logs | `--log-verbosity 2` | request and response bodies and prompt tokens are logged only at debug level |
| No prompt over the API | `--no-slots` | `/slots` returns each slot's prompt text (`res["prompt"] = …detokenize`) |
| No earlier document kept in RAM | `--cache-ram 0`, `--ctx-checkpoints 0` | the prompt cache defaults to 8 GiB of RAM |
| Never downloads | `--offline`, no `-hf` | |
| Smaller surface | `--no-webui`; `--metrics`, `--props`, `--tools` stay off (defaults) | |
| Thinking off | `--reasoning off` | Qwen3.5 thinks by default |
| Memory when idle | `--sleep-idle-seconds 900` | unloads the model; `/health` does not wake it |

Two traps found while building it: llama.cpp refuses `--flag=value` (every value is its
own argument), and Compose refuses an environment-sourced secret for a read-only
container ("`file` is the sole supported option"), so a one-shot `ai-init` container
writes the key file into a volume only the runtimes mount.

## 3. Chat and vision models

| Candidate | Licence | Size and GGUF used (pinned in `infra/scripts/ai-models.sh`) | Vision | Languages, Urdu evidence |
|---|---|---|---|---|
| **Qwen3.5-4B** ([card][qwen35-4b], 2026-02-27) | Apache 2.0 | 4B; [ggml-org][qwen35-4b-gguf] Q4_K_M 3.03 GB + mmproj Q8_0 0.37 GB | yes (early fusion) | "201 languages and dialects"; MMMLU 76.1, OCRBench 85.0, CC-OCR 76.7, OmniDocBench 1.5 86.2 (Qwen's numbers); predecessor Qwen3-4B-2507 is the best small model in UrduMMLU (51.7) |
| **Gemma 4 E4B** ([card][gemma4-e4b], 2026-03-02) | Apache 2.0 ([Gemma 4 licence][gemma4-licence]) | 4.5B effective, 8B with per-layer embeddings; Google's [QAT Q4_0][gemma4-e4b-qat] 5.15 GB + mmproj 0.99 GB | yes (+ audio) | 35+ languages "out of the box", pretrained on 140+; MMMLU 76.6 (Google); not in UrduMMLU |
| **Ministral 3 3B** ([card][ministral3], 2025-12) | Apache 2.0 | [official GGUF][ministral3-gguf] Q4_K_M 2.15 GB + BF16 mmproj 0.84 GB | yes | card names "dozens" incl. Arabic; **UrduMMLU 49.2** — the only small vision model here with a measured Urdu score |
| Gemma 4 E2B ([card][gemma4-e2b]) | Apache 2.0 | 2.3B effective; [QAT Q4_0][gemma4-e2b-qat] 3.35 GB + mmproj 0.99 GB | yes | MMMLU 67.4 |
| Qwen3.5-2B ([card][qwen35-2b]) | Apache 2.0 | [unsloth][qwen35-2b-gguf] Q4_K_M 1.28 GB + mmproj F16 0.67 GB (no ggml-org build) | yes | 201 languages |
| Phi-4-mini ([card][phi4mini]) | MIT | [unsloth][phi4mini-gguf] Q4_K_M 2.49 GB | no | 23 languages, no Urdu; **UrduMMLU 34.2** (chance is 25) |

Urdu evidence is thin. [UrduMMLU][urdummlu] (June 2026, 26 431 questions) is the only
2026 Urdu benchmark found that scores small models: Qwen3-4B-Instruct-2507 51.7,
Ministral-3-3B 49.2, Gemma-3-4B 44.9, LLaMA-3.2-3B 34.9, Phi-4-mini 34.2 (Urdu
prompts); the best open model (DeepSeek-V4-Flash) 81.4, Gemini-3.5-Flash 90.3. Gemma 4
E2B/E4B and Qwen3.5 were not evaluated. Copying fields out of a document is easier
than answering knowledge questions, but only the golden dataset says how much.

Looked at and not shortlisted: Qwen3.6 and Qwen3.8 exist only at 27B and above;
Gemma 4 12B needs ≈ 7 GB at Q4; tiny-aya ([CohereLabs][tinyaya], strong multilingual
claims) is CC BY-NC 4.0; Granite 4.2 3B has no Urdu and no vision.

## 4. Embedding models

| Candidate | Licence | GGUF used | Dimensions, context | Evidence |
|---|---|---|---|---|
| **Qwen3-Embedding-0.6B** ([card][qwen3emb]) | Apache 2.0 | [official][qwen3emb-gguf] Q8_0 639 MB | 1024, Matryoshka 32–1024; 32K | 100+ languages; MMTEB multilingual mean 64.33 (Qwen); query-side instruction |
| **EmbeddingGemma 2** ([card][eg2], 2026-09-14) | **Apache 2.0** | [ggml-org][eg2-gguf] Q8_0 310 MB (text only) | 768, Matryoshka 512/256/128; 8K | 100+ languages; MTEB multilingual v2 61.36 (Google); successor of EmbeddingGemma 1, which was Gemma-licensed and gated |
| multilingual-e5-small / -base ([card][e5]) | MIT | [community][e5-gguf] q8_0 132 / 302 MB | 384 / 768; 512 tokens | Urdu explicitly in its language list; "query: " / "passage: " prefixes |
| bge-m3 ([card][bgem3]) | MIT | [ggml-org][bgem3-gguf] Q8_0 635 MB | 1024; 8K | 100+ languages; MMTEB 59.56 (in Qwen's table) |

No source measures Urdu or Urdu↔English retrieval for any of them: test 7 does. Still
excluded: jina-embeddings-v5 (CC BY-NC 4.0, [card][jina5]).

## 5. OCR

- **Tesseract on Urdu is poor**, now with a source: in [From Press to Pixels][press-pixels]
  (arXiv 2505.13943, LREC 2026) Tesseract's CER is 0.955 on OpenITI Naskh lines, 1.420
  on Nastaliq and 2.580 on Urdu newspaper paragraphs (insertions push CER above 1).
  Kraken: 0.069 / 0.305 / 0.221; EasyOCR 0.177 / 0.392 / 0.246. Best LLM, Gemini 2.5
  Pro: 0.066 / 0.125 / 0.032; the open Llama 4 Maverick 0.104 / 0.561 / 0.128 (UNB high
  resolution). No Gemma or Qwen numbers. The figures in the 2026-11-15 notes (≈ 55 %
  vendor test, 72 % and 49 % on handwriting) could not be traced to a source and are
  withdrawn.
- On the synthetic dataset (smoke run, worker image, `tesseract-ocr-urd`): 0.8–29 % CER
  on clean Urdu pages; it drops whole lines on some layouts. English clean pages: 0–0.3 %.
- **PaddleOCR**: PP-OCRv5's `arabic_PP-OCRv5_mobile_rec` lists Urdu (with Arabic,
  Persian, Uyghur, Pashto, Kurdish, Sindhi, Balochi), 81.27 % accuracy on Paddle's
  Arabic set, nothing Urdu-specific ([docs][ppocrv5]). PP-OCRv6 (PaddleOCR 3.7.0,
  2026-06-11) covers 50 languages and no Arabic script ([wiki][paddle-wiki]). It can be
  measured with the suite's `cmd:` OCR engine once packaged as a container.
- **Phone**: ML Kit Text Recognition v2 reads Chinese, Devanagari, Japanese, Korean and
  Latin only ([docs][mlkit]): Urdu becomes searchable only after server OCR.

## 6. Recommendation before the evaluation

Evaluate in this order; the suite decides:

1. **Qwen3.5-4B + Qwen3-Embedding-0.6B** (≈ 4.0 GB download): fits the budget with room
   (≈ 3.4 GB of chat files), explicit coverage of 201 languages, the strongest published
   OCR numbers of the small models, GGUF from the llama.cpp maintainers.
2. **Gemma 4 E4B + EmbeddingGemma 2** (≈ 6.5 GB): Google's own QAT build, best MMMLU of
   the small models, but its file (5.15 GB + 0.99 GB) is larger than the chat cap.
   Per-layer embeddings are looked up sparsely and the file is memory-mapped, so the
   resident size may well be lower; test 13 measures it.
3. **Ministral 3 3B**: the only candidate with a measured Urdu score; smaller.

Fallbacks: Gemma 4 E2B, Qwen3.5-2B; embeddings e5-small (tiny, lists Urdu) and bge-m3.
Phi-4-mini stays in the catalogue as an English-only reference.

**Lock-in**: none in the runtime (OpenAI-compatible API, provider-neutral config), the
model format (GGUF, several runtimes read it) or the licences (Apache 2.0 / MIT). The
one real lock-in is stored embeddings: each vector records its model, and a model
change re-embeds (§11.5). Prompts and schemas are versioned in `tools/ai-eval`.

## 7. Open points

- Memory channel layout of the server (decides speed more than the model does).
- Plain HTTP between the worker and the runtimes on `data_net` (same host, no other
  container has `NET_RAW`); llama-server supports `--ssl-key-file`/`--ssl-cert-file`
  if the internal CA should issue a certificate for it.
- Vulkan on Iris Xe and a PaddleOCR container: benchmark candidates, not defaults.

[ark]: https://www.intel.com/content/www/us/en/products/sku/226261/intel-core-i51235u-processor-12m-cache-up-to-4-40-ghz/specifications.html
[vulkan]: https://github.com/ggml-org/llama.cpp/discussions/10879
[llama-v1]: https://github.com/ggml-org/llama.cpp/releases/tag/v1.0.0
[qwen35-4b]: https://huggingface.co/Qwen/Qwen3.5-4B
[qwen35-4b-gguf]: https://huggingface.co/ggml-org/Qwen3.5-4B-GGUF
[qwen35-2b]: https://huggingface.co/Qwen/Qwen3.5-2B
[qwen35-2b-gguf]: https://huggingface.co/unsloth/Qwen3.5-2B-GGUF
[gemma4-e4b]: https://huggingface.co/google/gemma-4-E4B-it
[gemma4-e2b]: https://huggingface.co/google/gemma-4-E2B-it
[gemma4-licence]: https://ai.google.dev/gemma/docs/gemma_4_license
[gemma4-e4b-qat]: https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf
[gemma4-e2b-qat]: https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf
[ministral3]: https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512
[ministral3-gguf]: https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF
[phi4mini]: https://huggingface.co/microsoft/Phi-4-mini-instruct
[phi4mini-gguf]: https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF
[urdummlu]: https://arxiv.org/abs/2606.07167
[tinyaya]: https://huggingface.co/CohereLabs/tiny-aya-global
[qwen3emb]: https://huggingface.co/Qwen/Qwen3-Embedding-0.6B
[qwen3emb-gguf]: https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF
[eg2]: https://huggingface.co/google/embeddinggemma-2
[eg2-gguf]: https://huggingface.co/ggml-org/embeddinggemma-2-GGUF
[e5]: https://huggingface.co/intfloat/multilingual-e5-small
[e5-gguf]: https://huggingface.co/cstr/multilingual-e5-small-GGUF
[bgem3]: https://huggingface.co/BAAI/bge-m3
[bgem3-gguf]: https://huggingface.co/ggml-org/bge-m3-Q8_0-GGUF
[jina5]: https://huggingface.co/jinaai/jina-embeddings-v5-text-small
[press-pixels]: https://arxiv.org/abs/2505.13943
[ppocrv5]: https://www.paddleocr.ai/latest/en/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.html
[paddle-wiki]: https://github.com/PaddlePaddle/PaddleOCR/wiki
[mlkit]: https://developers.google.com/ml-kit/vision/text-recognition/v2
