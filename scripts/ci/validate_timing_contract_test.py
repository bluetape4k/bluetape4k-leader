#!/usr/bin/env python3
"""Timing contract validator의 회귀 테스트."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_timing_contract import validate_sources


TARGETS = {
    "leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonSuspendLeaderGroupElectorTest.kt":
        "await.atMost(5.seconds) untilAsserted { startedCount.get() == maxLeaders }",
    "leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtenderStressTest.kt":
        "await.atMost(5.seconds) untilAsserted { delegates.all { it.extendCalls.get() >= 1 } }",
    "leader-core/src/test/kotlin/io/bluetape4k/leader/coroutines/LocalSuspendLeaderElectorTest.kt":
        "holderRelease.await()",
}


class TestTimingContractValidator(unittest.TestCase):
    def _write_target(self, root: Path, relative_path: str, source: str) -> None:
        target = root / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def test_accepts_bounded_and_controllable_timing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative_path, source in TARGETS.items():
                self._write_target(root, relative_path, source)

            self.assertEqual(validate_sources(root), [])

    def test_rejects_known_unbounded_or_wall_clock_patterns(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_target(
                root,
                next(path for path in TARGETS if path.startswith("leader-redis-redisson")),
                "while (startedCount.get() < maxLeaders) { delay(5.milliseconds) }",
            )
            self._write_target(
                root,
                next(path for path in TARGETS if path.startswith("leader-core/src/test/kotlin/io/bluetape4k/leader/Leader")),
                "delay(5.seconds)\nThread.sleep(500)\nextendStartedLatch.await(600, TimeUnit.MILLISECONDS)",
            )
            self._write_target(
                root,
                next(path for path in TARGETS if path.startswith("leader-core/src/test/kotlin/io/bluetape4k/leader/coroutines")),
                "delay(300.milliseconds)",
            )

            violations = validate_sources(root)

            self.assertEqual(len(violations), 5)
            self.assertTrue(any("무제한 polling" in item for item in violations))
            self.assertTrue(any("고정 delay" in item for item in violations))
            self.assertTrue(any("Thread.sleep" in item for item in violations))
            self.assertTrue(any("latch deadline" in item for item in violations))
            self.assertTrue(any("wall-clock delay" in item for item in violations))


if __name__ == "__main__":
    unittest.main()
