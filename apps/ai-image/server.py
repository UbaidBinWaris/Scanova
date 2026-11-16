"""Scanova's image AI runtime: server-side page clean-up (docs/ARCHITECTURE.md §11.8).

The model is GCDRNet (Zhang et al., IEEE TAI 2023; MIT), which removes shadows,
colour casts and bleed-through from a flattened page (docs/ai-image-research.md).

It works like llama.cpp's router, so the API controls it the same way: this
process holds no model. ``POST /models/load`` starts a child process that loads
the model with ONNX Runtime, ``POST /models/unload`` ends that child, and with
it every byte of its memory goes back to the server. An owner switches it on in
the app; it turns itself off 15 minutes after their app closes.

    GET  /health                  {"status": "ok"}                      (no key)
    GET  /models                  {"data": [{"id", "status": {"value", "failed"}}]}
    POST /models/load   {"model"} starts loading; 200 at once
    POST /models/unload {"model"} ends the child; 200
    POST /v1/images/enhance       a JPEG or PNG page in, the cleaned JPEG out
                                  (409 while the model is not loaded)

Every endpoint except /health needs ``Authorization: Bearer <key>`` (the key
file shared with the other AI runtimes). One page at a time; requests while one
is being cleaned wait for it. Nothing about a page's content is ever logged.

Standard library plus ONNX Runtime, NumPy and Pillow (requirements.txt, pinned
with hashes). Run: ``python3 -I server.py --api-key-file FILE [--port 8080]``.
"""

from __future__ import annotations

import argparse
import hmac
import io
import json
import logging
import multiprocessing as mp
import os
import signal
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

LOG = logging.getLogger("ai-image")

# Largest request body and page accepted: the app sends pages of at most 2400 px
# on the longer side as JPEG (a few MB). The pixel cap also bounds the model's
# memory (2400 x 1600 needs about 2.2 GB).
MAX_BODY_BYTES = int(os.environ.get("AI_IMAGE_MAX_BODY_BYTES", str(16 * 1024 * 1024)))
MAX_LONG_SIDE = int(os.environ.get("AI_IMAGE_MAX_LONG_SIDE", "2600"))
MAX_PIXELS = int(os.environ.get("AI_IMAGE_MAX_PIXELS", str(2600 * 2000)))
JPEG_QUALITY = 92
# The model's encoder halves the page five times: sides must be multiples of 32.
STRIDE = 32
LOAD_TIMEOUT_S = 300
ENHANCE_TIMEOUT_S = 600


# ── The model, in the child process ──────────────────────────────────────────


def _clean(session: Any, page_bytes: bytes) -> bytes:
    """One page: decode, run GCDRNet as its authors do (infer.py), encode as JPEG."""
    import numpy as np
    from PIL import Image

    Image.MAX_IMAGE_PIXELS = MAX_PIXELS  # Pillow refuses decompression bombs above this
    try:
        with Image.open(io.BytesIO(page_bytes)) as image:
            if image.format not in ("JPEG", "PNG"):
                raise ValueError("unsupported")
            width, height = image.size
            if max(width, height) > MAX_LONG_SIDE or width * height > MAX_PIXELS or min(width, height) < 8:
                raise ValueError("size")
            rgb = np.asarray(image.convert("RGB"), dtype=np.uint8)
    except (OSError, SyntaxError, Image.DecompressionBombError) as error:
        # Not an image Pillow can read (or a decompression bomb): the request's fault.
        raise ValueError("unreadable") from error
    # The authors' input: BGR scaled to 0..1, padded at the top and left to multiples
    # of 32 by repeating the edge (stride_integral), the model at full resolution.
    bgr = rgb[:, :, ::-1].astype(np.float32) / 255.0
    pad_h = (STRIDE - height % STRIDE) % STRIDE
    pad_w = (STRIDE - width % STRIDE) % STRIDE
    padded = np.pad(bgr, ((pad_h, 0), (pad_w, 0), (0, 0)), mode="edge")
    tensor = np.ascontiguousarray(padded.transpose(2, 0, 1)[None])
    output = session.run(None, {"image": tensor})[0][0]
    cleaned = np.clip(output.transpose(1, 2, 0)[pad_h:, pad_w:], 0.0, 1.0)
    out_rgb = np.rint(cleaned[:, :, ::-1] * 255.0).astype(np.uint8)
    buffer = io.BytesIO()
    Image.fromarray(out_rgb, "RGB").save(buffer, format="JPEG", quality=JPEG_QUALITY)
    return buffer.getvalue()


