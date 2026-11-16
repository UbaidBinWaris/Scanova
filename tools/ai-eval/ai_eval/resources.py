"""Peak memory of the model runtimes during a run (test 13).

A source is either a cgroup v2 directory of the runtime's container (its
`memory.peak` covers the whole life of the container, so restart the container
before a run; `infra/scripts/ai-models.sh eval` does) or `pid:<pid>` (VmHWM,
the peak resident set of that process). Samples of `memory.current` / VmRSS
are taken in a background thread as a second opinion.
"""

import pathlib
import threading
import time


def _read_int(path):
    try:
        return int(pathlib.Path(path).read_text().split()[0])
    except (OSError, ValueError, IndexError):
        return None


def _proc_status(pid, field):
    try:
        for line in pathlib.Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith(field + ":"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


class Source:
    def __init__(self, name, spec):
        self.name = name
        self.spec = spec
        self.max_sampled = 0

    def current(self):
        if self.spec.startswith("pid:"):
            return _proc_status(self.spec[4:], "VmRSS")
        return _read_int(pathlib.Path(self.spec) / "memory.current")

    def peak(self):
        if self.spec.startswith("pid:"):
            return _proc_status(self.spec[4:], "VmHWM")
        return _read_int(pathlib.Path(self.spec) / "memory.peak")

    def sample(self):
        value = self.current()
        if value is not None:
            self.max_sampled = max(self.max_sampled, value)


class Monitor:
    """Samples every source every `interval` seconds until stopped."""

    def __init__(self, specs, interval=1.0):
        self.sources = []
        for spec in specs:
            name, _, value = spec.partition("=")
            if not value:
                raise ValueError(f"--memory expects NAME=PATH, got {spec!r}")
            self.sources.append(Source(name, value))
        self.interval = interval
        self._stop = threading.Event()
        self._thread = None

    def __enter__(self):
        if self.sources:
            self._thread = threading.Thread(target=self._run, daemon=True)
            self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)

    def _run(self):
        while not self._stop.wait(self.interval):
            for source in self.sources:
                source.sample()

    def result(self):
        out = {}
        for source in self.sources:
            source.sample()
            peak = source.peak()
            out[source.name] = {
                "source": source.spec,
                "peak_bytes": peak,
                "max_sampled_bytes": source.max_sampled or None,
            }
        return out
