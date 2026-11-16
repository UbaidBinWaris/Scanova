"""Fonts for rendering the dataset, all SIL Open Font License, fetched from a
pinned commit of github.com/google/fonts and checked by SHA-256. They are cached
outside git (`--font-cache`), never committed."""

import hashlib
import pathlib
import urllib.request

GOOGLE_FONTS_COMMIT = "cc39c4c2c304bc368c9b7c311ea2bcc6013299fe"  # google/fonts main, 2026-11-14
_BASE = f"https://raw.githubusercontent.com/google/fonts/{GOOGLE_FONTS_COMMIT}/ofl"

FONTS = {
    "sans": ("NotoSans[wdth,wght].ttf", "notosans/NotoSans%5Bwdth,wght%5D.ttf",
             "bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d"),
    "serif": ("NotoSerif[wdth,wght].ttf", "notoserif/NotoSerif%5Bwdth,wght%5D.ttf",
              "4d8e6761424656867019081a1a01336f3cb086982682698714054fc33f782713"),
    "mono": ("NotoSansMono[wdth,wght].ttf", "notosansmono/NotoSansMono%5Bwdth,wght%5D.ttf",
             "2cb2adb378a8f574213e23df697050b83c54c27df465a2015552740b2769a081"),
    "naskh": ("NotoNaskhArabic[wght].ttf", "notonaskharabic/NotoNaskhArabic%5Bwght%5D.ttf",
              "67b5a525a661b607971fbd3f96a81b89d3a768e74534fca84f18ac97e6fab72f"),
    "nastaliq": ("NotoNastaliqUrdu[wght].ttf", "notonastaliqurdu/NotoNastaliqUrdu%5Bwght%5D.ttf",
                 "98a4787f34eb6fde57fb9a1121a8f216301196ab0da98ea110cad581be8abbcd"),
    "nastaliq-gulzar": ("Gulzar-Regular.ttf", "gulzar/Gulzar-Regular.ttf",
                        "246337973ac64e8b70fe611405dcf32a395a8885c4497dc45dbc4975d3ac0831"),
}  # fmt: skip


def _sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        for block in iter(lambda: file.read(1 << 16), b""):
            digest.update(block)
    return digest.hexdigest()


def ensure(style, cache_dir):
    """Path of the font for `style`, downloading it once. Refuses a file whose hash differs."""
    name, url_path, expected = FONTS[style]
    cache = pathlib.Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / name
    if target.exists() and _sha256(target) == expected:
        return target
    partial = target.with_suffix(target.suffix + ".partial")
    request = urllib.request.Request(f"{_BASE}/{url_path}", headers={"user-agent": "scanova-ai-eval"})
    with urllib.request.urlopen(request, timeout=60) as response, open(partial, "wb") as out:
        while block := response.read(1 << 16):
            out.write(block)
    actual = _sha256(partial)
    if actual != expected:
        partial.unlink()
        raise RuntimeError(f"font {name}: SHA-256 {actual} does not match the pinned {expected}")
    partial.replace(target)
    return target


def manifest():
    """Font provenance for the dataset manifest."""
    return {style: {"file": name, "sha256": sha, "source": f"{_BASE}/{path}"} for style, (name, path, sha) in FONTS.items()}
