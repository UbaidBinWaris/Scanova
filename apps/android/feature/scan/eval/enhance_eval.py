"""Reference implementation and evaluation of Scanova's document enhancement.

`DocumentEnhancer.kt` (feature/scan/filter) is a step-by-step port of
`enhance()` below; change both together. The evaluation draws clean synthetic
pages, photographs them synthetically, enhances the "photo" and scores how
close it gets to the clean page. The cases:

- "light+shadow+glare" (seeds 1-3): a certificate under a lamp on one side, a
  soft shadow, a reflection, a colour cast, blur and sensor noise.
- "hard warm shadow" / "hard deep shadow": the same page with a red seal, and
  the hard-edged, brownish shadow of a phone and a hand across text, the round
  logo and the seal (about 45 % and 60 % darker).
- "flash glare": a small, bright flash reflection over text.
- "solid fills": a page of solid areas that must stay solid (a black bar, a
  grey box, a big logo, a red seal, a yellow note, a grey table header).
- "full-bleed photo" / "photo under shadow": the certificate with a large,
  textured, many-toned photo running off its top and right edges, without and
  with the hard hand and phone shadow; the picture must not be taken for a
  shadow and wiped out.
- "dark desk": the certificate on a dark textured desk, which must stay dark.

Scores: SSIM and PSNR of the result against the clean page, "tint99" (the
99th percentile of the colourfulness, CIELAB chroma, of pixels that are bare
paper on the clean page: a shadow turned orange shows up here), "inkdE"
(mean CIELAB colour difference inside solid coloured areas: logos and stamps
must keep their colour) and "keep" (PSNR inside the picture or desk, which
must stay as it is).

The same reference also runs on real photos (RealDAE) on the owner's bench
server; the thresholds of the shadow test were chosen there as well.

Run (no project install needed):

    uv run --no-project --with opencv-python-headless --with numpy \
        python3 -I -B apps/android/feature/scan/eval/enhance_eval.py [--all-looks] [output-dir]

It prints the scores of the "Auto" look (with --all-looks, of every look
built on the enhancement) and, with an output directory, writes the images.
A change to the enhancement should not lower the scores.
"""
import sys
import time
from pathlib import Path

import cv2
import numpy as np


# ── Helpers ──────────────────────────────────────────────────────────────────

def odd(v, minimum=3):
    v = max(int(round(v)), minimum)
    return v if v % 2 else v + 1


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def small_of(x, long_small=640):
    h, w = x.shape[:2]
    s = min(1.0, long_small / max(h, w))
    return cv2.resize(x, (max(1, round(w * s)), max(1, round(h * s))), interpolation=cv2.INTER_AREA)


def up(x, shape, interpolation=cv2.INTER_CUBIC):
    return cv2.resize(x, (shape[1], shape[0]), interpolation=interpolation)


def ellipse(size):
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (odd(size),) * 2)


def regions(mask, connectivity):
    """Connected regions of a boolean mask: labels, area per label, whether each label touches the border."""
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), connectivity=connectivity)
    border = np.zeros(n, bool)
    for edge in (labels[0], labels[-1], labels[:, 0], labels[:, -1]):
        border[edge] = True
    return labels, stats[:, cv2.CC_STAT_AREA], border


def smooth_keeping_edges(x, radius, eps):
    """A guided filter with the map as its own guide: flat parts are averaged, steps (a shadow's edge) stay sharp."""
    k = (2 * radius + 1,) * 2
    mean = cv2.boxFilter(x, -1, k)
    var = np.maximum(cv2.boxFilter(x * x, -1, k) - mean * mean, 0)
    a = var / (var + eps)
    b = mean - a * mean
    return cv2.boxFilter(a, -1, k) * x + cv2.boxFilter(b, -1, k)


# ── The enhancement (mirrors DocumentEnhancer.kt) ────────────────────────────

def noise_sigma(gray):
    """1. Noise level of the brightness: median of |pixel - 3x3 median|, as a standard deviation."""
    r = np.abs(gray - cv2.medianBlur(gray, 3))
    return float(1.4826 * 1.25 * np.median(r))


