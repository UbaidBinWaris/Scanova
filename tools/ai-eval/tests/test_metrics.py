import unittest

from ai_eval import metrics
from ai_eval.textnorm import arabic_script_ratio, normalise, script_of


class NormaliseTest(unittest.TestCase):
    def test_urdu_letter_variants_digits_and_bidi_controls_compare_equal(self):
        arabic_forms = "كتاب " + chr(0x0647) + " ي " + chr(0x0661) + chr(0x0662)  # kaf, heh, yeh, Arabic-Indic 12
        urdu_forms = "کتاب " + chr(0x06C1) + " ی " + chr(0x06F1) + chr(0x06F2)
        self.assertEqual(normalise(arabic_forms), normalise(urdu_forms))
        self.assertEqual(normalise(chr(0x2066) + "27-07-2026" + chr(0x2069)), "27-07-2026")
        self.assertEqual(normalise("  Total:\n PKR  1,550 "), "total: pkr 1,550")

    def test_script_detection(self):
        self.assertEqual(script_of("رسید نمبر 123"), "arabic")
        self.assertEqual(script_of("Receipt 123"), "latin")
        self.assertEqual(script_of("123"), "none")
        self.assertGreater(arabic_script_ratio("بجلی کا bill"), 0.5)


class CerTest(unittest.TestCase):
    def test_edit_distance(self):
        self.assertEqual(metrics.levenshtein("kitten", "sitting"), 3)
        self.assertEqual(metrics.levenshtein("", "abc"), 3)

    def test_cer_normalises_both_sides_and_can_exceed_one(self):
        self.assertEqual(metrics.cer("Receipt  No:\n123", "Receipt No: 123"), 0.0)
        self.assertAlmostEqual(metrics.cer("abcd", "abxd"), 0.25)
        self.assertGreater(metrics.cer("ab", "ab and a lot of invented text"), 1.0)
        self.assertEqual(metrics.cer("", ""), 0.0)


class RetrievalTest(unittest.TestCase):
    def test_recall_and_reciprocal_rank(self):
        ranking = ["d3", "d1", "d7", "d2", "d9", "d4"]
        self.assertEqual(metrics.recall_at_k(ranking, ["d1", "d4"], 5), 0.5)
        self.assertEqual(metrics.reciprocal_rank(ranking, ["d1", "d4"]), 0.5)
        self.assertEqual(metrics.reciprocal_rank(ranking, ["x"]), 0.0)


class FieldTest(unittest.TestCase):
    def test_dates_in_every_written_form(self):
        for text in ["15 March 2026", "March 15, 2026", "15/03/2026", "2026-03-15", "15-03-2026", "15 مارچ 2026",
                     "۱۵-۰۳-۲۰۲۶", "Date: 15th March, 2026"]:
            with self.subTest(text=text):
                self.assertEqual(metrics.normalise_date(text), "2026-03-15")
        self.assertIsNone(metrics.normalise_date("no date here"))
        self.assertIsNone(metrics.normalise_date("45/13/2026"))

    def test_find_dates_returns_all_in_order(self):
        self.assertEqual(metrics.find_dates("from 2026-01-20 to 25 February 2026"), ["2026-01-20", "2026-02-25"])

    def test_amounts(self):
        for text, expected in [("PKR 12,500", "12500.00"), ("12,500 روپے", "12500.00"), ("Rs. 12,500/-", "12500.00"),
                               ("USD 44.64", "44.64"), (44.6, "44.60"), ("۱۲,۵۰۰", "12500.00"), ("none", None)]:
            with self.subTest(text=text):
                self.assertEqual(metrics.normalise_amount(text), expected)

    def test_currency_identifier_party(self):
        self.assertEqual(metrics.normalise_currency("روپے"), "PKR")
        self.assertEqual(metrics.normalise_currency("usd"), "USD")
        self.assertEqual(metrics.normalise_identifier("INV-2026/0042"), metrics.normalise_identifier("inv 2026 0042"))
        self.assertEqual(metrics.normalise_party("  Greenleaf  Pharmacy. "), "greenleaf pharmacy")

    def test_field_matches_with_aliases_and_absence(self):
        self.assertTrue(metrics.field_matches("party", "شفا میڈیکل اسٹور", "Shifa Medical Store", ["Shifa Medical Store"]))
        self.assertTrue(metrics.field_matches("amount", None, None))
        self.assertFalse(metrics.field_matches("amount", None, "100"))
        self.assertFalse(metrics.field_matches("date", "2026-03-15", "2026-03-16"))


class FaithfulnessTest(unittest.TestCase):
    def test_numbers_are_canonical(self):
        text = "Total PKR 12,500.00 on 05-03-2026"
        self.assertEqual(metrics.numbers_in(text), {"12500", "5", "3", "2026"})
        self.assertEqual(metrics.numbers_in(text, parts=True), {"12500", "12", "500", "0", "5", "3", "2026"})
        self.assertEqual(metrics.numbers_in("15.03.2026"), {"15", "3", "2026"})

    def test_invented_facts(self):
        source = "Receipt No: 19587\nDate: 14 January 2026\nTOTAL: PKR 1,550"
        self.assertEqual(metrics.invented_facts("Paid PKR 1,550 on 14 January 2026.", source), [])
        self.assertEqual(metrics.invented_facts("Paid PKR 1,650 on 2026-01-14.", source), ["number:1650"])
        self.assertEqual(metrics.invented_facts("Paid on 15 January 2026.", source), ["number:15", "date:2026-01-15"])


if __name__ == "__main__":
    unittest.main()
