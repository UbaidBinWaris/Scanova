"""Writes report.json and a Markdown report a person can read and compare."""

import json

_STATUS = {"pass": "PASS", "proxy-pass": "PASS (proxy)", "fail": "FAIL", "baseline": "baseline", "partial": "partial",
           "skipped": "skipped"}


def _fmt(value):
    if value is None:
        return "–"
    if isinstance(value, float):
        if value >= 1e8:
            return f"{value / 1024**3:.2f} GiB"
        return f"{value:.3f}" if value < 10 else f"{value:.1f}"
    if isinstance(value, int) and value >= 1e8:
        return f"{value / 1024**3:.2f} GiB"
    return str(value)


def markdown(doc):
    s = doc["summary"]
    lines = [
        f"# AI evaluation: {doc['label']}",
        "",
        f"- Chat: `{doc['endpoints']['chat']['model']}` at {doc['endpoints']['chat']['base_url']}",
        f"- Embeddings: `{doc['endpoints']['embeddings']['model']}` at {doc['endpoints']['embeddings']['base_url']}",
        f"- Suite {doc['suite_version']}, prompts {doc['prompt_version']}, dataset seed {doc['dataset']['manifest'].get('seed')}, "
        f"{doc['dataset']['documents_evaluated']} documents + {doc['dataset']['injection_documents']} injection documents",
        f"- {doc['started']} → {doc['finished']} ({doc['duration_seconds']:.0f} s)",
        "",
        f"**Result:** {s['passed']} passed, {s['failed']} failed, {s['baseline']} baseline, {s['partial']} partial, "
        f"{s['skipped']} skipped. All gates pass: **{'yes' if s['all_gates_pass'] else 'no'}**.",
        "",
        "| # | Test | Metric | Value | Gate | Status |",
        "|---|---|---|---|---|---|",
    ]
    for t in doc["tests"]:
        lines.append(f"| {t['id']} | {t['name']} | {t['metric']} | {_fmt(t['value'])} | {t['gate']} | {_STATUS.get(t['status'], t['status'])} |")
    lines.append("")
    for t in doc["tests"]:
        lines += [f"## {t['id']}. {t['name']}", "", "```json", json.dumps(t["details"], ensure_ascii=False, indent=1, default=str)[:6000], "```", ""]
    props = {k: v["props"] for k, v in doc["endpoints"].items()}
    lines += ["## Runtime", "", "```json", json.dumps(props, ensure_ascii=False, indent=1), "```", ""]
    lines += [
        "Titles: `titles-review.csv` has every suggested title. Fill `reviewer_accepts` (yes/no) to replace the",
        "automatic proxy of test 4 with a real acceptance rate. Per-item results are in `results/`.",
        "",
    ]
    return "\n".join(lines)


def write(doc, out):
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    (out / "report.md").write_text(markdown(doc), encoding="utf-8")
