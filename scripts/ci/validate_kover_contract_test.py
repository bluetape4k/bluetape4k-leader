#!/usr/bin/env python3
"""Kover test/report execution and fail-closed aggregation 계약 테스트."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/validate_kover_contract.py"
AGGREGATOR = ROOT / ".github/scripts/aggregate-kover-coverage.py"
WORKFLOWS = (
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/nightly-tests.yml",
)


def run_validator(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *arguments],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
    )


def run_aggregator(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(AGGREGATOR), str(root)],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
    )


class KoverContractTest(unittest.TestCase):
    def test_current_workflows_use_one_gradle_graph_for_tests_and_kover(self) -> None:
        result = run_validator(
            "--workflow-contract",
            *(str(workflow) for workflow in WORKFLOWS),
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_validator_rejects_a_report_command_that_skips_tests(self) -> None:
        workflow = """
jobs:
  test-core:
    steps:
      - run: ./gradlew :bluetape4k-leader-core:test
      - name: Generate Kover XML report
        run: ./gradlew :bluetape4k-leader-core:koverXmlReport -x test
"""

        path = self._write_workflow(workflow)
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("same Gradle invocation", result.stdout + result.stderr)

    def test_validator_requires_fail_on_missing_coverage_files(self) -> None:
        workflow = """
jobs:
  test-core:
    steps:
      - run: ./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-core:koverXmlReport
  coverage-report:
    steps:
      - name: Download all coverage artifacts
        uses: actions/download-artifact@v8
        with:
          pattern: coverage-*
        continue-on-error: true
      - name: Aggregate Kover coverage summary
        run: python3 .github/scripts/aggregate-kover-coverage.py coverage-artifacts
"""

        path = self._write_workflow(workflow)
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("continue-on-error", result.stdout + result.stderr)

    def test_validator_requires_coverage_job_to_follow_impact_filters(self) -> None:
        workflow = """
jobs:
  changes:
    outputs:
      leader-core: ${{ steps.filter.outputs.leader-core }}
  test-core:
    needs: [changes]
    if: ${{ needs.changes.outputs['leader-core'] == 'true' }}
    steps:
      - run: ./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-core:koverXmlReport
      - name: Upload coverage report
        uses: actions/upload-artifact@v7
        with:
          if-no-files-found: error
  coverage-report:
    needs: [test-core]
    if: always()
    steps:
      - name: Download all coverage artifacts
        uses: actions/download-artifact@v8
      - name: Aggregate Kover coverage summary
        run: python3 .github/scripts/aggregate-kover-coverage.py coverage-artifacts
"""

        path = self._write_workflow(workflow)
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("changes job", result.stdout + result.stderr)

    def test_validator_requires_k8s_pr_job_to_publish_aggregated_coverage(self) -> None:
        workflow = """
jobs:
  changes:
    outputs:
      leader-k8s: ${{ steps.filter.outputs.leader-k8s }}
  test-core:
    steps:
      - run: ./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-core:koverXmlReport
      - name: Upload coverage report
        uses: actions/upload-artifact@v7
        with:
          if-no-files-found: error
  test-leader-k8s:
    needs: [changes]
    if: ${{ needs.changes.outputs['leader-k8s'] == 'true' || github.event_name == 'workflow_dispatch' }}
    steps:
      - name: Test leader-k8s
        run: ./gradlew :bluetape4k-leader-k8s:test :bluetape4k-leader-k8s:k8sTest
  coverage-report:
    needs:
      - changes
      - test-core
      - test-leader-k8s
    if: ${{ needs.changes.outputs['leader-k8s'] == 'true' || github.event_name == 'workflow_dispatch' }}
    steps:
      - name: Download all coverage artifacts
        uses: actions/download-artifact@v8
      - name: Aggregate Kover coverage summary
        run: python3 .github/scripts/aggregate-kover-coverage.py coverage-artifacts
"""

        path = self._write_workflow(workflow)
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("test-leader-k8s", result.stdout + result.stderr)

    def test_validator_requires_full_nightly_expected_coverage_artifacts(self) -> None:
        workflow = """
jobs:
  test-core:
    steps:
      - run: ./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-core:koverXmlReport
      - name: Upload coverage report
        uses: actions/upload-artifact@v7
        with:
          if-no-files-found: error
          name: coverage-core
  coverage-report:
    needs: [test-core]
    if: always()
    steps:
      - name: Download all coverage artifacts
        uses: actions/download-artifact@v8
      - name: Aggregate Kover coverage summary
        run: python3 .github/scripts/aggregate-kover-coverage.py coverage-artifacts
"""

        path = self._write_workflow(workflow, name="nightly-tests.yml")
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--expected-artifact", result.stdout + result.stderr)

    def test_validator_rejects_full_nightly_without_coverage_producers(self) -> None:
        workflow = """
jobs:
  coverage-report:
    needs: []
    if: always()
    steps:
      - name: Download all coverage artifacts
        uses: actions/download-artifact@v8
      - name: Aggregate Kover coverage summary
        run: python3 .github/scripts/aggregate-kover-coverage.py coverage-artifacts
  nightly-status:
    needs: [coverage-report]
    if: always()
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-python@v7
        with:
          python-version: '3.12'
      - name: Check all jobs
        run: |
          python3 scripts/ci/validate_nightly_gate.py \
            --results "$RESULTS" \
            --full-scope "$FULL_SCOPE" \
            --cluster-result "$CLUSTER_RESULT"
"""

        path = self._write_workflow(workflow, name="nightly-tests.yml")
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("producer", result.stdout + result.stderr)

    def test_validator_requires_the_fail_closed_nightly_gate_helper(self) -> None:
        workflow = """