def denoise(img, sigma):
    """Colour planes always smoothed; brightness by non-local means when there is grain."""
    long_edge = max(img.shape[:2])
    ycc = cv2.cvtColor(img, cv2.COLOR_BGR2YCrCb)
    s = 1.6 * max(1.0, long_edge / 1100)
    for c in (1, 2):
        ycc[..., c] = cv2.GaussianBlur(ycc[..., c], (0, 0), s)
    if sigma > 0.006:
        y8 = (np.clip(ycc[..., 0], 0, 1) * 255).astype(np.uint8)
        h = float(np.clip(sigma * 255 * 1.1, 3, 25))
        ycc[..., 0] = cv2.fastNlMeansDenoising(y8, None, h=h, templateWindowSize=7, searchWindowSize=15) / 255.0
    return cv2.cvtColor(ycc, cv2.COLOR_YCrCb2BGR)


def is_dimmed_paper(ratio):
    """Where the paper estimate looks like paper in less light (EnhanceMath.isDimmedPaper). `ratio` is each channel
    divided by the paper nearby: every channel lowered (0.25..0.90), by factors within e^0.6 of each other, and
    green between red and blue (light changes colour smoothly with wavelength: a warm or a cool shadow)."""
    b, g, r = ratio[..., 0], ratio[..., 1], ratio[..., 2]
    lo, hi = ratio.min(-1), ratio.max(-1)
    rb_lo, rb_hi = np.minimum(r, b), np.maximum(r, b)
    return (lo >= 0.25) & (hi <= 0.90) & (hi <= lo * np.exp(0.6)) & (g >= rb_lo / 1.05) & (g <= rb_hi * 1.05)


def mad(v):
    return float(1.4826 * np.median(np.abs(v - np.median(v))))


def shadow_regions(candidates, closed, calm):
    """Shadows among the candidate pixels (far below the paper nearby, and dimmed paper): see EnhanceMath.isShadow.

    A region is a shadow when it reaches the page's edge (a hand or a phone casts it from outside the page), is
    large, and inside (away from its own edge) is as smooth and evenly coloured as paper: half of it changes by at
    most 0.07 % of its brightness per pixel and 75 % by at most 0.3 %, and its hue varies by at most 0.03 (log
    units, robust spread). A photo, a printed colour area or a textured desk has edges, many tones and varying
    hue, and is left alone: a shadow left in place is better than a picture wiped out.
    """
    sl = max(candidates.shape)
    labels, area, border = regions(candidates, 8)
    lum = cv2.cvtColor(closed, cv2.COLOR_BGR2GRAY)
    gx = cv2.Sobel(lum, cv2.CV_32F, 1, 0, ksize=3, scale=1 / 8)
    gy = cv2.Sobel(lum, cv2.CV_32F, 0, 1, ksize=3, scale=1 / 8)
    gradient = np.hypot(gx, gy) / np.maximum(lum, 0.05)
    lc = np.log(np.maximum(calm, 1e-3))
    hue_a, hue_b = lc[..., 2] - lc[..., 1], lc[..., 0] - lc[..., 1]
    interior = cv2.erode(candidates.astype(np.uint8), ellipse(2 * max(1, round(sl / 80)) + 1)) > 0
    shadow = np.zeros(len(area), bool)
    for k in range(1, len(area)):
        if not border[k] or area[k] < (sl / 16) ** 2:
            continue
        inside = interior & (labels == k)
        if inside.sum() < 200:
            continue
        smooth = np.percentile(gradient[inside], 50) <= 0.0007 and np.percentile(gradient[inside], 75) <= 0.003
        even = np.hypot(mad(hue_a[inside]), mad(hue_b[inside])) <= 0.03
        shadow[k] = smooth and even
    return shadow[labels]


