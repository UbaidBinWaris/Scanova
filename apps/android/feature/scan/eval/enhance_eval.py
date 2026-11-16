"""Reference implementation and evaluation of Scanova's document enhancement.

`DocumentEnhancer.kt` (feature/scan/filter) is a step-by-step port of
`enhance()` below; change both together. The evaluation draws a clean,
certificate-like page, photographs it synthetically (uneven light, a shadow, a
reflection, a colour cast, blur and sensor noise), enhances the "photo" and
scores how close it gets to the clean page (PSNR and SSIM of the brightness).

Run (no project install needed):

    uv run --no-project --with opencv-python-headless --with numpy \
        python3 -I apps/android/feature/scan/eval/enhance_eval.py [output-dir]

It prints the scores and, with an output directory, writes the images. A
change to the enhancement should not lower the scores.
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


def background(ch):
    """2. The bare paper in one channel: ink filled in by a closing, then smoothed."""
    small = small_of(ch)
    sl = max(small.shape)
    bg = cv2.morphologyEx(small, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (odd(sl / 40),) * 2))
    bg = cv2.dilate(bg, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (odd(sl / 90),) * 2))
    # Large solid areas (stamps, logos, photos) are too big to fill: where the estimate is far
    # below the paper around it, take the paper from the surroundings instead (normalised convolution).
    envelope = cv2.GaussianBlur(cv2.dilate(bg, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (odd(sl / 12),) * 2)), (0, 0), sl / 30)
    valid = (bg >= 0.72 * envelope).astype(np.float32)
    filled = cv2.GaussianBlur(bg * valid, (0, 0), sl / 25) / np.maximum(cv2.GaussianBlur(valid, (0, 0), sl / 25), 1e-3)
    bg = np.where(valid > 0, bg, filled).astype(np.float32)
    bg = cv2.medianBlur((np.clip(bg, 0, 1) * 255).astype(np.uint8), odd(sl / 30)).astype(np.float32) / 255
    bg = cv2.GaussianBlur(bg, (0, 0), sl / 120)
    return np.maximum(up(bg, ch.shape), 0.08)


def glare_map(bgs):
    """3. Reflections: paper brighter than its own surroundings (an evenly lit side is not one)."""
    bl = cv2.cvtColor(np.stack([small_of(b) for b in bgs], -1), cv2.COLOR_BGR2GRAY)
    sl = max(bl.shape)
    ratio = bl / np.maximum(cv2.GaussianBlur(bl, (0, 0), sl / 10), 1e-3)
    g = smoothstep(1.025, 1.10, ratio).astype(np.float32)
    g = cv2.dilate(g, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (odd(sl / 40),) * 2))
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
    bgs = [background(den[..., c]) for c in range(3)]
    norm = np.stack([np.minimum(den[..., c] / bgs[c], 1.25) for c in range(3)], -1)
    glare = glare_map(bgs)
    gray = cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY)
    gs = small_of(gray)
    level, spread = paper_stats(gs)
    # 4. Faint ink: stretched back where it stands clearly above the paper's noise.
    sl = max(gs.shape)
    ink = cv2.GaussianBlur(gs, (0, 0), max(0.6, sl / 900))
    ink = cv2.erode(ink, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (odd(sl / 25),) * 2))
    ink = cv2.GaussianBlur(ink, (0, 0), sl / 60)
    depth = np.clip(level - ink, 0, 1)
    gain = np.clip(0.50 / np.maximum(depth, 1e-3), 1.0, 3.0)
    gain = 1.0 + (gain - 1.0) * smoothstep(max(0.06, 5 * spread), max(0.16, 9 * spread), depth)
    gain = np.maximum(up(gain.astype(np.float32), gray.shape), 1.0)
    norm = level + (norm - level) * gain[..., None]
    # 5. Tone: paper within ~3 spreads of white becomes white; whiter inside reflections.
    white = float(np.clip(level - 3.0 * spread, 0.70, 0.97))
    out = np.clip((norm - black) / ((white - 0.16 * glare)[..., None] - black), 0, 1) ** gamma
    out = despeckle(out.astype(np.float32), glare)
    return out, glare


def finish_colour(out, glare, saturation=1.06, sharpen=0.35):
    """The 'Auto' filter's last steps (FilterEngine.colourPage)."""
    rgb8 = (out * 255 + 0.5).astype(np.uint8)
    hsv = cv2.cvtColor(rgb8, cv2.COLOR_BGR2HSV).astype(np.float32)
    s = hsv[..., 1] / 255
    v = hsv[..., 2] / 255
    # A reflection has no colour of its own: drop the tint it left on paper, never on ink.
    s = np.clip(s * (1 + (saturation - 1) * smoothstep(0.12, 0.30, s)), 0, 1) * (1 - glare * smoothstep(0.80, 0.97, v))
    hsv[..., 1] = s * 255
    rgb8 = cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2BGR)
    sigma = max(0.8, max(rgb8.shape[:2]) / 2400)
    blur = cv2.GaussianBlur(rgb8, (0, 0), sigma)
    return cv2.addWeighted(rgb8, 1 + sharpen, blur, -sharpen, 0)


# ── Evaluation ───────────────────────────────────────────────────────────────

def clean_page(w=1700, h=1200):
    """A certificate-like page: coloured headings, body text, rules, ornaments, a round stamp."""
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
    return page


def photograph(clean, seed=1):
    """The page as a phone sees it: lamp on one side, a shadow, a reflection, a cast, blur, noise."""
    rng = np.random.default_rng(seed)
    img = clean.astype(np.float32) / 255
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    light = 0.62 + 0.25 * (xx / w) + 0.08 * (yy / h)
    shadow = 1 - 0.35 * np.exp(-(((xx - 0.12 * w) / (0.15 * w)) ** 2))
    glare = 0.55 * np.exp(-(((xx - 0.55 * w) ** 2 + (yy - 0.6 * h) ** 2) / (2 * (0.07 * min(h, w)) ** 2)))
    photo = img * (light * shadow)[..., None] * np.array([0.93, 0.98, 1.04], np.float32)
    photo = photo + glare[..., None] * (1 - photo) * 0.95
    photo = cv2.GaussianBlur(photo, (0, 0), 0.8)
    photo = photo + rng.normal(0, 0.035, photo.shape).astype(np.float32)
    return (np.clip(photo, 0, 1) * 255).astype(np.uint8)


def psnr(a, b):
    mse = np.mean((a.astype(np.float32) - b.astype(np.float32)) ** 2)
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


def main():
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
    clean = clean_page()
    for seed in (1, 2, 3):
        photo = photograph(clean, seed)
        started = time.perf_counter()
        out, glare = enhance(photo, "natural")
        result = finish_colour(out, glare)
        ms = (time.perf_counter() - started) * 1000
        print(f"seed {seed}: photo psnr={psnr(photo, clean):5.2f} ssim={ssim(photo, clean):.3f}  ->  "
              f"auto psnr={psnr(result, clean):5.2f} ssim={ssim(result, clean):.3f}  ({ms:.0f} ms)")
        if out_dir:
            cv2.imwrite(str(out_dir / f"clean.png"), clean)
            cv2.imwrite(str(out_dir / f"photo{seed}.png"), photo)
            cv2.imwrite(str(out_dir / f"auto{seed}.png"), result)


if __name__ == "__main__":
    main()