def _model_process(conn: Any, model_path: str, threads: int) -> None:
    """The child: loads the model, then cleans pages sent over [conn] until told to stop."""
    try:
        import onnxruntime as ort

        # ONNX Runtime ships a telemetry module; the container has no route out anyway,
        # and a document runtime keeps it off on principle.
        ort.disable_telemetry_events()
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        session = ort.InferenceSession(model_path, options, providers=["CPUExecutionProvider"])
        conn.send(("ready", None))
    except Exception as error:  # noqa: BLE001 - any failure means "failed to load"
        conn.send(("failed", type(error).__name__))
        return
    while True:
        try:
            request = conn.recv()
        except EOFError:
            return
        if request is None:
            return
        try:
            conn.send(("ok", _clean(session, request)))
        except ValueError as error:
            conn.send(("invalid", str(error)))
        except Exception as error:  # noqa: BLE001 - reported, never echoed with content
            conn.send(("error", type(error).__name__))


# ── The router, in the server process ────────────────────────────────────────


class Router:
    """One model's lifecycle: unloaded → loading → loaded (or failed), and back."""

    def __init__(self, name: str, model_path: str, threads: int) -> None:
        self.name = name
        self.model_path = model_path
        self.threads = threads
        self._lock = threading.Lock()  # guards the fields below
        self._work = threading.Lock()  # one page (or load/unload) at a time through the pipe
        self._state = "unloaded"
        self._failed = False
        self._process: Any = None
        self._conn: Any = None
        self._context = mp.get_context("spawn")

    def status(self) -> dict[str, Any]:
        with self._lock:
            return {"id": self.name, "status": {"value": self._state, "failed": self._failed}}

    def load(self) -> None:
        with self._lock:
            if self._state in ("loading", "loaded"):
                return
            self._state = "loading"
            self._failed = False
        threading.Thread(target=self._start, name="load", daemon=True).start()

    def _start(self) -> None:
        with self._work:
            parent, child = self._context.Pipe()
            process = self._context.Process(
                target=_model_process, args=(child, self.model_path, self.threads), daemon=True
            )
            process.start()
            child.close()
            started = time.monotonic()
            ready = parent.poll(LOAD_TIMEOUT_S) and parent.recv()[0] == "ready"
            with self._lock:
                if self._state != "loading" or not ready:
                    # Unloaded meanwhile, or the model did not start.
                    failed = self._state == "loading"
                    self._state = "unloaded"
                    self._failed = failed
                    _end(process, parent)
                    if failed:
                        LOG.warning("load_failed")
                    return
                self._process, self._conn, self._state = process, parent, "loaded"
            LOG.info("loaded seconds=%.1f", time.monotonic() - started)

    def unload(self) -> None:
        with self._lock:
            process, conn = self._process, self._conn
            self._process, self._conn = None, None
            self._state, self._failed = "unloaded", False
        if process is not None:
            _end(process, conn)
            LOG.info("unloaded")

    def enhance(self, page: bytes) -> tuple[str, bytes | str | None]:
        """("ok", jpeg) | ("not_loaded", None) | ("invalid", reason) | ("error", kind)."""
        with self._work:
            with self._lock:
                conn = self._conn if self._state == "loaded" else None
            if conn is None:
                return "not_loaded", None
            started = time.monotonic()
            try:
                conn.send(page)
                if not conn.poll(ENHANCE_TIMEOUT_S):
                    self.unload()
                    return "error", "timeout"
                kind, value = conn.recv()
            except (EOFError, OSError):
                # The child died (out of memory, say): the model is not loaded any more,
                # and the list says it failed, so the API reports it and does not reload it.
                self.unload()
                with self._lock:
                    self._failed = True
                LOG.warning("model_process_ended")
                return "error", "crashed"
            if kind == "ok":
                LOG.info("enhanced seconds=%.1f bytes_out=%d", time.monotonic() - started, len(value))
            return kind, value


def _end(process: Any, conn: Any) -> None:
    try:
        if conn is not None:
            try:
                conn.send(None)
            except OSError:
                pass  # the child is already gone
            conn.close()
    finally:
        process.join(timeout=5)
        if process.is_alive():
            process.kill()
            process.join(timeout=5)


