#!/usr/bin/env python3
"""Regression tests for binary-compatibility report classification."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from check_binary_api import is_intentionally_ignored


class BinaryApiClassificationTest(unittest.TestCase):
    def test_class_format_and_synthetic_accessor_are_ignored_together(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLockKt  (not serializable)
\t***! CLASS FILE FORMAT VERSION: 69.0 <- 65.0
\t===  UNCHANGED SUPERCLASS: java.lang.Object (<- java.lang.Object)
\t---! REMOVED METHOD: PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.time.Instant access$dbCurrentTimestamp(org.jetbrains.exposed.v1.jdbc.JdbcTransaction)
"""

        self.assertEqual(
            is_intentionally_ignored(block),
            "compiler-generated synthetic accessor/bridge method",
        )

    def test_class_file_format_only_is_ignored(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.example.PublicApi  (not serializable)
\t***! CLASS FILE FORMAT VERSION: 69.0 <- 65.0
\t===  UNCHANGED SUPERCLASS: java.lang.Object (<- java.lang.Object)
"""

        self.assertEqual(is_intentionally_ignored(block), "JVM class-file format")

    def test_legacy_internal_facade_removal_is_allowlisted(self) -> None:
        block = """---! REMOVED CLASS: PUBLIC(-) FINAL(-) io.bluetape4k.leader.exposed.jdbc.lock.MonotonicDeadline  (not serializable)
\t---  CLASS FILE FORMAT VERSION: n.a. <- 65.0
\t---! REMOVED SUPERCLASS: java.lang.Object
\t---! REMOVED FIELD: PUBLIC(-) STATIC(-) FINAL(-) io.bluetape4k.leader.exposed.jdbc.lock.MonotonicDeadline$Companion Companion
"""

        self.assertEqual(
            is_intentionally_ignored(block),
            "legacy Kotlin-internal JVM facade",
        )

    def test_real_public_member_removal_with_class_format_stays_unclassified(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.example.PublicApi  (not serializable)
\t***! CLASS FILE FORMAT VERSION: 69.0 <- 65.0
\t---! REMOVED METHOD: PUBLIC(-) FINAL(-) java.lang.String removedPublicMethod()
"""

        self.assertIsNone(is_intentionally_ignored(block))

    def test_unrelated_public_class_removal_stays_unclassified(self) -> None:
        block = """---! REMOVED CLASS: PUBLIC(-) FINAL(-) io.example.RemovedPublicApi  (not serializable)
\t---! REMOVED SUPERCLASS: java.lang.Object
"""

        self.assertIsNone(is_intentionally_ignored(block))


if __name__ == "__main__":
    unittest.main()