def backgrounds(den):
    """2. The bare paper in each channel, at the small working size, and where solid areas are."""
    small = small_of(den)
    sl = max(small.shape[:2])
    # Ink removed by a closing; a slight dilation puts the estimate at the paper's top, above its noise.
    sharp = np.stack([cv2.morphologyEx(small[..., c], cv2.MORPH_CLOSE, ellipse(sl / 40)) for c in range(3)], -1)
    closed = np.stack([cv2.dilate(sharp[..., c], ellipse(sl / 90)) for c in range(3)], -1)
    # The paper around each pixel (from a slightly smoothed copy, so noise does not lift it).
    calm = cv2.GaussianBlur(closed, (0, 0), sl / 160)
    env = np.stack([cv2.GaussianBlur(cv2.dilate(calm[..., c], ellipse(sl / 12)), (0, 0), sl / 30) for c in range(3)], -1)
    # Solid: far below that paper in some channel; except shadows, which are evened out like the rest of the light.
    far_below = (closed < 0.72 * env).any(-1)
    dimmed = is_dimmed_paper(calm / np.maximum(env, 1e-3))
    shadow = shadow_regions(far_below & dimmed, closed, calm)
    solid = far_below & ~shadow
    # Solid areas take the paper from around them (normalised convolution), the same in every channel.
    valid = (~solid).astype(np.float32)
    weight = np.maximum(cv2.GaussianBlur(valid, (0, 0), sl / 25), 1e-3)

    def fill(bg):
        filled = cv2.GaussianBlur(bg * valid, (0, 0), sl / 25) / weight
        return np.where(solid, filled, bg).astype(np.float32)

    def median8(bg, size):
        return cv2.medianBlur((np.clip(bg, 0, 1) * 255).astype(np.uint8), odd(size)).astype(np.float32) / 255

    # Near a shadow the estimate must keep the shadow's edge: no dilation, a small median, an edge-keeping smoothing.
    near = None
    if shadow.any():
        near = cv2.GaussianBlur(cv2.dilate(shadow.astype(np.float32), ellipse(sl / 16)), (0, 0), sl / 120)
    bgs = []
    for c in range(3):
        bg = cv2.GaussianBlur(median8(fill(closed[..., c]), sl / 30), (0, 0), sl / 120)
        if near is not None:
            edge = smooth_keeping_edges(median8(fill(sharp[..., c]), sl / 60), max(1, round(sl / 80)), 1e-4)
            bg = near * edge + (1 - near) * bg
        bgs.append(np.maximum(up(bg, den.shape[:2]), 0.08))
    return bgs, solid.astype(np.float32)


def glare_map(bgs):
    """3. Reflections: paper brighter than the paper around it (an evenly lit side, or the lit side of a shadow, is not one)."""
    bl = cv2.cvtColor(np.stack([small_of(b) for b in bgs], -1), cv2.COLOR_BGR2GRAY)
    sl = max(bl.shape)
    around = cv2.GaussianBlur(bl, (0, 0), sl / 10)
    # An opening keeps large lit areas and removes compact bright spots: next to a shadow it is the lit paper.
    rect = cv2.getStructuringElement(cv2.MORPH_RECT, (odd(sl / 4),) * 2)
    around = np.maximum(around, cv2.GaussianBlur(cv2.morphologyEx(bl, cv2.MORPH_OPEN, rect), (0, 0), sl / 30))
    g = smoothstep(1.025, 1.10, bl / np.maximum(around, 1e-3)).astype(np.float32)
    g = cv2.dilate(g, ellipse(sl / 40))
    g = cv2.GaussianBlur(g, (0, 0), sl / 120)
    return np.clip(up(g, bgs[0].shape, cv2.INTER_LINEAR), 0, 1)


def paper_stats(gray_small):
    paper = gray_small[gray_small > 0.72]
    if paper.size < 100:
        paper = gray_small.ravel()
    level = float(np.median(paper))
    spread = float(1.4826 * np.median(np.abs(paper - level)))
    return level, spread


def is_speck(area, darkest, glare, long_edge):
    unit = (long_edge / 1000.0) ** 2
    return (area < 3 * unit) | ((area < 40 * unit) & (darkest > 0.62)) | ((glare > 0.35) & (area < 80 * unit) & (darkest > 0.55))


def despeckle(out, glare):
    """6. Small, faint, isolated dots become paper; strokes are larger or darker."""
    long_edge = max(out.shape[:2])
    lum = cv2.cvtColor((out * 255).astype(np.uint8), cv2.COLOR_BGR2GRAY)
    n, labels, stats, _ = cv2.connectedComponentsWithStats((lum <= 244).astype(np.uint8), connectivity=8)
    if n <= 1:
        return out
    darkest = np.full(n, 255.0)
    np.minimum.at(darkest, labels.ravel(), lum.ravel().astype(np.float64))
    near = np.zeros(n)
    np.maximum.at(near, labels.ravel(), glare.ravel())
    drop = is_speck(stats[:, cv2.CC_STAT_AREA], darkest / 255, near, long_edge)
    drop[0] = False
    out = out.copy()
    out[drop[labels]] = 1.0
    return out


STYLES = {"natural": (0.05, 1.04), "vivid": (0.10, 1.12), "even": (0.0, 1.0)}


