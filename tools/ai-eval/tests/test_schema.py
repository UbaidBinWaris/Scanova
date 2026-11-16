import copy
import unittest

from ai_eval import schema

VALID = {
    "language": "en",
    "type": "receipt",
    "title": "Receipt from Greenleaf Pharmacy",
    "tags": ["pharmacy"],
    "fields": {"date": "2026-01-14", "amount": "1550.00", "currency": "PKR", "number": "19587", "party": None},
    "summary": "A pharmacy receipt.",
}


class SchemaTest(unittest.TestCase):
    def test_accepts_a_valid_answer(self):
        self.assertEqual(schema.validate(VALID, schema.ANALYSIS_SCHEMA), [])

    def test_reports_each_problem_with_its_path(self):
        broken = copy.deepcopy(VALID)
        broken["type"] = "HACKED"
        broken["title"] = "x" * 81
        broken["fields"]["extra"] = "1"
        del broken["fields"]["date"]
        broken["tags"] = ["a"] * 6
        broken["note"] = "?"
        problems = schema.validate(broken, schema.ANALYSIS_SCHEMA)
        for expected in ["$.type", "$.title", "$.fields.extra", "$.fields.date", "$.tags", "$.note"]:
            self.assertTrue(any(p.startswith(expected) for p in problems), (expected, problems))

    def test_type_mismatch(self):
        self.assertEqual(schema.validate("text", schema.QA_SCHEMA), ["$: expected object"])
        self.assertTrue(schema.validate({"found": "yes", "answer": "", "citations": []}, schema.QA_SCHEMA))


if __name__ == "__main__":
    unittest.main()
