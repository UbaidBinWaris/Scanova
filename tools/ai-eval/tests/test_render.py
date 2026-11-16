"""Rendering checks. Need Pillow (with Raqm) and fontTools plus the pinned fonts
in the cache, so they are skipped in a plain `selftest`. Run them with:

    uv run --no-project --with-requirements tools/ai-eval/requirements-generate.txt \
        python3 -I tools/ai-eval/aieval.py selftest
"""

import os
import pathlib
import unittest

from ai_eval import dataset

try:
    from PIL import features

    from ai_eval import fonts, render

    HAVE_PILLOW = features.check_feature("raqm")
except ImportError:
    HAVE_PILLOW = False

CACHE = pathlib.Path(os.environ.get("AIEVAL_FONT_CACHE", pathlib.Path(__file__).resolve().parents[1] / ".cache" / "fonts"))


def _fonts_cached():
    return HAVE_PILLOW and all((CACHE / name).exists() for name, _, _ in fonts.FONTS.values())


@unittest.skipUnless(_fonts_cached(), "Pillow with Raqm, fontTools and cached fonts are needed")
class RenderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docs = {d["id"]: d for d in dataset.build().docs}

    def ink_columns(self, image):
        bbox = image.point(lambda v: 255 if v < 128 else 0).getbbox()
        self.assertIsNotNone(bbox, "the page has no text")
        return bbox

    def test_urdu_is_right_aligned_and_english_left_aligned(self):
        for doc_id, side in [("letter-job_offer-ur", "right"), ("invoice-internet-ur", "right"), ("receipt-pharmacy-en", "left")]:
            with self.subTest(doc=doc_id):
                page = render.render_page(self.docs[doc_id], CACHE)
                self.assertEqual(page.size, render.PAGE)
                left, _, right, _ = self.ink_columns(page)
                if side == "right":
                    self.assertGreater(right, render.PAGE[0] - render.MARGIN_X - 20)
                else:
                    self.assertLess(left, render.MARGIN_X + 20)

    def test_photo_is_deterministic(self):
        page = render.render_page(self.docs["receipt-fuel-en"], CACHE)
        a = render.photo(page, "seed").tobytes()
        b = render.photo(page, "seed").tobytes()
        self.assertEqual(a, b)
        self.assertNotEqual(a, render.photo(page, "other").tobytes())

    def test_missing_glyphs_are_refused(self):
        doc = dict(self.docs["invoice-internet-ur"])
        doc["text"] += "\n" + chr(0x4E2D)  # a CJK character: in no font of the dataset
        with self.assertRaises(render.RenderError):
            render.render_page(doc, CACHE)


if __name__ == "__main__":
    unittest.main()