def enhance(bgr, style="natural"):
    img = bgr.astype(np.float32) / 255
    black, gamma = STYLES[style]
    sigma = noise_sigma(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY))
    den = denoise(img, sigma)
    bgs, solid = backgrounds(den)
    norm = np.stack([np.minimum(den[..., c] / bgs[c], 1.25) for c in range(3)], -1)
    glare = glare_map(bgs)
    gray = cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY)
    gs = small_of(gray)
    level, spread = paper_stats(gs)
    # 4. Faint ink: stretched back where it stands clearly above the paper's noise, not inside solid areas.
    sl = max(gs.shape)
    ink = cv2.GaussianBlur(gs, (0, 0), max(0.6, sl / 900))
    ink = cv2.erode(ink, ellipse(sl / 25))
    ink = cv2.GaussianBlur(ink, (0, 0), sl / 60)
    depth = np.clip(level - ink, 0, 1)
    gain = np.clip(0.50 / np.maximum(depth, 1e-3), 1.0, 3.0)
    gain = 1.0 + (gain - 1.0) * smoothstep(max(0.06, 5 * spread), max(0.16, 9 * spread), depth)
    gain = 1.0 + (gain - 1.0) * (1 - cv2.GaussianBlur(cv2.dilate(solid, ellipse(sl / 60)), (0, 0), sl / 120))
    gain = np.maximum(up(gain.astype(np.float32), gray.shape), 1.0)
    # Brightness only (the same change in every channel), so the stretch never creates or amplifies colour.
    norm = norm + ((gray - level) * (gain - 1))[..., None]
    # 5. Tone: paper within ~3 spreads of white becomes white; whiter inside reflections.
    white = float(np.clip(level - 3.0 * spread, 0.70, 0.97))
    out = np.clip((norm - black) / ((white - 0.16 * glare)[..., None] - black), 0, 1) ** gamma
    out = despeckle(out.astype(np.float32), glare)
    return out, glare


# ── The looks built on it (FilterEngine) ─────────────────────────────────────

def finish_colour(out, glare, saturation=1.06, sharpen=0.35):
    """A colour look's last steps (FilterEngine.colourPage); the defaults are 'Auto'."""
    rgb8 = (out * 255 + 0.5).astype(np.uint8)
    hsv = cv2.cvtColor(rgb8, cv2.COLOR_BGR2HSV).astype(np.float32)
    s = hsv[..., 1] / 255
    v = hsv[..., 2] / 255
    # A reflection has no colour of its own: drop the tint it left on paper, never on ink.
    s = np.clip(s * (1 + (saturation - 1) * smoothstep(0.12, 0.30, s)), 0, 1) * (1 - glare * smoothstep(0.80, 0.97, v))
    hsv[..., 1] = s * 255
    rgb8 = cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2BGR)
    if sharpen <= 0:
        return rgb8
    sigma = max(0.8, max(rgb8.shape[:2]) / 2400)
    blur = cv2.GaussianBlur(rgb8, (0, 0), sigma)
    return cv2.addWeighted(rgb8, 1 + sharpen, blur, -sharpen, 0)


def grey_page(out):
    return cv2.cvtColor((out * 255 + 0.5).astype(np.uint8), cv2.COLOR_BGR2GRAY)


def black_white(gray, sharp):
    """FilterEngine.blackWhite: Otsu's threshold within bounds, then dots removed."""
    if sharp:
        gray = cv2.addWeighted(gray, 1.5, cv2.GaussianBlur(gray, (0, 0), 1.0), -0.5, 0)
    otsu, _ = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    _, bw = cv2.threshold(gray, float(np.clip(otsu, 120, 215 if sharp else 205)), 255, cv2.THRESH_BINARY)
    n, labels, stats, _ = cv2.connectedComponentsWithStats((bw <= 127).astype(np.uint8), connectivity=8)
    drop = stats[:, cv2.CC_STAT_AREA] <= int(6 * (max(bw.shape) / 1000) ** 2)
    drop[0] = False
    bw[drop[labels]] = 255
    return bw


LIGHTEN = np.array([round(255 * (i / 255) ** 0.72) for i in range(256)], np.uint8)
INK_SAVER = np.array([255 if i >= 228 else round(255 - (255 - i) * 0.55) for i in range(256)], np.uint8)
COLOUR_LOOKS = ("auto", "magic", "no_shadow", "lighten", "sharpen")


def looks(photo, only_auto=False):
    natural = enhance(photo, "natural")
    if only_auto:
        return {"auto": finish_colour(*natural)}
    vivid, even = enhance(photo, "vivid"), enhance(photo, "even")
    bgr = lambda g: cv2.cvtColor(g, cv2.COLOR_GRAY2BGR)
    return {
        "auto": finish_colour(*natural),
        "magic": finish_colour(*vivid, saturation=1.22),
        "no_shadow": finish_colour(*even, saturation=1.0, sharpen=0),
        "lighten": cv2.LUT(finish_colour(*even, saturation=1.0, sharpen=0), LIGHTEN),
        "sharpen": finish_colour(*natural, sharpen=0.9),
        "greyscale": bgr(grey_page(natural[0])),
        "ink_saver": bgr(cv2.LUT(grey_page(natural[0]), INK_SAVER)),
        "black_white": bgr(black_white(grey_page(natural[0]), sharp=False)),
        "bw_sharp": bgr(black_white(grey_page(vivid[0]), sharp=True)),
    }


