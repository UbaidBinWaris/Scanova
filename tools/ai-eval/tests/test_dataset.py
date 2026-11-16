import json
import re
import unittest

from ai_eval import dataset, metrics, mock_server, schema
from ai_eval.textnorm import normalise, script_of

LATIN = re.compile(r"[A-Za-z]")


class DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = dataset.build().to_json()
        cls.docs = cls.data["docs"]
        cls.originals = [d for d in cls.docs if d["injection"] is None]

    def test_is_deterministic(self):
        again = dataset.build().to_json()
        self.assertEqual(json.dumps(self.data, sort_keys=True), json.dumps(again, sort_keys=True))
        self.assertNotEqual(json.dumps(self.data, sort_keys=True), json.dumps(dataset.build(seed=1).to_json(), sort_keys=True))

    def test_shape(self):
        self.assertEqual(len(self.originals), 60)
        self.assertEqual(len([d for d in self.docs if d["injection"]]), 8)
        self.assertEqual(len({d["id"] for d in self.docs}), len(self.docs))
        kinds = {(d["type"], d["lang"]) for d in self.originals}
        self.assertEqual(kinds, {(t, l) for t in ["invoice", "receipt", "letter", "form", "id_card"] for l in ["en", "ur"]})
        self.assertEqual({d["user"] for d in self.originals}, {"alice", "bilal"})

    def test_ground_truth_is_in_the_text(self):
        for doc in self.originals:
            with self.subTest(doc=doc["id"]):
                text = doc["text"]
                fields = doc["fields"]
                self.assertIn(fields["date"], metrics.find_dates(text))
                if fields["amount"] is not None:
                    self.assertIn(metrics._canonical_number(fields["amount"]), metrics.numbers_in(text))
                self.assertIn(normalise(fields["number"]), normalise(text))
                self.assertIn(normalise(fields["party"]), normalise(text))
                self.assertIn(doc["type"], schema.DOCUMENT_TYPES)

    def test_urdu_documents_are_urdu_without_latin_letters(self):
        for doc in self.originals:
            if doc["lang"] != "ur":
                continue
            with self.subTest(doc=doc["id"]):
                self.assertEqual(script_of(doc["text"]), "arabic")
                self.assertIsNone(LATIN.search(doc["text"]), "Urdu documents keep identifiers numeric")
                for joined in re.findall(r"\d+(?:-\d+)+", doc["text"]):
                    self.assertIn(chr(0x2066) + joined, doc["text"], "hyphenated numbers are left-to-right isolates")

    def test_queries_and_questions_point_at_real_documents(self):
        owner = {d["id"]: d["user"] for d in self.originals}
        for query in self.data["queries"]:
            self.assertTrue(set(query["relevant"]) <= set(owner), query["id"])
        for question in self.data["questions"]:
            self.assertIn(question["doc_id"], owner)
            self.assertEqual(question["kind"] == "own", owner[question["doc_id"]] == question["asked_by"])
        langs = {q["lang"] for q in self.data["queries"]}
        self.assertEqual(langs, {"en", "ur"})

    def test_injection_documents_keep_the_ground_truth_of_their_base(self):
        for doc in self.docs:
            if doc["injection"] is None:
                continue
            self.assertFalse(doc["render"])
            base = next(d for d in self.originals if d["fields"] == doc["fields"] and d["type"] == doc["type"])
            self.assertIn(base["text"].split("\n")[0], doc["text"])

    def test_mock_rules_read_the_ground_truth(self):
        """The mock 'model' must be good enough for the end-to-end test to mean something."""
        correct = 0
        for doc in self.originals:
            out = mock_server.analyse(doc["text"])
            self.assertEqual(schema.validate(out, schema.ANALYSIS_SCHEMA), [], doc["id"])
            correct += out["type"] == doc["type"]
        self.assertEqual(correct, len(self.originals))


if __name__ == "__main__":
    unittest.main()
