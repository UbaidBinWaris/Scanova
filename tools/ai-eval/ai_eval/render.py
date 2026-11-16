"""Renders dataset pages with Pillow: a clean A4 page per document and, for
some, a "poor photo" of it (paper tint, uneven light, shadow, tilt, perspective,
blur, sensor noise, JPEG). Needs Pillow with Raqm (HarfBuzz + FriBiDi) for
Urdu shaping and right-to-left order, and fontTools to prove every character
has a glyph. Deterministic for a given seed and Pillow version."""

import random
import unicodedata

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, features

from . import fonts

PAGE = (1240, 1754)  # A4 at 150 dpi
MARGIN_X, MARGIN_TOP, MARGIN_BOTTOM = 100, 110, 90

# style: (body size, line height factor, direction)
_STYLES = {
    "sans": (27, 1.55, "ltr"),
    "serif": (27, 1.55, "ltr"),
    "mono": (25, 1.5, "ltr"),
    "naskh": (31, 1.9, "rtl"),
    "nastaliq": (31, 2.35, "rtl"),
    "nastaliq-gulzar": (31, 2.35, "rtl"),
}


class RenderError(Exception):
    pass


def check_environment(need_urdu=True):
    """Fails early, with the fix, when Urdu cannot be shaped."""
    if need_urdu and not features.check_feature("raqm"):
        raise RenderError(
            "Pillow has no Raqm layout (needs FriBiDi): Urdu would render unshaped and left-to-right. "
            "Install libfribidi (Debian: apt install libfribidi0) and run again."
        )


def _coverage(path):
    from fontTools.ttLib import TTFont

    with TTFont(str(path), lazy=True) as font:
        return set(font["cmap"].getBestCmap())


_COVERAGE = {}


def _check_glyphs(doc, style, path):
    if style not in _COVERAGE:
        _COVERAGE[style] = _coverage(path)
    # Format characters (bidi isolates) are not drawn and need no glyph.
    missing = sorted(
        {c for c in doc["text"] if not c.isspace() and unicodedata.category(c) != "Cf" and ord(c) not in _COVERAGE[style]}
    )
    if missing:
        listed = ", ".join(f"U+{ord(c):04X}" for c in missing)
        raise RenderError(f"{doc['id']}: font for style {style!r} has no glyph for {listed}")


def _font(path, size):
    return ImageFont.truetype(str(path), size, layout_engine=ImageFont.Layout.RAQM)


def render_page(doc, font_cache):
    style = doc["style"]
    path = fonts.ensure(style, font_cache)
    _check_glyphs(doc, style, path)
    size, spacing, direction = _STYLES[style]
    lines = doc["text"].split("\n")
    language = "ur" if direction == "rtl" else "en"
    width = PAGE[0] - 2 * MARGIN_X

    # Shrink until every line fits the width and the page height (no wrapping: the text is the ground truth).
    while True:
        font = _font(path, size)
        heading = _font(path, int(size * 1.35))
        widest = max(
            (heading if i == 0 else font).getlength(line, direction=direction, language=language)
            for i, line in enumerate(lines)
        )
        height = MARGIN_TOP + (len(lines) + 1) * size * spacing
        if (widest <= width and height <= PAGE[1] - MARGIN_BOTTOM) or size <= 18:
            break
        size -= 1
    if widest > width or height > PAGE[1] - MARGIN_BOTTOM:
        raise RenderError(f"{doc['id']}: text does not fit on one page")

    page = Image.new("L", PAGE, 255)
    draw = ImageDraw.Draw(page)
    y = MARGIN_TOP
    for i, line in enumerate(lines):
        face = heading if i == 0 else font
        if line:
            if direction == "rtl":
                draw.text((PAGE[0] - MARGIN_X, y), line, font=face, fill=0, anchor="ra", direction="rtl", language="ur")
            else:
                draw.text((MARGIN_X, y), line, font=face, fill=0, anchor="la", language="en")
        y += int((size * 1.35 if i == 0 else size) * spacing)
    return page


def photo(page, seed):
    """A phone photo of the printed page, in RGB."""
    rng = random.Random(seed)
    w, h = page.size
    rgb = Image.merge("RGB", [page] * 3)
    rgb = ImageChops.multiply(rgb, Image.new("RGB", (w, h), (238, 231, 212)))

    # Uneven light: a linear gradient at a random angle, 60–100 % brightness.
    gradient = Image.linear_gradient("L").resize((w * 2, h * 2)).rotate(rng.uniform(0, 360))
    gradient = gradient.crop((w // 2, h // 2, w // 2 + w, h // 2 + h)).point(lambda v: 153 + v * 102 // 255)
    rgb = ImageChops.multiply(rgb, Image.merge("RGB", [gradient] * 3))

    # A soft shadow from one corner (a hand or the phone).
    shadow = Image.new("L", (w, h), 255)
    cx, cy = rng.choice([(0, 0), (w, 0), (0, h), (w, h)])
    r = int(min(w, h) * rng.uniform(0.35, 0.55))
    ImageDraw.Draw(shadow).ellipse((cx - r, cy - r, cx + r, cy + r), fill=rng.randint(120, 170))
    shadow = shadow.filter(ImageFilter.GaussianBlur(110))
    rgb = ImageChops.multiply(rgb, Image.merge("RGB", [shadow] * 3))

    # Perspective (corners moved up to 3 %) and a tilt, on a dark table.
    table = (56, 52, 48)
    j = lambda span: rng.uniform(-0.03, 0.03) * span  # noqa: E731
    quad = (j(w), j(h), j(w), h + j(h), w + j(w), h + j(h), w + j(w), j(h))
    rgb = rgb.transform((w, h), Image.Transform.QUAD, quad, resample=Image.Resampling.BICUBIC, fillcolor=table)
    rgb = rgb.rotate(rng.uniform(-3.5, 3.5), resample=Image.Resampling.BICUBIC, expand=True, fillcolor=table)

    # Optics and sensor: blur, then noise.
    rgb = rgb.filter(ImageFilter.GaussianBlur(rng.uniform(0.8, 1.4)))
    noise = Image.frombytes("L", rgb.size, rng.randbytes(rgb.width * rgb.height))
    rgb = Image.blend(rgb, Image.merge("RGB", [noise] * 3), 0.07)

    scale = rng.uniform(0.78, 0.9)
    return rgb.resize((int(rgb.width * scale), int(rgb.height * scale)), Image.Resampling.LANCZOS)


def render_dataset(docs, image_dir, font_cache, seed, progress=None):
    """Writes images for every renderable document and records their file names in `doc["images"]`."""
    check_environment(any(d["lang"] == "ur" and d["render"] for d in docs))
    image_dir.mkdir(parents=True, exist_ok=True)
    for n, doc in enumerate(docs):
        doc["images"] = []
        if not doc["render"]:
            continue
        page = render_page(doc, font_cache)
        clean = f"{doc['id']}.png"
        page.save(image_dir / clean, optimize=True)
        doc["images"].append({"file": f"images/{clean}", "variant": "clean"})
        if doc["photo"]:
            shot = f"{doc['id']}-photo.jpg"
            photo(page, f"{seed}:{doc['id']}").save(image_dir / shot, quality=55, optimize=True)
            doc["images"].append({"file": f"images/{shot}", "variant": "photo"})
        if progress is not None:
            progress(n + 1, len(docs))