# ── Evaluation ───────────────────────────────────────────────────────────────

RED = (40, 40, 200)


def clean_page(w=1700, h=1200, seal=False):
    """A certificate-like page: coloured headings, body text, rules, ornaments, a round logo (and a red seal)."""
    page = np.full((h, w, 3), 255, np.uint8)
    green, blue, ink = (60, 110, 40), (200, 90, 30), (40, 40, 40)
    cv2.rectangle(page, (40, 40), (w - 40, h - 40), (190, 120, 60), 3)
    cv2.rectangle(page, (58, 58), (w - 58, h - 58), (120, 180, 220), 1)
    for x, y in ((70, 70), (w - 70, 70), (70, h - 70), (w - 70, h - 70)):
        for r in (14, 24, 34):
            cv2.circle(page, (x, y), r, (200, 140, 80), 2)
    cv2.putText(page, "80th Independence Day Celebrations", (260, 210), cv2.FONT_HERSHEY_TRIPLEX, 2.0, green, 3, cv2.LINE_AA)
    cv2.circle(page, (850, 340), 70, green, -1)
    cv2.circle(page, (850, 340), 56, (255, 255, 255), 3)
    cv2.putText(page, "14th August, 2026", (650, 470), cv2.FONT_HERSHEY_TRIPLEX, 1.3, green, 2, cv2.LINE_AA)
    cv2.putText(page, "PRESENTED TO", (710, 540), cv2.FONT_HERSHEY_SIMPLEX, 1.1, blue, 2, cv2.LINE_AA)
    cv2.putText(page, "Mr/Mrs/Miss", (180, 640), cv2.FONT_HERSHEY_SIMPLEX, 1.0, ink, 2, cv2.LINE_AA)
    cv2.line(page, (400, 640), (1500, 640), ink, 2)
    cv2.putText(page, "in recognition of participation in", (580, 720), cv2.FONT_HERSHEY_SIMPLEX, 0.9, ink, 1, cv2.LINE_AA)
    cv2.putText(page, "80th Independence Day Celebrations", (420, 800), cv2.FONT_HERSHEY_SIMPLEX, 1.4, blue, 2, cv2.LINE_AA)
    cv2.putText(page, "Participated in", (180, 900), cv2.FONT_HERSHEY_SIMPLEX, 1.0, ink, 2, cv2.LINE_AA)
    cv2.line(page, (440, 900), (1000, 900), ink, 2)
    cv2.putText(page, "at", (1030, 900), cv2.FONT_HERSHEY_SIMPLEX, 1.0, ink, 2, cv2.LINE_AA)
    cv2.line(page, (1080, 900), (1500, 900), ink, 2)
    cv2.putText(page, "Pakistan Sports Board", (650, 1000), cv2.FONT_HERSHEY_SIMPLEX, 1.1, green, 2, cv2.LINE_AA)
    for i in range(12):
        cv2.ellipse(page, (300 + i * 18, 1080), (8, 14), i * 15, 0, 300, (60, 160, 200), 1, cv2.LINE_AA)
    cv2.putText(page, "DY. DIRECTOR GENERAL (FACILITIES)", (1130, 1090), cv2.FONT_HERSHEY_SIMPLEX, 0.5, blue, 1, cv2.LINE_AA)
    if seal:
        c = (1330, 1000)
        cv2.circle(page, c, 82, RED, 6, cv2.LINE_AA)
        cv2.circle(page, c, 68, RED, 2, cv2.LINE_AA)
        cv2.circle(page, c, 42, RED, -1, cv2.LINE_AA)
        cv2.putText(page, "OK", (c[0] - 26, c[1] + 12), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (255, 255, 255), 3, cv2.LINE_AA)
    return page


