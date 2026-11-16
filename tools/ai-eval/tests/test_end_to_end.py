"""The runner against the mock runtime, offline: proves every test computes,
the privacy checks catch what they must, and the report is written."""

import contextlib
import io
import json
import os
import pathlib
import tempfile
import unittest

import aieval
from ai_eval import dataset, mock_server
from ai_eval.client import Endpoint

KEY = "selftest-key-0123456789abcdef0123"


class EndToEndTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(cls.tmp.name)
        cls.data = root / "dataset"
        builder = dataset.build()
        for doc in builder.docs:
            doc["images"] = []
        dataset.write(builder, cls.data, dataset.DEFAULT_SEED, {"text_only": True})
        cls.log = root / "runtime.log"
        cls.server, cls.url = mock_server.start(KEY, log_path=str(cls.log))
        os.environ["SCANOVA_SELFTEST_KEY"] = KEY

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def run_suite(self, out, *extra):
        argv = ["run", "--dataset", str(self.data), "--out", str(out), "--api-key-env", "SCANOVA_SELFTEST_KEY",
                "--chat-base-url", self.url, "--chat-model", "mock-chat", "--embed-base-url", self.url,
                "--embed-model", "multilingual-e5-small", "--embed-dimensions", "128", "--max-questions", "9", *extra]
        with contextlib.redirect_stderr(io.StringIO()):
            code = aieval.main(argv)
        report = json.loads((out / "report.json").read_text(encoding="utf-8"))
        return code, report, {t["id"]: t for t in report["tests"]}

    def test_full_run(self):
        out = pathlib.Path(self.tmp.name) / "report-full"
        infra = pathlib.Path(self.tmp.name) / "infra.json"
        infra.write_text(json.dumps({"unreachable_from_edge": True, "no_published_ports": {"ok": True}}))
        _, report, tests = self.run_suite(out, "--runtime-log", str(self.log), "--infra-checks", str(infra))

        self.assertEqual(sorted(tests), list(range(1, 14)))
        for number in (1, 2, 3, 10, 11, 12):
            self.assertEqual(tests[number]["status"], "pass", (number, tests[number]["details"]))
        # The mock retrieves by word overlap, so Q&A is good but not perfect; it must be computed.
        self.assertGreaterEqual(tests[9]["value"], 0.5)
        self.assertEqual(tests[9]["details"]["questions"], 6)
        self.assertEqual(tests[4]["status"], "proxy-pass")
        self.assertEqual(tests[8]["status"], "pass")
        # No images in a text-only dataset: the OCR tests say so instead of passing.
        self.assertEqual(tests[5]["status"], "skipped")
        self.assertEqual(tests[6]["status"], "skipped")
        # Lexical mock embeddings cannot cross languages: the search test must show it.
        directions = tests[7]["details"]["by_direction"]
        self.assertLess(directions["en->ur"]["recall@5"], 0.5)
        self.assertGreater(directions["en->en"]["recall@5"], 0.5)
        # No RAM source given: performance is measured but partial.
        self.assertEqual(tests[13]["status"], "partial")
        self.assertIsNotNone(tests[13]["details"]["analysis_seconds_per_page"]["p50"])

        for name in ("report.md", "titles-review.csv", "results/analysis.jsonl", "results/qa.jsonl"):
            self.assertTrue((out / name).exists(), name)
        self.assertIn("| 12 | privacy |", (out / "report.md").read_text(encoding="utf-8"))
        self.assertEqual(report["prompt_version"], report["prompt_version"].strip())

    def test_privacy_catches_document_text_in_a_log_and_an_open_runtime(self):
        leaked = pathlib.Path(self.tmp.name) / "leaky.log"
        doc = dataset.build().docs[0]
        leaked.write_text("request body: " + doc["text"].split("\n")[1], encoding="utf-8")
        out = pathlib.Path(self.tmp.name) / "report-leak"
        code, _, tests = self.run_suite(out, "--tests", "privacy", "--runtime-log", str(leaked))
        checks = tests[12]["details"]["checks"]
        self.assertFalse(checks["no_document_text_in_runtime_logs"]["ok"])
        self.assertEqual(tests[12]["status"], "fail")
        self.assertEqual(code, 2)

    def test_selected_tests_only(self):
        out = pathlib.Path(self.tmp.name) / "report-one"
        _, _, tests = self.run_suite(out, "--tests", "2,structured_output", "--limit", "6")
        self.assertEqual(sorted(tests), [1, 2])

    def test_mock_requires_the_key_like_llama_cpp(self):
        endpoint = Endpoint(self.url, "mock", KEY)
        status, _, _ = endpoint.request("GET", f"{endpoint.root}/health", api_key=None)
        self.assertEqual(status, 200)
        status, _, _ = endpoint.request("POST", f"{self.url}/embeddings", {"input": "x"}, api_key=None)
        self.assertEqual(status, 401)
        # The request log has method, path and status only.
        for line in self.log.read_text(encoding="utf-8").splitlines():
            self.assertRegex(line, r"^\S+ (GET|POST) /\S* \d{3}$")


if __name__ == "__main__":
    unittest.main()
