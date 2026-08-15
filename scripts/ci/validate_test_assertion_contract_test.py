#!/usr/bin/env python3
"""Kotlin 테스트 assertion contract validator의 계약 테스트."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_test_assertion_contract import validate_sources


class TestAssertionContractValidator(unittest.TestCase):
    def test_accepts_bluetape_assertions_and_natural_success_calls(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "leader-core/src/test/kotlin/ContractTest.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                """
                import io.bluetape4k.assertions.assertFailsWith
                import org.junit.jupiter.api.Test

                @Test
                fun contract() {
                    assertFailsWith<IllegalArgumentException> { validate(\"\") }
                    validate(\"valid\")
                }
                """,
                encoding="utf-8",
            )

            self.assertEqual(validate_sources(root), [])

    def test_rejects_forbidden_junit_and_kotlin_test_assertions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "leader-core/src/test/kotlin/ContractTest.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                """
                import kotlin.test.assertTrue
                import org.junit.jupiter.api.assertDoesNotThrow
                """,
                encoding="utf-8",
            )

            violations = validate_sources(root)

            self.assertEqual(len(violations), 2)
            self.assertTrue(any("kotlin.test assertion" in item for item in violations))
            self.assertTrue(any("JUnit assertion" in item for item in violations))

    def test_rejects_direct_junit_assertion_calls(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "leader-core/src/test/kotlin/ContractTest.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                """
                import org.junit.jupiter.api.Test

                @Test
                fun contract() {
                    Assertions.assertEquals(1, 1)
                }
                """,
                encoding="utf-8",
            )

            violations = validate_sources(root)

            self.assertEqual(len(violations), 1)
            self.assertIn("JUnit assertion 호출", violations[0])

    def test_rejects_run_catching_failure_without_exception_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "leader-core/src/test/kotlin/ContractTest.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                """
                import org.junit.jupiter.api.Test

                @Test
                fun contract() {
                    runCatching {
                        validate(\"\")
                    }.isFailure.shouldBeTrue()
                }
                """,
                encoding="utf-8",
            )

            violations = validate_sources(root)

            self.assertEqual(len(violations), 1)
            self.assertIn("runCatching", violations[0])

    def test_excludes_buildsrc_bootstrap_tests_from_library_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "buildSrc/src/test/kotlin/BootstrapTest.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                "import kotlin.test.assertTrue\n",
                encoding="utf-8",
            )

            self.assertEqual(validate_sources(root), [])


if __name__ == "__main__":
    unittest.main()