def clean_fills(w=1700, h=1200):
    """Solid areas that must stay solid: a black bar, a grey header and box, a big logo, a red seal, a yellow note."""
    page = np.full((h, w, 3), 255, np.uint8)
    ink = (40, 40, 40)
    cv2.rectangle(page, (60, 60), (w - 60, 150), (30, 30, 30), -1)
    cv2.putText(page, "QUARTERLY STATEMENT", (90, 125), cv2.FONT_HERSHEY_SIMPLEX, 1.6, (255, 255, 255), 3, cv2.LINE_AA)
    cv2.rectangle(page, (100, 220), (700, 300), (190, 190, 190), -1)
    cv2.putText(page, "Item        Qty    Price", (120, 272), cv2.FONT_HERSHEY_SIMPLEX, 1.0, ink, 2, cv2.LINE_AA)
    for i in range(6):
        cv2.putText(page, f"Line {i + 1}: sample entry text  {i * 7 + 3}  {i * 13 + 20}.00", (120, 360 + i * 50),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, ink, 2, cv2.LINE_AA)
    cv2.rectangle(page, (100, 700), (420, 1000), (90, 90, 90), -1)
    cv2.circle(page, (1200, 420), 150, (60, 120, 30), -1)
    cv2.circle(page, (1200, 420), 120, (255, 255, 255), 6)
    cv2.circle(page, (1350, 900), 110, RED, 8, cv2.LINE_AA)
    cv2.circle(page, (1350, 900), 70, RED, -1, cv2.LINE_AA)
    cv2.rectangle(page, (600, 760), (1000, 1060), (120, 230, 250), -1)
    cv2.putText(page, "Note: call back", (630, 900), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (150, 60, 30), 2, cv2.LINE_AA)
    return page


def scene(h, w, rng):
    """A photo-like picture (invented): sky, hills, khaki ground, dark figures, a dark doorway, fine grain."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)

    def noise(scale):
        return cv2.resize(rng.random((h // scale + 2, w // scale + 2)).astype(np.float32), (w, h), interpolation=cv2.INTER_CUBIC)

    sky = np.stack([0.80 - 0.20 * yy / h, 0.55 - 0.15 * yy / h, 0.30 - 0.05 * yy / h], -1)   # BGR: a deep blue
    hills = np.stack([0.18 + 0.08 * noise(30), 0.36 + 0.12 * noise(25), 0.22 + 0.08 * noise(35)], -1)
    ground = np.stack([0.32 + 0.10 * noise(20), 0.44 + 0.10 * noise(20), 0.56 + 0.12 * noise(20)], -1)  # khaki, shadow-like
    horizon = 0.35 * h + 0.08 * h * np.sin(xx / w * 7) + 0.04 * h * noise(40)
    ground_line = 0.62 * h + 0.05 * h * np.cos(xx / w * 5)
    img = np.where((yy < horizon)[..., None], sky, np.where((yy < ground_line)[..., None], hills, ground))
    for i in range(5):
        cx, base = int(w * (0.15 + 0.17 * i)), int(h * (0.95 - 0.05 * (i % 2)))
        cv2.ellipse(img, (cx, base - int(0.18 * h)), (int(0.05 * w), int(0.2 * h)), 0, 0, 360, (0.12 + 0.05 * i, 0.10, 0.10 + 0.04 * i), -1)
        cv2.circle(img, (cx, base - int(0.42 * h)), int(0.035 * w), (0.45, 0.55, 0.78), -1)
    cv2.rectangle(img, (int(0.72 * w), int(0.30 * h)), (int(0.88 * w), int(0.70 * h)), (0.08, 0.08, 0.09), -1)
    img = img * (0.92 + 0.16 * noise(6))[..., None] + rng.normal(0, 0.015, img.shape).astype(np.float32)
    return np.clip(cv2.GaussianBlur(img, (0, 0), 0.7), 0, 1)


def clean_photo_page(w=1700, h=1200):
    """The certificate with a large photo running off its top and right edges; returns the page and the photo's area."""
    page = clean_page(w, h)
    x0, y1 = int(0.42 * w), int(0.52 * h)
    page[:y1, x0:] = (scene(y1, w - x0, np.random.default_rng(5)) * 255).astype(np.uint8)
    keep = np.zeros((h, w), bool)
    keep[:y1, x0:] = True
    return page, keep