jobs:
  test-core:
    steps:
      - run: ./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-core:koverXmlReport
      - name: Upload coverage report
        uses: actions/upload-artifact@v7
        with:
          if-no-files-found: error
          name: coverage-core
  coverage-report:
    needs: [test-core]
    if: always()
    steps:
      - name: Download all coverage artifacts
        uses: actions/download-artifact@v8
      - name: Aggregate Kover coverage summary
        run: >-
          python3 .github/scripts/aggregate-kover-coverage.py coverage-artifacts
          --expected-artifact coverage-core
  nightly-status:
    needs: [test-core, coverage-report]
    if: always()
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-python@v7
        with:
          python-version: '3.12'
      - name: Check all jobs
        run: echo "Nightly results"
"""

        path = self._write_workflow(workflow, name="nightly-tests.yml")
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("validate_nightly_gate.py", result.stdout + result.stderr)

    def test_validator_requires_nightly_status_to_checkout_the_repository(self) -> None:
        workflow = """
jobs:
  test-core:
    steps:
      - run: ./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-core:koverXmlReport
  coverage-report:
    needs: [test-core]
    if: always()
    steps:
      - name: Download all coverage artifacts
        uses: actions/download-artifact@v8
      - name: Aggregate Kover coverage summary
        run: python3 .github/scripts/aggregate-kover-coverage.py coverage-artifacts
  nightly-status:
    needs: [test-core, coverage-report]
    if: always()
    steps:
      - uses: actions/setup-python@v7
        with:
          python-version: '3.12'
      - name: Check all jobs
        run: |
          python3 scripts/ci/validate_nightly_gate.py \
            --results "$RESULTS" \
            --full-scope "$FULL_SCOPE" \
            --cluster-result "$CLUSTER_RESULT"
"""

        path = self._write_workflow(workflow, name="nightly-tests.yml")
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checkout", result.stdout + result.stderr)

    def test_validator_rejects_duplicate_full_nightly_coverage_artifact_names(self) -> None:
        workflow = self._nightly_topology_workflow(
            second_artifact="coverage-core",
            coverage_needs="test-core, test-micrometer",
        )

        path = self._write_workflow(workflow, name="nightly-tests.yml")
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("중복", result.stdout + result.stderr)

    def test_validator_requires_coverage_producers_in_report_needs(self) -> None:
        workflow = self._nightly_topology_workflow(
            second_artifact="coverage-micrometer",
            coverage_needs="test-core",
        )

        path = self._write_workflow(workflow, name="nightly-tests.yml")
        result = run_validator("--workflow-contract", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("producer", result.stdout + result.stderr)

    def test_aggregator_fails_when_no_reports_are_present(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = run_aggregator(Path(directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No coverage reports found", result.stdout + result.stderr)

    def test_aggregator_fails_when_a_report_is_malformed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "coverage-core/build/reports/kover/report.xml"
            report.parent.mkdir(parents=True)
            report.write_text("<report>", encoding="utf-8")

            result = run_aggregator(Path(directory))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("malformed", result.stdout + result.stderr)

    def test_aggregator_accepts_a_valid_instruction_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "coverage-core/build/reports/kover/report.xml"
            report.parent.mkdir(parents=True)
            report.write_text(
                '<report><counter type="INSTRUCTION" missed="2" covered="8"/></report>',
                encoding="utf-8",
            )

            result = run_aggregator(Path(directory))

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("80.00%", result.stdout)

    def test_aggregator_fails_when_an_expected_artifact_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "coverage-core/build/reports/kover/report.xml"
            report.parent.mkdir(parents=True)
            report.write_text(
                '<report><counter type="INSTRUCTION" missed="1" covered="1"/></report>',
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(AGGREGATOR),
                    directory,
                    "--expected-artifact",
                    "coverage-core",
                    "--expected-artifact",
                    "coverage-micrometer",
                ],
                cwd=ROOT,
                check=False,
                text=True,
                capture_output=True,
            )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected coverage artifact is missing", result.stdout + result.stderr)

    def _nightly_topology_workflow(self, *, second_artifact: str, coverage_needs: str) -> str:
        return f"""
jobs:
  test-core:
    steps:
      - run: ./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-core:koverXmlReport
      - name: Upload coverage report
        uses: actions/upload-artifact@v7
        with:
          if-no-files-found: error
          name: coverage-core
  test-micrometer:
    steps:
      - run: ./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-micrometer:koverXmlReport
      - name: Upload coverage report
        uses: actions/upload-artifact@v7
        with:
          if-no-files-found: error
          name: {second_artifact}
  coverage-report:
    needs: [{coverage_needs}]
    if: always()
    steps:
      - name: Download all coverage artifacts
        uses: actions/download-artifact@v8
      - name: Aggregate Kover coverage summary
        run: >-
          python3 .github/scripts/aggregate-kover-coverage.py coverage-artifacts
          --expected-artifact coverage-core
          --expected-artifact {second_artifact}
  nightly-status:
    needs: [test-core, test-micrometer, coverage-report]
    if: always()
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-python@v7
        with:
          python-version: '3.12'
      - name: Check all jobs
        run: |
          python3 scripts/ci/validate_nightly_gate.py \
            --results "$RESULTS" \
            --full-scope "$FULL_SCOPE" \
            --cluster-result "$CLUSTER_RESULT"
"""

    def _write_workflow(self, source: str, *, name: str = "workflow.yml") -> Path:
        directory_handle = tempfile.TemporaryDirectory()
        self.addCleanup(directory_handle.cleanup)
        directory = Path(directory_handle.name)
        path = directory / name
        path.write_text(source, encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
