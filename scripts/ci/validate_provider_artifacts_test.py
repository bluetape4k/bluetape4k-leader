#!/usr/bin/env python3
"""Exposed provider artifact 계약 테스트."""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/validate_provider_artifacts.py"
WORKFLOWS = (
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/nightly-tests.yml",
)
SPEC = importlib.util.spec_from_file_location("provider_artifact_validator", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


def run_validator(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *arguments],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
    )


class ProviderArtifactContractTest(unittest.TestCase):
    def test_current_workflows_propagate_provider_and_upload_markers(self) -> None:
        result = run_validator(
            "--workflow-contract",
            *(str(workflow) for workflow in WORKFLOWS),
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_accepts_matching_test_and_kover_markers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            test_dir = root / "leader-exposed-jdbc/build/test-results/test"
            kover_dir = root / "leader-exposed-jdbc/build/reports/kover"
            test_dir.mkdir(parents=True)
            kover_dir.mkdir(parents=True)
            (test_dir / "TEST-provider.xml").write_text("<testsuite/>", encoding="utf-8")
            (kover_dir / "report.xml").write_text("<report/>", encoding="utf-8")
            (test_dir / "leader-test-db.txt").write_text(
                "LEADER_TEST_DB=POSTGRESQL\n", encoding="utf-8"
            )
            (kover_dir / "leader-test-db.txt").write_text(
                "LEADER_TEST_DB=POSTGRESQL\n", encoding="utf-8"
            )

            result = run_validator(
                "--root",
                str(root),
                "--module",
                "leader-exposed-jdbc",
                "--provider",
                "POSTGRESQL",
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_mismatched_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            test_dir = root / "leader-exposed-r2dbc/build/test-results/test"
            kover_dir = root / "leader-exposed-r2dbc/build/reports/kover"
            test_dir.mkdir(parents=True)
            kover_dir.mkdir(parents=True)
            (test_dir / "TEST-provider.xml").write_text("<testsuite/>", encoding="utf-8")
            (kover_dir / "reportJvm.xml").write_text("<report/>", encoding="utf-8")
            (test_dir / "leader-test-db.txt").write_text(
                "LEADER_TEST_DB=H2\n", encoding="utf-8"
            )
            (kover_dir / "leader-test-db.txt").write_text(
                "LEADER_TEST_DB=H2\n", encoding="utf-8"
            )

            result = run_validator(
                "--root",
                str(root),
                "--module",
                "leader-exposed-r2dbc",
                "--provider",
                "MYSQL_V8",
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("leader-test-db.txt", result.stdout + result.stderr)

    def test_self_test_passes(self) -> None:
        result = run_validator("--self-test")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_provider_removed_from_test_step(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        source = source.replace(
            '          LEADER_TEST_DB: "H2"\n\n      - name: Generate Kover XML report',
            '          LEADER_TEST_DB: "BROKEN"\n\n      - name: Generate Kover XML report',
            1,
        )

        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "ci.yml"
            workflow.write_text(source, encoding="utf-8")
            violations = VALIDATOR.validate_workflow(workflow)

        self.assertTrue(
            any("test-exposed-jdbc-h2" in violation and "LEADER_TEST_DB" in violation for violation in violations),
            violations,
        )

    def test_rejects_provider_mentioned_only_in_test_script(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        job_start = source.index("  test-exposed-jdbc-h2:")
        job_end = source.index("  test-exposed-jdbc-postgresql:", job_start)
        job = source[job_start:job_end]
        job = job.replace(
            "          for attempt in 1 2 3 4 5; do",
            "          echo 'LEADER_TEST_DB: \"H2\"'\n"
            "          for attempt in 1 2 3 4 5; do",
            1,
        )
        job = job.replace(
            '          LEADER_TEST_DB: "H2"\n\n      - name: Generate Kover XML report',
            '\n      - name: Generate Kover XML report',
            1,
        )
        source = source[:job_start] + job + source[job_end:]

        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "ci.yml"
            workflow.write_text(source, encoding="utf-8")
            violations = VALIDATOR.validate_workflow(workflow)

        self.assertTrue(
            any("test-exposed-jdbc-h2" in violation and "test env" in violation for violation in violations),
            violations,
        )

    def test_rejects_provider_artifact_name_mismatch(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        source = source.replace(
            "          name: test-results-exposed-jdbc-h2",
            "          name: test-results-exposed-jdbc-postgresql",
            1,
        )

        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "ci.yml"
            workflow.write_text(source, encoding="utf-8")
            violations = VALIDATOR.validate_workflow(workflow)

        self.assertTrue(
            any("test-exposed-jdbc-h2" in violation and "artifact" in violation for violation in violations),
            violations,
        )

    def test_rejects_test_artifact_without_xml_results(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        source = source.replace(
            "            **/build/test-results/test/*.xml\n            **/build/test-results/test/leader-test-db.txt",
            "            **/build/test-results/test/leader-test-db.txt",
            1,
        )

        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "ci.yml"
            workflow.write_text(source, encoding="utf-8")
            violations = VALIDATOR.validate_workflow(workflow)

        self.assertTrue(
            any("test-exposed-jdbc-h2" in violation and "artifact" in violation for violation in violations),
            violations,
        )


if __name__ == "__main__":
    unittest.main()
