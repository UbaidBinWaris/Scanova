"""Tests of the image AI runtime, against the real model (model/gcdrnet.onnx).

    uv run --no-project --with-requirements apps/ai-image/requirements.txt \
        python3 -I -m unittest discover -s apps/ai-image -p 'test_*.py'
"""

import hashlib
import io
import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import server  # noqa: E402

KEY = "k" * 48
MODEL = HERE / "model" / "gcdrnet.onnx"


def page(width: int = 96, height: int = 64, kind: str = "JPEG") -> bytes:
    """A grey page with a dark band of 'text' and a shadowed corner."""
    from PIL import Image, ImageDraw

    image = Image.new("RGB", (width, height), (225, 222, 215))
    draw = ImageDraw.Draw(image)
    draw.rectangle((8, height // 3, width - 8, height // 3 + 6), fill=(30, 30, 30))
    draw.rectangle((0, 0, width // 3, height // 3), fill=(150, 145, 140))
    buffer = io.BytesIO()
    image.save(buffer, format=kind)
    return buffer.getvalue()


class RuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.router = server.Router("gcdrnet", str(MODEL), threads=2)
        cls.http = server.serve(0, KEY.encode(), cls.router)
        cls.base = f"http://127.0.0.1:{cls.http.server_address[1]}"
        threading.Thread(target=cls.http.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.router.unload()
        cls.http.shutdown()

    def call(self, method: str, path: str, body: bytes | None = None, *, key: str | None = KEY,
             content_type: str = "application/json") -> tuple[int, bytes, str]:
        request = urllib.request.Request(self.base + path, data=body, method=method)
        if key is not None:
            request.add_header("Authorization", f"Bearer {key}")
        if body is not None:
            request.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.status, response.read(), response.headers.get_content_type()
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers.get_content_type()

    def models(self) -> dict:
        status, body, _ = self.call("GET", "/models")
        self.assertEqual(200, status)
        return json.loads(body)["data"][0]

    def load(self) -> None:
        status, _, _ = self.call("POST", "/models/load", json.dumps({"model": "gcdrnet"}).encode())
        self.assertEqual(200, status)
        deadline = time.monotonic() + 120
        while self.models()["status"]["value"] != "loaded":
            self.assertLess(time.monotonic(), deadline, "the model did not load")
            time.sleep(0.2)

    def test_model_file_is_the_reviewed_export(self) -> None:
        digest = hashlib.sha256(MODEL.read_bytes()).hexdigest()
        self.assertEqual("36573a09a9e6d905e12ce7e0c71e859cd11b92aa81d9eff637c8d6f8bdeb8aeb", digest)
        self.assertIn("MIT License", (HERE / "model" / "gcdrnet-LICENSE.txt").read_text())
        self.assertIn("MIT License", (HERE / "model" / "unext-LICENSE.txt").read_text())

    def test_health_is_open_everything_else_needs_the_key(self) -> None:
        self.assertEqual(200, self.call("GET", "/health", key=None)[0])
        for method, path in (("GET", "/models"), ("POST", "/models/load"), ("POST", "/v1/images/enhance")):
            self.assertEqual(401, self.call(method, path, b"{}", key=None)[0], path)
            self.assertEqual(401, self.call(method, path, b"{}", key="x" * 48)[0], path)

    def test_load_clean_unload(self) -> None:
        self.assertIn(self.models()["status"]["value"], ("unloaded", "loaded"))
        self.router.unload()
        # Not loaded: refused, nothing processed.
        self.assertEqual(409, self.call("POST", "/v1/images/enhance", page(), content_type="image/jpeg")[0])

        self.load()
        child = self.router._process
        self.assertTrue(child is not None and child.is_alive())
        for kind, mime in (("JPEG", "image/jpeg"), ("PNG", "image/png")):
            status, body, content_type = self.call("POST", "/v1/images/enhance", page(97, 65, kind), content_type=mime)
            self.assertEqual(200, status)
            self.assertEqual("image/jpeg", content_type)
            from PIL import Image

            with Image.open(io.BytesIO(body)) as cleaned:
                # Same size as sent (padding to multiples of 32 removed again).
                self.assertEqual((97, 65), cleaned.size)

        status, _, _ = self.call("POST", "/models/unload", json.dumps({"model": "gcdrnet"}).encode())
        self.assertEqual(200, status)
        self.assertEqual("unloaded", self.models()["status"]["value"])
        child.join(timeout=10)
        self.assertFalse(child.is_alive(), "the model's process must end, giving its memory back")

    def test_refuses_what_is_not_a_page(self) -> None:
        self.load()
        self.assertEqual(415, self.call("POST", "/v1/images/enhance", b"hello", content_type="text/plain")[0])
        self.assertEqual(422, self.call("POST", "/v1/images/enhance", b"not a jpeg", content_type="image/jpeg")[0])
        huge = page(server.MAX_LONG_SIDE + 1, 40)
        self.assertEqual(422, self.call("POST", "/v1/images/enhance", huge, content_type="image/jpeg")[0])
        self.assertEqual(404, self.call("POST", "/models/load", json.dumps({"model": "other"}).encode())[0])
        # Still serving after bad input.
        self.assertEqual(200, self.call("POST", "/v1/images/enhance", page(), content_type="image/jpeg")[0])

    def test_refuses_bodies_over_the_limit_before_reading_them(self) -> None:
        saved = server.MAX_BODY_BYTES
        server.MAX_BODY_BYTES = 1024
        try:
            self.assertEqual(413, self.call("POST", "/v1/images/enhance", b"x" * 2048, content_type="image/jpeg")[0])
        finally:
            server.MAX_BODY_BYTES = saved


if __name__ == "__main__":
    unittest.main()
