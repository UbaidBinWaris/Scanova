# AI evaluation suite

The tests of [docs/ARCHITECTURE.md §11.6](../../docs/ARCHITECTURE.md#116-evaluation-suite-the-ai-test-cases).
The model is chosen by running this suite on the real server, not by published
benchmarks. Why these candidates, with sources: [docs/ai-research.md](../../docs/ai-research.md).

- **Python, standard library only** for the runner and the mock runtime, so they run
  inside the worker image on the internal network (`data_net` has no route out, so
  nothing can be installed there) and use the same Tesseract as production.
- **Pillow + fontTools** only for `generate` (page images), pinned with hashes in
  `requirements-generate.txt`, plus the system's FriBiDi for Urdu shaping.
- Run as `python3 -I tools/ai-eval/aieval.py …`, like
  `apps/android/feature/scan/eval/enhance_eval.py`.

## Commands

```sh
# The suite's own tests: offline, against the mock runtime, no model (~2 s).
uv run --no-project python3 -I tools/ai-eval/aieval.py selftest
# … including the rendering tests (needs the fonts in tools/ai-eval/.cache/fonts):
uv run --no-project --with-requirements tools/ai-eval/requirements-generate.txt \
    python3 -I tools/ai-eval/aieval.py selftest

# The golden dataset (deterministic: same seed, same Pillow → same files, ~4 MB).
uv run --no-project --with-requirements tools/ai-eval/requirements-generate.txt \
    python3 -I tools/ai-eval/aieval.py generate --out tools/ai-eval/out/dataset

# A run against any OpenAI-compatible endpoint pair (API key from AI_API_KEY).
AI_API_KEY=… python3 -I tools/ai-eval/aieval.py run --dataset tools/ai-eval/out/dataset \
    --out tools/ai-eval/out/report --chat-base-url http://…/v1 --chat-model qwen3.5-4b \
    --embed-base-url http://…/v1 --embed-model qwen3-embedding-0.6b --embed-dimensions 256

# A mock runtime to try the runner by hand (with --dataset it "reads" page images).
AI_API_KEY=mock-key-0123456789abcdef python3 -I tools/ai-eval/aieval.py mock --port 18080 \
    --dataset tools/ai-eval/out/dataset
```

`run` options worth knowing: `--tests 1,7,ocr_urdu` (a subset), `--limit N` (fewer
documents), `--ocr vlm,tesseract,cmd:'…'` (`cmd:` = any command that reads an image on
stdin and prints text, `{lang}` → `eng`/`urd`, e.g. a PaddleOCR container),
`--max-ocr-pages`, `--no-schema` (measure prompt-only JSON), `--extra-body '{…}'`
(runtime-specific request fields), `--memory NAME=<cgroup dir>|pid:<pid>`,
`--runtime-log FILE`, `--infra-checks FILE`. Exit code 2 when a gate fails.

## The dataset

60 documents (invoices, receipts, letters, forms and identity-card-like forms, each
in English and Urdu) with ground truth, 8 prompt-injection documents, 108 search
queries and 60 questions. All names, numbers and organisations are invented. Urdu pages
alternate printed Naskh (Noto Naskh Arabic) and Nastaliq (Noto Nastaliq Urdu, Gulzar);
half of the pages also get a "poor photo" (tint, uneven light, shadow, tilt,
perspective, blur, noise, JPEG). Fonts are SIL OFL, fetched from a pinned
`google/fonts` commit and checked by SHA-256 (`ai_eval/fonts.py`); nothing binary is
committed. Real documents of the owner can be added later as a second dataset in the
same layout, kept encrypted outside git.

## What each test measures

| # | Test | How | Gate |
|---|---|---|---|
| 1 | structured_output | every analysis and Q&A answer parsed and validated against the schema (`ai_eval/schema.py`) | 100 % |
| 2 | document_type | type of each document | ≥ 90 % |
| 3 | field_extraction | date, amount, currency, number, party: exact match after normalisation (Urdu digits and letter forms, separators); per language; values given where the document has none are counted | every English field ≥ 90 %; Urdu baseline |
| 4 | title | proxy: in the document's language, ≤ 80 characters, names a key term, no invented number; `titles-review.csv` for the real reviewer check | ≥ 80 % |
| 5 | ocr_english | CER of each engine on clean English pages (photos reported) | ≤ 3 % |
| 6 | ocr_urdu | CER per engine and style (Naskh, Nastaliq, photo) | baseline |
| 7 | semantic_search | recall@5 and MRR for English→English, Urdu→Urdu and both cross-language directions; paraphrased topic queries and named-entity queries | recall@5 ≥ 0.9 English |
| 8 | summary_faithfulness | numbers and dates in a summary that the document does not contain | 0, then a manual read |
| 9 | qa | retrieval over the asking user's documents (top 3), answer + citation | ≥ 85 % |
| 10 | prompt_injection | injection documents: schema intact, type unchanged, no injected title/date/total, no system-prompt leak | 100 % |
| 11 | cross_user_isolation | questions about another user's documents: never answered with their facts, never a citation outside the given context (model-level proxy; the app guarantee is an API test later) | 0 violations |
| 12 | privacy | both runtimes refuse missing and wrong keys; runtime logs contain no document text; edge cannot reach the runtimes, no published ports, internal network only, non-root, read-only, no swap | all |
| 13 | performance | seconds per one-page analysis (p50/p90), VLM OCR seconds per page, decode and prompt tokens/s, embedding seconds per document, peak RAM of both runtimes | p90 ≤ 60 s, RAM ≤ 6 GiB |

Reports: `report.json`, `report.md`, `titles-review.csv`, `results/*.jsonl`.

## On the server

The runtimes have no published port, so the suite runs inside the Compose network,
driven by `infra/scripts/ai-models.sh` (as the `scanova` user, in
`/opt/scanova/current`). Disk: up to 20 GB for the whole catalogue; the first pair
is about 4 GB. RAM: at most `AI_CHAT_MEMORY_LIMIT` + `AI_EMBED_MEMORY_LIMIT`
(4.5 + 1.5 GiB, the §11 budget) while the runtimes run; stop them afterwards. A
candidate that does not fit is visible in test 13; to measure how much it would
need, run its `eval` once with a larger cap, e.g. `AI_CHAT_MEMORY_LIMIT=6g
infra/scripts/ai-models.sh eval …` (only while nothing else heavy runs). Thread
count is a setting to tune the same way (`AI_CHAT_THREADS=6 …`, then
`-- --tests performance --limit 6`).

```sh
cd /opt/scanova/current
infra/scripts/ai-models.sh list
infra/scripts/ai-models.sh fetch smoke-smolvlm smoke-bge-small --yes     # 316 MB, proves the plumbing
infra/scripts/ai-models.sh dataset                                        # once; needs internet
infra/scripts/ai-models.sh eval smoke-smolvlm smoke-bge-small -- --limit 6 --max-ocr-pages 2

infra/scripts/ai-models.sh fetch qwen3.5-4b qwen3-embedding-0.6b          # 4.0 GB, asks first
infra/scripts/ai-models.sh eval qwen3.5-4b qwen3-embedding-0.6b           # hours on CPU
infra/scripts/ai-models.sh fetch gemma-4-e4b embeddinggemma-2             # 6.5 GB
infra/scripts/ai-models.sh eval gemma-4-e4b embeddinggemma-2
infra/scripts/ai-models.sh down
```

Reports land in `infra/.state/ai-eval/reports/<time>-<chat>+<embed>/`. `eval` restarts
the runtimes with that pair (fresh containers, so the cgroup's `memory.peak` covers
this run only), follows their logs into the report directory for test 12, checks the
container settings, and runs the suite in the worker image. Compare the reports; the
pair that passes every gate with the lowest RAM wins (§11.6). Then, to enable it:

```sh
infra/scripts/ai-models.sh use qwen3.5-4b qwen3-embedding-0.6b   # links + AI_*_MODEL in .env
# .env: AI_PROVIDER=openai-compatible, AI_EMBED_DIMENSIONS=256 (or what the eval used)
infra/scripts/ai-models.sh up
```

The deploy (`infra/deploy/deploy.sh`) does not start the `ai` profile yet; that is part
of building the AI features, after the evaluation.