def clean_desk_page(w=1700, h=1200):
    """The certificate, smaller, on a dark textured desk (a carpet with light flecks); returns the image and the desk's area."""
    rng = np.random.default_rng(6)

    def noise(scale):
        return cv2.resize(rng.random((h // scale + 2, w // scale + 2)).astype(np.float32), (w, h), interpolation=cv2.INTER_CUBIC)

    fleck = (rng.random((h, w)) > 0.93).astype(np.float32) * rng.random((h, w)).astype(np.float32)
    lum = 0.10 + 0.08 * noise(30) + 0.10 * noise(3) + 0.35 * cv2.GaussianBlur(fleck, (0, 0), 0.8)
    out = (np.clip(np.stack([lum * 0.95, lum, lum * 1.08], -1), 0, 1) * 255).astype(np.uint8)
    inner = cv2.resize(clean_page(w, h), (int(w * 0.84), int(h * 0.84)), interpolation=cv2.INTER_AREA)
    y, x = (h - inner.shape[0]) // 2, (w - inner.shape[1]) // 2
    out[y:y + inner.shape[0], x:x + inner.shape[1]] = inner
    keep = np.ones((h, w), bool)
    keep[y:y + inner.shape[0], x:x + inner.shape[1]] = False
    return out, keep


CAST = np.array([0.93, 0.98, 1.04], np.float32)


def camera(photo, rng, noise=0.035):
    """Lens blur and sensor noise."""
    photo = cv2.GaussianBlur(photo, (0, 0), 0.8)
    photo = photo + rng.normal(0, noise, photo.shape).astype(np.float32)
    return (np.clip(photo, 0, 1) * 255).astype(np.uint8)


def lamp(h, w):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    return 0.70 + 0.18 * (xx / w) + 0.06 * (yy / h)


def photograph(clean, seed=1):
    """The page as a phone sees it: lamp on one side, a shadow, a reflection, a cast, blur, noise."""
    rng = np.random.default_rng(seed)
    img = clean.astype(np.float32) / 255
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    light = 0.62 + 0.25 * (xx / w) + 0.08 * (yy / h)
    shadow = 1 - 0.35 * np.exp(-(((xx - 0.12 * w) / (0.15 * w)) ** 2))
    glare = 0.55 * np.exp(-(((xx - 0.55 * w) ** 2 + (yy - 0.6 * h) ** 2) / (2 * (0.07 * min(h, w)) ** 2)))
    photo = img * (light * shadow)[..., None] * CAST
    photo = photo + glare[..., None] * (1 - photo) * 0.95
    return camera(photo, rng)


def hand_and_phone(h, w):
    """Where a phone held over the page (from the top) and a hand (from the bottom right) block the light."""
    m = np.zeros((h, w), np.float32)
    cv2.fillPoly(m, [cv2.boxPoints(((640, 230), (300, 700), 25)).astype(np.int32)], 1.0)
    cv2.ellipse(m, (1450, 1180), (330, 220), 20, 0, 360, 1.0, -1)
    for x, y, a in ((1300, 900, 35), (1210, 930, 25), (1130, 980, 15), (1080, 1060, 5)):
        cv2.ellipse(m, (x, y), (52, 180), a, 0, 360, 1.0, -1)
    return m


def photograph_hard_shadow(clean, seed=1, strength=(0.42, 0.52, 0.64), edge=2.5):
    """A lamp, a cast and the hard-edged shadow of a hand and a phone, lit by warmer light (B, G, R kept)."""
    rng = np.random.default_rng(seed)
    img = clean.astype(np.float32) / 255
    h, w = img.shape[:2]
    m = cv2.GaussianBlur(hand_and_phone(h, w), (0, 0), edge)
    shade = 1 - m[..., None] * (1 - np.array(strength, np.float32))
    return camera(img * lamp(h, w)[..., None] * shade * CAST, rng, noise=0.03)


def photograph_flash(clean, seed=1, peak=0.8):
    """A lamp, a cast and a flash reflection: a bright core, not quite blown out, with a softer halo."""
    rng = np.random.default_rng(seed)
    img = clean.astype(np.float32) / 255
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r2 = ((xx - 0.47 * w) ** 2 + (yy - 0.62 * h) ** 2) / (2 * (0.045 * min(h, w)) ** 2)
    spot = np.minimum(peak * np.exp(-r2) + 0.25 * peak * np.exp(-r2 / 6), 1)
    photo = img * lamp(h, w)[..., None] * CAST
    return camera(photo + spot[..., None] * (1 - photo), rng, noise=0.03)


def photograph_soft(clean, seed=1):
    """A lamp on one side, a soft shadow, a cast, blur and noise (no reflection)."""
    rng = np.random.default_rng(seed)
    img = clean.astype(np.float32) / 255
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    light = 0.62 + 0.25 * (xx / w) + 0.08 * (yy / h)
    shadow = 1 - 0.30 * np.exp(-(((xx - 0.15 * w) / (0.18 * w)) ** 2))
    return camera(img * (light * shadow)[..., None] * CAST, rng, noise=0.03)


def cases():
    """(name, clean page, photo, area of a picture or desk that must be kept or None)."""
    page, sealed, fills = clean_page(), clean_page(seal=True), clean_fills()
    pictured, picture = clean_photo_page()
    desk, desk_area = clean_desk_page()
    for seed in (1, 2, 3):
        yield f"light+shadow+glare/{seed}", page, photograph(page, seed), None
    yield "hard warm shadow", sealed, photograph_hard_shadow(sealed), None
    yield "hard deep shadow", sealed, photograph_hard_shadow(sealed, 2, strength=(0.33, 0.40, 0.50), edge=1.5), None
    yield "flash glare", page, photograph_flash(page), None
    yield "solid fills", fills, photograph_soft(fills), None
    yield "full-bleed photo", pictured, photograph_soft(pictured), picture
    yield "photo under shadow", pictured, photograph_hard_shadow(pictured), picture
    yield "dark desk", desk, photograph_soft(desk), desk_area


def psnr(a, b, where=None):
    d = (a.astype(np.float32) - b.astype(np.float32)) ** 2
    mse = np.mean(d[where] if where is not None else d)
    return 10 * np.log10(255 ** 2 / max(mse, 1e-6))


def ssim(a, b):
    a = cv2.cvtColor(a, cv2.COLOR_BGR2GRAY).astype(np.float32)
    b = cv2.cvtColor(b, cv2.COLOR_BGR2GRAY).astype(np.float32)
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    ma, mb = cv2.GaussianBlur(a, (11, 11), 1.5), cv2.GaussianBlur(b, (11, 11), 1.5)
    va = cv2.GaussianBlur(a * a, (11, 11), 1.5) - ma ** 2
    vb = cv2.GaussianBlur(b * b, (11, 11), 1.5) - mb ** 2
    cov = cv2.GaussianBlur(a * b, (11, 11), 1.5) - ma * mb
    return float((((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma ** 2 + mb ** 2 + c1) * (va + vb + c2))).mean())


def lab(x):
    return cv2.cvtColor(x.astype(np.float32) / 255, cv2.COLOR_BGR2Lab)


def colour_scores(result, clean):
    """tint99: 99th percentile of the chroma of bare paper. inkdE: mean colour error inside solid coloured areas."""
    lr, lc = lab(result), lab(clean)
    paper = cv2.erode(np.all(clean >= 250, axis=-1).astype(np.uint8), np.ones((7, 7), np.uint8)) > 0
    coloured = cv2.erode((np.hypot(lc[..., 1], lc[..., 2]) > 25).astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
    tint99 = float(np.percentile(np.hypot(lr[..., 1], lr[..., 2])[paper], 99))
    ink_de = float(np.mean(np.linalg.norm(lr[coloured] - lc[coloured], axis=-1)))
    return tint99, ink_de


def main():
    args = sys.argv[1:]
    all_looks = "--all-looks" in args
    args = [a for a in args if a != "--all-looks"]
    out_dir = Path(args[0]) if args else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
    # keep: PSNR inside the picture or desk that must be left as it is (photo cases only).
    print(f"{'case':22s} {'look':12s} {'ssim':>6s} {'psnr':>6s} {'tint99':>7s} {'inkdE':>6s} {'keep':>6s}")
    for name, clean, photo, keep in cases():
        def row(label, result, colour=True):
            c = "{:7.1f} {:6.1f}".format(*colour_scores(result, clean)) if colour else f"{'-':>7s} {'-':>6s}"
            k = f"{psnr(result, clean, keep):6.2f}" if keep is not None else f"{'-':>6s}"
            return f"{name:22s} {label:12s} {ssim(result, clean):6.3f} {psnr(result, clean):6.2f} {c} {k}"
        print(row("(photo)", photo))
        started = time.perf_counter()
        results = looks(photo, only_auto=not all_looks)
        ms = (time.perf_counter() - started) * 1000 / (1 if not all_looks else 3)
        stem = name.replace(" ", "_").replace("+", "_").replace("/", "_")
        for look, result in results.items():
            print(row(look, result, look in COLOUR_LOOKS) + (f"  ({ms:.0f} ms per enhancement)" if look == "auto" else ""))
            if out_dir:
                cv2.imwrite(str(out_dir / f"{stem}__{look}.png"), result)
        if out_dir:
            cv2.imwrite(str(out_dir / f"{stem}__photo.png"), photo)
            cv2.imwrite(str(out_dir / f"{stem}__clean.png"), clean)


if __name__ == "__main__":
    main()
