"""Scanova AI evaluation suite (docs/ARCHITECTURE.md §11.6, docs/ai-research.md).

Everything here except `render` and `fonts` uses the Python standard library
only, so the runner and the mock server work in the worker image on the
internal network, where nothing can be installed.
"""

SUITE_VERSION = 1
