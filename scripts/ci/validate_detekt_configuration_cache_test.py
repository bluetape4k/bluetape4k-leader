#!/usr/bin/env python3
"""Detekt configuration-cache guard 계약 테스트."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_detekt_configuration_cache import validate_source


class DetektConfigurationCacheContractTest(unittest.TestCase):
    def test_current_guard_is_execution_safe(self) -> None:
        violations = validate_source(Path(__file__).resolve().parents[2])

        self.assertEqual(violations, [])

    def test_rejects_gradle_project_capture_inside_task_action(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build.gradle.kts").write_text(
                """
                val detektProductionSourceGuard = tasks.register("detektProductionSourceGuard") {
                    doLast {
                        val files = subprojects.flatMap { it.fileTree(rootProject.file("src")) }
                        logger.lifecycle(files.toString())
                    }
                }

                gradle.projectsEvaluated {
                }
                """,
                encoding="utf-8",
            )

            violations = validate_source(root)

            self.assertEqual(len(violations), 5)
            self.assertTrue(any("subprojects" in violation for violation in violations))
            self.assertTrue(any("rootProject" in violation for violation in violations))
            self.assertTrue(any("Project.fileTree" in violation for violation in violations))


if __name__ == "__main__":
    unittest.main()