class Handler(BaseHTTPRequestHandler):
    server_version = "scanova-ai-image"
    sys_version = ""
    router: Router
    api_key: bytes

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 - stdlib signature
        # Paths only, never bodies; health checks are not worth a line.
        if self.path != "/health":
            LOG.info("%s %s %s", self.command, self.path.split("?")[0], args[1] if len(args) > 1 else "")

    def _authorised(self) -> bool:
        header = self.headers.get("Authorization", "")
        expected = b"Bearer " + self.api_key
        return hmac.compare_digest(header.encode("utf-8", "replace"), expected)

    def _send(self, status: HTTPStatus, body: bytes = b"", content_type: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _json(self, status: HTTPStatus, value: Any) -> None:
        self._send(status, json.dumps(value).encode())

    def _body(self, limit: int) -> bytes | None:
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self._json(HTTPStatus.LENGTH_REQUIRED, {"error": "length_required"})
            return None
        if length < 0 or length > limit:
            self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "too_large"})
            self.close_connection = True
            return None
        return self.rfile.read(length)

    def _model_name(self) -> str | None:
        body = self._body(4096)
        if body is None:
            return None
        try:
            name = json.loads(body or b"{}").get("model")
        except (ValueError, AttributeError):
            name = None
        if name != self.router.name:
            self._json(HTTPStatus.NOT_FOUND, {"error": "unknown_model"})
            return None
        return name

    def do_GET(self) -> None:  # noqa: N802 - stdlib name
        if self.path == "/health":
            self._json(HTTPStatus.OK, {"status": "ok"})
        elif not self._authorised():
            self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
        elif self.path == "/models":
            self._json(HTTPStatus.OK, {"data": [self.router.status()]})
        else:
            self._json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802 - stdlib name
        if not self._authorised():
            self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            self.close_connection = True
            return
        if self.path == "/models/load":
            if self._model_name() is not None:
                self.router.load()
                self._json(HTTPStatus.OK, {"success": True})
        elif self.path == "/models/unload":
            if self._model_name() is not None:
                self.router.unload()
                self._json(HTTPStatus.OK, {"success": True})
        elif self.path == "/v1/images/enhance":
            self._enhance()
        else:
            self._json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def _enhance(self) -> None:
        if self.headers.get_content_type() not in ("image/jpeg", "image/png"):
            self._json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "unsupported_media_type"})
            self.close_connection = True
            return
        page = self._body(MAX_BODY_BYTES)
        if page is None:
            return
        kind, value = self.router.enhance(page)
        if kind == "ok" and isinstance(value, bytes):
            self._send(HTTPStatus.OK, value, "image/jpeg")
        elif kind == "not_loaded":
            self._json(HTTPStatus.CONFLICT, {"error": "not_loaded"})
        elif kind == "invalid":
            self._json(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": "invalid_image"})
        else:
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "failed"})


def serve(port: int, api_key: bytes, router: Router) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {"router": router, "api_key": api_key})
    server = ThreadingHTTPServer(("0.0.0.0", port), handler)  # noqa: S104 - container network only
    server.daemon_threads = True
    return server


def read_key(path: str) -> bytes:
    with open(path, "rb") as file:
        key = file.readline().strip()
    if len(key) < 32:
        raise SystemExit("the API key must be at least 32 characters")
    return key


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api-key-file", required=True)
    parser.add_argument("--port", type=int, default=int(os.environ.get("AI_IMAGE_PORT", "8080")))
    parser.add_argument("--model", default=os.environ.get("AI_IMAGE_MODEL_PATH", "/app/model/gcdrnet.onnx"))
    parser.add_argument("--name", default=os.environ.get("AI_IMAGE_MODEL_NAME", "gcdrnet"))
    parser.add_argument("--threads", type=int, default=int(os.environ.get("AI_IMAGE_THREADS", "4")))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s", stream=sys.stderr)
    router = Router(args.name, args.model, args.threads)
    server = serve(args.port, read_key(args.api_key_file), router)

    def stop(*_: Any) -> None:
        router.unload()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    LOG.info("listening port=%d model=%s (not loaded)", args.port, args.name)
    server.serve_forever()


if __name__ == "__main__":
    main()
