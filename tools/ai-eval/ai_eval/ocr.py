"""OCR engines compared by tests 5 and 6.

    vlm          the chat model's vision input (llama.cpp `--mmproj`)
    tesseract    `tesseract stdin stdout -l <eng|urd>` (the worker image has both packs)
    cmd:<cmd>    any command that reads an image on stdin and prints text, `{lang}`
                 replaced by eng or urd, e.g. a PaddleOCR container
"""

import shlex
import subprocess
import time

from . import prompts

TESSERACT_LANG = {"en": "eng", "ur": "urd"}


class Engine:
    name = "engine"

    def read(self, image_bytes, media_type, lang):
        """Returns (text, seconds, extra) for one page."""
        raise NotImplementedError


class VlmEngine(Engine):
    name = "vlm"

    def __init__(self, chat):
        self.chat = chat

    def read(self, image_bytes, media_type, lang):
        answer = self.chat.chat(prompts.OCR_SYSTEM, prompts.OCR_USER, images=[(media_type, image_bytes)], max_tokens=2048)
        return answer["content"], answer["seconds"], {
            "prompt_tokens": answer["prompt_tokens"],
            "completion_tokens": answer["completion_tokens"],
            "timings": answer["timings"],
            "finish_reason": answer["finish_reason"],
        }


class CommandEngine(Engine):
    def __init__(self, name, template, timeout=300):
        self.name = name
        self.template = template
        self.timeout = timeout

    def read(self, image_bytes, media_type, lang):
        command = shlex.split(self.template.replace("{lang}", TESSERACT_LANG.get(lang, "eng")))
        started = time.monotonic()
        result = subprocess.run(command, input=image_bytes, capture_output=True, timeout=self.timeout, check=False)
        elapsed = time.monotonic() - started
        if result.returncode != 0:
            raise RuntimeError(f"{self.name}: exit {result.returncode}: {result.stderr.decode(errors='replace')[:300]}")
        return result.stdout.decode("utf-8", errors="replace"), elapsed, {}


def engines_from(spec, chat):
    """Parses `--ocr`; returns (engines, notes about engines that are not available)."""
    import shutil

    engines, notes = [], []
    for item in [s.strip() for s in spec.split(",") if s.strip()]:
        if item == "vlm":
            engines.append(VlmEngine(chat))
        elif item == "tesseract":
            if shutil.which("tesseract") is None:
                notes.append("tesseract: not installed here (it is in the worker image)")
            else:
                engines.append(CommandEngine("tesseract", "tesseract stdin stdout -l {lang}"))
        elif item.startswith("cmd:"):
            engines.append(CommandEngine("cmd", item[4:]))
        else:
            notes.append(f"{item}: unknown OCR engine")
    return engines, notes
