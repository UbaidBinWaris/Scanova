"""Mirror of packages/ai/src/embedding.ts: model-specific query/document input
formats and Matryoshka truncation. Change both together."""

import math
import re

_QWEN_TASK = "Given a search query, retrieve the documents that answer it"

_FORMATS = [
    (re.compile(r"qwen3[-_.]?(vl[-_.]?)?embedding", re.I), lambda t: f"Instruct: {_QWEN_TASK}\nQuery:{t}", lambda t: t),
    (re.compile(r"embedding[-_.]?gemma", re.I), lambda t: f"task: search result | query: {t}", lambda t: f"title: none | text: {t}"),
    (re.compile(r"(^|[^a-z0-9])e5([^a-z0-9]|$)", re.I), lambda t: f"query: {t}", lambda t: f"passage: {t}"),
]


def embedding_input(model, kind, text):
    for pattern, query, document in _FORMATS:
        if pattern.search(model):
            return query(text) if kind == "query" else document(text)
    return text


def to_stored_vector(values, dimensions):
    """First `dimensions` values scaled to unit length, or None (too short, zero, not finite)."""
    if len(values) < dimensions:
        return None
    head = [float(v) for v in values[:dimensions]]
    if not all(math.isfinite(v) for v in head):
        return None
    norm = math.sqrt(sum(v * v for v in head))
    if norm == 0:
        return None
    return [v / norm for v in head]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))
