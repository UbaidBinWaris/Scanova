"""Mock OpenAI-compatible runtime for the suite's own tests (stdlib only).

It behaves like llama.cpp where the suite depends on it: bearer-token check
on everything but /health, `/v1/chat/completions` honouring a JSON-schema
`response_format`, `/v1/embeddings`, `timings` in chat answers. The "model" is
a handful of rules: it reads labels like "Total" or "تاریخ" from the document,
retrieves by word overlap, and embeds with hashed character trigrams. With
`--dataset` it "reads" page images by looking up their SHA-256 in the dataset
(an OCR oracle), so the OCR pipeline can be tested end to end.

The request log holds method, path and status only, never bodies.
"""

import base64
import hashlib
import json
import math
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import metrics
from .textnorm import normalise, script_of

DIMENSIONS = 384


def embed(text):
    vector = [0.0] * DIMENSIONS
    clean = f"  {normalise(text)}  "
    for i in range(len(clean) - 2):
        digest = hashlib.blake2b(clean[i : i + 3].encode("utf-8"), digest_size=4).digest()
        index = int.from_bytes(digest, "little")
        vector[index % DIMENSIONS] += 1.0 if index & 0x80000000 else -1.0
    norm = math.sqrt(sum(v * v for v in vector)) or 1.0
    return [v / norm for v in vector]


_TYPE_RULES = [
    ("id_card", ["identity card", "identity number", "شناختی"]),
    ("form", ["application no", "درخواست نمبر"]),
    ("receipt", ["receipt", "رسید"]),
    ("invoice", ["invoice", "انوائس"]),
    ("letter", ["dear", "جناب", "ref:", "حوالہ"]),
]


def _value_after(text, labels):
    for line in text.split("\n"):
        clean = normalise(line)
        for label in labels:
            label = normalise(label)
            if clean.startswith(label):
                return line.split(":", 1)[1].strip() if ":" in line else None
    return None


def analyse(text):
    lines = [line for line in text.split("\n") if line.strip()]
    low = normalise(text)
    kind = next((name for name, words in _TYPE_RULES if any(normalise(w) in low for w in words)), "other")
    date_text = _value_after(text, ["Date of issue", "Date:", "تاریخ اجراء", "تاریخ:"])
    total = _value_after(text, ["Total due", "TOTAL", "Admission fee paid", "Monthly fee paid", "Fee paid",
                                "کل واجب الادا رقم", "کل رقم", "داخلہ فیس", "ماہانہ فیس", "ادا شدہ فیس"])
    number = _value_after(text, ["Invoice No", "Receipt No", "Ref", "Application No", "Identity number",
                                 "انوائس نمبر", "رسید نمبر", "حوالہ نمبر", "درخواست نمبر", "شناختی نمبر"])
    holder = _value_after(text, ["Name:", "نام:"]) if kind == "id_card" else None
    party = holder or (lines[0].strip() if lines else None)
    currency = None
    if total is not None and "روپے" in total:
        currency = "PKR"
    elif total is not None and total.split():
        currency = total.split()[0]
    language = "ur" if script_of(text) == "arabic" else "en"
    return {
        "language": language,
        "type": kind,
        "title": f"{kind} {party or ''}".strip()[:80],
        "tags": [kind],
        "fields": {
            "date": metrics.normalise_date(date_text),
            "amount": metrics.normalise_amount(total),
            "currency": metrics.normalise_currency(currency),
            "number": number,
            "party": party,
        },
        "summary": (lines[1] if len(lines) > 1 else "")[:400],
    }


_DOC_BLOCK = re.compile(r'<document id="([^"]+)">\n(.*?)\n</document>', re.S)


def answer_question(user):
    question = user.rsplit("Question:", 1)[-1].strip()
    words = {w for w in re.split(r"\W+", normalise(question)) if len(w) > 2}
    best, best_score = None, 0
    for doc_id, text in _DOC_BLOCK.findall(user):
        first = normalise(text.split("\n", 1)[0])
        score = sum(1 for w in words if w in first)
        if score > best_score:
            best, best_score = (doc_id, text), score
    if best is None or best_score < 2:
        return {"found": False, "answer": "", "citations": []}
    doc_id, text = best
    fields = analyse(text)["fields"]
    q = normalise(question)
    if any(w in q for w in ["date", "تاریخ"]):
        value = fields["date"]
    elif any(w in q for w in ["number", "نمبر"]):
        value = fields["number"]
    else:
        value = fields["amount"]
    if not value:
        return {"found": False, "answer": "", "citations": []}
    return {"found": True, "answer": str(value), "citations": [doc_id]}


