#!/usr/bin/env python3
"""immutable JDK25 provider pin에 대한 release preflight 계약 테스트."""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROPERTIES = ROOT / "gradle.properties"
WORKFLOW = ROOT / ".github/workflows/release.yml"
sys.path.insert(0, str(Path(__file__).parent))

from validate_release_preflight import (  # noqa: E402
    validate_provider_version,
    validate_resolved_dependencies,
)


class ReleasePreflightContractTest(unittest.TestCase):
    def test_release_line_uses_an_immutable_jdk25_provider_coordinate(self) -> None:
        properties = {
            line.split("=", 1)[0]: line.split("=", 1)[1]
            for line in PROPERTIES.read_text(encoding="utf-8").splitlines()
            if "=" in line and not line.lstrip().startswith("#")
        }
        version = properties["bluetape4kVirtualThreadJdk25Version"]

        self.assertNotRegex(version, re.compile(r"-SNAPSHOT$"))

    def test_release_workflow_validates_the_resolved_provider_and_api(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("validate_release_preflight.py", workflow)
        self.assertIn("--resolution", workflow)
        self.assertRegex(
            workflow,
            re.compile(r"SNAPSHOT.*fail|fail.*SNAPSHOT", re.IGNORECASE | re.DOTALL),
        )

    def test_provider_validation_rejects_a_dev_snapshot(self) -> None:
        version, errors = validate_provider_version(
            {"bluetape4kVirtualThreadJdk25Version": "1.13.0-SNAPSHOT"}
        )

        self.assertEqual(version, "1.13.0-SNAPSHOT")
        self.assertEqual(len(errors), 1)
        self.assertIn("-SNAPSHOT으로 끝날 수 없다", errors[0])

    def test_provider_validation_rejects_a_missing_coordinate(self) -> None:
        version, errors = validate_provider_version({})

        self.assertIsNone(version)
        self.assertEqual(len(errors), 1)
        self.assertIn("필요하다", errors[0])

    def test_resolution_validation_requires_the_same_immutable_api_and_provider(
        self,
    ) -> None:
        resolution = """
        +--- io.github.bluetape4k:bluetape4k-virtualthread-api:1.13.0-SNAPSHOT -> 1.13.0-20260813.192107-9
        +--- io.github.bluetape4k:bluetape4k-virtualthread-jdk25:1.13.0-SNAPSHOT -> 1.13.0-20260813.192107-9
        """

        errors = validate_resolved_dependencies(
            resolution,
            "1.13.0-20260813.192107-9",
        )

        self.assertEqual(errors, [])

    def test_resolution_validation_rejects_snapshot_output(self) -> None:
        resolution = (
            "io.github.bluetape4k:bluetape4k-virtualthread-api:1.13.0-SNAPSHOT\n"
            "io.github.bluetape4k:bluetape4k-virtualthread-jdk25:1.13.0-SNAPSHOT\n"
        )

        errors = validate_resolved_dependencies(resolution, "1.13.0-SNAPSHOT")

        self.assertEqual(len(errors), 2)
        self.assertTrue(all("snapshot 좌표가 남아 있다" in error for error in errors))

    def test_resolution_validation_rejects_a_different_immutable_coordinate(self) -> None:
        resolution = (
            "io.github.bluetape4k:bluetape4k-virtualthread-api:1.12.1\n"
            "io.github.bluetape4k:bluetape4k-virtualthread-jdk25:1.12.1\n"
        )

        errors = validate_resolved_dependencies(
            resolution,
            "1.13.0-20260813.192107-9",
        )

        self.assertEqual(len(errors), 2)
        self.assertTrue(all("찾을 수 없다" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
