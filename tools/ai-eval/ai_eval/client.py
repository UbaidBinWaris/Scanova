"""OpenAI-compatible HTTP client for the evaluation (urllib, stdlib only).

The requests mirror packages/ai (temperature 0, `response_format` with a JSON
schema, images before text) so the evaluation measures what the app will send.
A fixed seed is added because llama.cpp honours it; `--extra-body` can add
runtime-specific fields for experiments.
"""

import json
import time
import urllib.error
import urllib.request

SEED = 42


class HttpError(Exception):
    def __init__(self, status, message):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status


class Endpoint:
    def __init__(self, base_url, model, api_key, timeout=600.0, extra_body=None):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.api_key = api_key
        self.timeout = timeout
        self.extra_body = dict(extra_body or {})

    @property
    def root(self):
        """Server root (llama.cpp serves /health and /props there)."""
        return self.base_url[: -len("/v1")] if self.base_url.endswith("/v1") else self.base_url

    def request(self, method, url, body=None, api_key=..., timeout=None):
        """Returns (status, parsed JSON or None, seconds). `api_key=None` sends no Authorization header."""
        key = self.api_key if api_key is ... else api_key
        headers = {"accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["content-type"] = "application/json"
        if key is not None:
            headers["authorization"] = f"Bearer {key}"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        started = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
        elapsed = time.monotonic() - started
        try:
            parsed = json.loads(raw.decode("utf-8")) if raw else None
        except (UnicodeDecodeError, json.JSONDecodeError):
            parsed = None
        return status, parsed, elapsed

    def chat(self, system, user, images=(), schema=None, schema_name="answer", max_tokens=1024):
        """One chat completion. `images` are (media_type, bytes) pairs, sent before the text."""
        import base64

        if images:
            content = [
                {"type": "image_url", "image_url": {"url": f"data:{mt};base64,{base64.b64encode(data).decode()}"}}
                for mt, data in images
            ]
            content.append({"type": "text", "text": user})
        else:
            content = user
        body = {
            "model": self.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": content}],
            "temperature": 0,
            "seed": SEED,
            "max_tokens": max_tokens,
            "stream": False,
        }
        if schema is not None:
            body["response_format"] = {
                "type": "json_schema",
                "json_schema": {"name": schema_name, "strict": True, "schema": schema},
            }
        body.update(self.extra_body)
        status, parsed, elapsed = self.request("POST", f"{self.base_url}/chat/completions", body)
        if status != 200 or not isinstance(parsed, dict):
            raise HttpError(status, _error_text(parsed))
        try:
            choice = parsed["choices"][0]
            text = choice["message"].get("content")
        except (KeyError, IndexError, TypeError, AttributeError) as error:
            raise HttpError(status, "unexpected answer shape") from error
        usage = parsed.get("usage") or {}
        return {
            "content": text if isinstance(text, str) else "",
            "finish_reason": choice.get("finish_reason"),
            "prompt_tokens": usage.get("prompt_tokens"),
            "completion_tokens": usage.get("completion_tokens"),
            "timings": parsed.get("timings") if isinstance(parsed.get("timings"), dict) else None,
            "seconds": elapsed,
        }

    def embed(self, inputs):
        body = {"model": self.model, "input": list(inputs), "encoding_format": "float"}
        status, parsed, elapsed = self.request("POST", f"{self.base_url}/embeddings", body)
        if status != 200 or not isinstance(parsed, dict) or not isinstance(parsed.get("data"), list):
            raise HttpError(status, _error_text(parsed))
        items = sorted(parsed["data"], key=lambda item: item.get("index", 0))
        if len(items) != len(inputs):
            raise HttpError(status, "wrong number of vectors")
        return [item["embedding"] for item in items], elapsed

    def props(self):
        """llama.cpp's /props (model, context size, build); None for other runtimes."""
        try:
            status, parsed, _ = self.request("GET", f"{self.root}/props", timeout=10)
        except (urllib.error.URLError, OSError):
            return None
        return parsed if status == 200 and isinstance(parsed, dict) else None

    def health(self):
        try:
            status, _, _ = self.request("GET", f"{self.root}/health", api_key=None, timeout=10)
        except (urllib.error.URLError, OSError):
            return None
        return status


def _error_text(parsed):
    if isinstance(parsed, dict):
        error = parsed.get("error")
        if isinstance(error, dict):
            return str(error.get("message", ""))[:200]
        if isinstance(error, str):
            return error[:200]
    return "no JSON error body"