class _State:
    def __init__(self, api_key, dataset=None, log_path=None):
        self.api_key = api_key
        self.log_path = log_path
        self.lock = threading.Lock()
        self.pages = {}
        if dataset is not None:
            from . import dataset as ds

            data = ds.load(dataset)
            for doc in data["docs"]:
                for image in doc.get("images", []):
                    path = data["root"] / image["file"]
                    self.pages[hashlib.sha256(path.read_bytes()).hexdigest()] = doc["text"]

    def log(self, line):
        if self.log_path is None:
            return
        with self.lock, open(self.log_path, "a", encoding="utf-8") as out:
            out.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} {line}\n")


def _handler(state):
    class Handler(BaseHTTPRequestHandler):
        server_version = "scanova-mock/1"

        def log_message(self, *_args):
            """Silences the default per-request stderr line; `State.log` writes the request log."""

        def _send(self, status, body):
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            state.log(f"{self.command} {self.path} {status}")

        def _authorised(self):
            return self.headers.get("authorization") == f"Bearer {state.api_key}"

        def do_GET(self):
            if self.path in ("/health", "/v1/health"):
                return self._send(200, {"status": "ok"})
            if not self._authorised():
                return self._send(401, {"error": {"code": 401, "message": "Invalid API Key", "type": "authentication_error"}})
            if self.path in ("/v1/models", "/models"):
                return self._send(200, {"object": "list", "data": [{"id": "mock", "object": "model"}]})
            if self.path == "/props":
                return self._send(200, {"model_path": "/models/mock.gguf", "build_info": "mock", "n_ctx": 8192})
            return self._send(404, {"error": {"message": "not found"}})

        def do_POST(self):
            length = int(self.headers.get("content-length") or 0)
            raw = self.rfile.read(length)
            if not self._authorised():
                return self._send(401, {"error": {"code": 401, "message": "Invalid API Key", "type": "authentication_error"}})
            try:
                body = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                return self._send(400, {"error": {"message": "invalid JSON"}})
            if self.path == "/v1/chat/completions":
                return self._chat(body)
            if self.path == "/v1/embeddings":
                inputs = body.get("input")
                inputs = [inputs] if isinstance(inputs, str) else inputs
                if not isinstance(inputs, list) or not all(isinstance(i, str) for i in inputs):
                    return self._send(400, {"error": {"message": "input must be a string or a list of strings"}})
                data = [{"object": "embedding", "index": i, "embedding": embed(t)} for i, t in enumerate(inputs)]
                return self._send(200, {"object": "list", "model": body.get("model"), "data": data,
                                        "usage": {"prompt_tokens": len(inputs), "total_tokens": len(inputs)}})
            return self._send(404, {"error": {"message": "not found"}})

        def _chat(self, body):
            started = time.monotonic()
            messages = body.get("messages") or []
            user = messages[-1].get("content") if messages else ""
            images = []
            if isinstance(user, list):
                images = [p["image_url"]["url"] for p in user if p.get("type") == "image_url"]
                user = "\n".join(p.get("text", "") for p in user if p.get("type") == "text")
            schema = ((body.get("response_format") or {}).get("json_schema") or {}).get("schema")
            if schema is not None and "citations" in schema.get("properties", {}):
                content = json.dumps(answer_question(user), ensure_ascii=False)
            elif schema is not None:
                match = re.search(r"<document>\n(.*)\n</document>", user, re.S)
                content = json.dumps(analyse(match.group(1) if match else user), ensure_ascii=False)
            elif images:
                data = base64.b64decode(images[0].split(",", 1)[1])
                content = state.pages.get(hashlib.sha256(data).hexdigest(), "")
            else:
                content = "OK"
            completion = max(1, len(content) // 4)
            prompt = max(1, len(json.dumps(messages)) // 4)
            seconds = time.monotonic() - started
            return self._send(200, {
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "model": body.get("model"),
                "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion},
                "timings": {"prompt_n": prompt, "prompt_ms": 1.0, "predicted_n": completion,
                            "predicted_ms": max(seconds * 1000, 1.0), "predicted_per_second": completion / max(seconds, 0.001)},
            })

    return Handler


def start(api_key, host="127.0.0.1", port=0, dataset=None, log_path=None):
    """Starts the mock in a background thread; returns (server, base_url ending in /v1)."""
    server = ThreadingHTTPServer((host, port), _handler(_State(api_key, dataset, log_path)))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, f"http://{host}:{server.server_address[1]}/v1"


def serve(args):
    api_key = os.environ.get(args.api_key_env, "").strip()
    if len(api_key) < 16:
        raise SystemExit(f"set {args.api_key_env} to the API key the mock should require (16+ characters)")
    server = ThreadingHTTPServer((args.host, args.port), _handler(_State(api_key, args.dataset, args.log)))
    print(f"mock runtime on http://{args.host}:{server.server_address[1]}/v1 (Ctrl-C stops it)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
