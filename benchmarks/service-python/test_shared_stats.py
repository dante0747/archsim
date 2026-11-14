"""The shared statistics add up every gunicorn worker's slot (shared_stats.py).

Run by hand from this folder, with Python 3.13 (the service's own) and nothing else installed:

    python -m unittest test_shared_stats

CI does not run it: the python-mixed stack is frozen as it was measured, so its code is not
expected to change, and the repository has no other Python. The Dockerfile copies only the
service's own files, so this test is not in the image.
"""

from __future__ import annotations

import gc
import importlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
shared_stats = importlib.import_module("shared_stats")


class SharedStatsTest(unittest.TestCase):
    def setUp(self) -> None:
        # the workers keep the file mapped, which Windows will not delete
        folder = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(folder.cleanup)
        before = list(gc.callbacks)
        self.addCleanup(lambda: gc.callbacks.__setitem__(slice(None), before))
        path = shared_stats.PATH
        self.addCleanup(lambda: setattr(shared_stats, "PATH", path))
        shared_stats.PATH = Path(folder.name) / "stats"

    def test_the_stats_add_up_every_worker(self) -> None:
        shared_stats.create()
        workers = [shared_stats.Shared() for _ in range(3)]
        for w in workers:
            w.claim()
        self.assertEqual([w.slot for w in workers], [0, 1, 2])
        for i, w in enumerate(workers):
            for j in range(4):
                w.record("db_read", 1.0 + i)
                w.request(2.0 + i + j, 1.0)
        s = workers[0].snapshot()
        self.assertEqual((s["requests"], s["workers"]), (12, 3))
        self.assertEqual(s["timings"]["db_read"]["count"], 12)
        self.assertEqual(s["timings"]["db_read"]["median"], 2.0)  # 4 x 1.0, 4 x 2.0, 4 x 3.0
        self.assertEqual(s["timings"]["handler"]["p95"], 7.0)
        self.assertEqual(s["timings"]["own"]["mean"], 1.0)
        self.assertNotIn("db_write", s["timings"])
        workers[2].reset()  # any worker's /reset clears them all
        s = workers[1].snapshot()
        self.assertEqual((s["requests"], s["timings"]), (0, {}))
        late = shared_stats.Shared()  # a fourth worker takes the next slot
        late.claim()
        self.assertEqual(late.slot, 3)


if __name__ == "__main__":
    unittest.main()
