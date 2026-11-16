"""Scanova AI evaluation suite — command line.

    generate   build the synthetic golden dataset (needs Pillow + fontTools for images)
    run        evaluate one chat + embedding endpoint pair, write report.json/.md
    mock       serve a mock OpenAI-compatible runtime (for the suite's own tests)
    selftest   run the suite's tests offline (mock runtime, no model, stdlib only)

See tools/ai-eval/README.md. Run with `python3 -I`; this file puts its own
directory on the import path explicitly.
"""

import argparse
import json
import os
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from ai_eval import SUITE_VERSION  # noqa: E402


def cmd_generate(args):
    from ai_eval import dataset

    builder = dataset.build(args.seed)
    out = pathlib.Path(args.out)
    extra = {}
    if not args.text_only:
        from ai_eval import fonts, render

        def progress(done, total):
            print(f"\r  rendered {done}/{total}", end="", file=sys.stderr, flush=True)

        render.render_dataset(builder.docs, out / "images", args.font_cache, args.seed, progress)
        print(file=sys.stderr)
        import PIL
        from PIL import features

        extra = {
            "fonts": fonts.manifest(),
            "renderer": {"pillow": PIL.__version__, "raqm": features.version("raqm")},
        }
    else:
        for doc in builder.docs:
            doc["images"] = []
    dataset.write(builder, out, args.seed, {"text_only": args.text_only, **extra})
    counts = {}
    for doc in builder.docs:
        key = f"{doc['type']}/{doc['lang']}"
        counts[key] = counts.get(key, 0) + 1
    print(json.dumps({"dataset": str(out), "documents": len(builder.docs), "by_type": counts,
                      "queries": len(builder.queries), "questions": len(builder.questions)}, indent=1))


def cmd_run(args):
    from ai_eval import runner

    return runner.main(args)


def cmd_mock(args):
    from ai_eval import mock_server

    mock_server.serve(args)


def cmd_selftest(_args):
    import unittest

    suite = unittest.defaultTestLoader.discover(str(HERE / "tests"), top_level_dir=str(HERE))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


def env(name, default=None):
    value = os.environ.get(name, "").strip()
    return value or default


def main(argv=None):
    parser = argparse.ArgumentParser(prog="aieval", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", action="version", version=f"scanova-ai-eval {SUITE_VERSION}")
    sub = parser.add_subparsers(dest="command", required=True)

    g = sub.add_parser("generate", help="build the synthetic dataset")
    g.add_argument("--out", required=True, help="dataset directory to write")
    g.add_argument("--seed", type=int, default=20261116)
    g.add_argument("--font-cache", default=str(HERE / ".cache" / "fonts"), help="where the pinned fonts are kept")
    g.add_argument("--text-only", action="store_true", help="no images (no Pillow needed); OCR tests are skipped")
    g.set_defaults(func=cmd_generate)

    r = sub.add_parser("run", help="evaluate one endpoint pair")
    r.add_argument("--dataset", required=True)
    r.add_argument("--out", required=True, help="report directory to write")
    r.add_argument("--label", default=None, help="name of this run in the report (default: the two model names)")
    r.add_argument("--chat-base-url", default=env("AI_CHAT_BASE_URL"))
    r.add_argument("--chat-model", default=env("AI_CHAT_MODEL"))
    r.add_argument("--embed-base-url", default=env("AI_EMBED_BASE_URL"))
    r.add_argument("--embed-model", default=env("AI_EMBED_MODEL"))
    r.add_argument("--embed-dimensions", type=int, default=int(env("AI_EMBED_DIMENSIONS", "0")) or None,
                   help="Matryoshka truncation (default: the full vector)")
    r.add_argument("--api-key-env", default="AI_API_KEY", help="environment variable that holds the API key")
    r.add_argument("--tests", default="all", help="comma-separated test numbers or names (default: all)")
    r.add_argument("--ocr", default="vlm,tesseract",
                   help="OCR engines: vlm, tesseract, cmd:<command reading PNG on stdin, {lang} = eng|urd>")
    r.add_argument("--limit", type=int, default=None, help="at most this many documents per test (quick runs)")
    r.add_argument("--max-ocr-pages", type=int, default=24, help="pages per OCR engine (VLM OCR is slow on CPU)")
    r.add_argument("--max-questions", type=int, default=24)
    r.add_argument("--timeout", type=float, default=600.0, help="seconds per request")
    r.add_argument("--extra-body", default=None, help="JSON object merged into every chat request")
    r.add_argument("--no-schema", action="store_true", help="do not send response_format (measures prompt-only JSON)")
    r.add_argument("--embed-max-chars", type=int, default=1500, help="characters of a document embedded")
    r.add_argument("--memory", action="append", default=[], metavar="NAME=PATH",
                   help="memory source per runtime: a cgroup v2 directory or pid:<pid>; repeatable")
    r.add_argument("--runtime-log", action="append", default=[], metavar="FILE",
                   help="runtime log captured after the run; scanned for document text (test 12)")
    r.add_argument("--infra-checks", default=None, help="JSON file with infrastructure checks (test 12)")
    r.set_defaults(func=cmd_run)

    m = sub.add_parser("mock", help="serve a mock OpenAI-compatible runtime")
    m.add_argument("--host", default="127.0.0.1")
    m.add_argument("--port", type=int, default=18080)
    m.add_argument("--api-key-env", default="AI_API_KEY")
    m.add_argument("--dataset", default=None, help="lets the mock 'read' dataset pages (OCR oracle)")
    m.add_argument("--log", default=None, help="request log (method, path, status only)")
    m.set_defaults(func=cmd_mock)

    s = sub.add_parser("selftest", help="run the suite's own tests offline")
    s.set_defaults(func=cmd_selftest)

    args = parser.parse_args(argv)
    return args.func(args) or 0


if __name__ == "__main__":
    sys.exit(main())
