#!/usr/bin/env python3
"""CI fan-out validator contract tests."""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from validate_ci_fanout import job_blocks, job_specs, runtime_errors, static_errors


class ValidateCiFanoutContractTest(unittest.TestCase):
    def test_mongodb_sensitive_test_lanes_are_pinned_to_ubuntu_2204(self) -> None:
        root = Path(__file__).resolve().parents[2]
        workflow_jobs = {
            ".github/workflows/ci.yml": {
                "test-examples-webhook-poller": "ubuntu-22.04",
                "test-spring-boot": "ubuntu-22.04",
                "test-mongodb": "ubuntu-22.04",
            },
            ".github/workflows/examples.yml": {"test-examples": "ubuntu-22.04"},
            ".github/workflows/nightly-tests.yml": {
                "test-mongodb": "ubuntu-22.04",
                "test-spring-boot": "ubuntu-22.04",
            },
        }

        for workflow_path, job_runners in workflow_jobs.items():
            workflow = root.joinpath(workflow_path).read_text(encoding="utf-8")
            blocks = job_blocks(workflow)
            for job_id, runner in job_runners.items():
                with self.subTest(workflow=workflow_path, job=job_id):
                    self.assertIn(job_id, blocks)
                    escaped_runner = re.escape(runner)
                    self.assertRegex(
                        blocks[job_id],
                        rf"(?m)^    runs-on:\s*{escaped_runner}\s*$",
                    )

    def test_benchmark_tasks_are_excluded_from_ci_and_nightly(self) -> None:
        root = Path(__file__).resolve().parents[2]
        ci = root.joinpath(".github/workflows/ci.yml").read_text(encoding="utf-8")
        nightly = root.joinpath(".github/workflows/nightly-tests.yml").read_text(encoding="utf-8")

        self.assertNotIn("compile-benchmark:", ci)
        self.assertNotIn("steps.filter.outputs.benchmark", ci)
        self.assertIn("-x :benchmark:build", ci)
        self.assertIn("-x :benchmark:build", nightly)
        self.assertIn("-x :benchmark:detekt", nightly)

    def test_static_contract_requires_manual_and_global_filters_and_job(self) -> None:
        workflow = """
on:
  push:
    paths-ignore:
      - '**.md'
      - 'docs/**'
  pull_request:
    paths-ignore:
      - '**.md'
      - 'docs/**'
jobs:
  changes:
    outputs:
      dependency-graph: ${{ steps.filter.outputs.dependency-graph }}
    steps:
      - uses: dorny/paths-filter@v4
        id: filter
        with:
          filters: |
            dependency-graph:
              - 'settings.gradle.kts'
  ci-contract:
    needs: [changes]
    steps:
      - run: python3 scripts/ci/validate_ci_fanout.py --static
  ci-status:
    needs:
      - ci-contract
    steps: []
"""

        errors = static_errors(workflow)

        self.assertTrue(any("manual-contract" in error for error in errors))
        self.assertTrue(any("paths-ignore" in error for error in errors))

    def test_impacted_manual_contract_cannot_be_skipped(self) -> None:
        workflow = """
jobs:
  changes:
    outputs:
      manual-contract: ${{ steps.filter.outputs.manual-contract }}
      global-config: ${{ steps.filter.outputs.global-config }}
  manual-contract:
    needs: [changes]
    if: always() && (needs.changes.outputs['manual-contract'] == 'true' || needs.changes.outputs['global-config'] == 'true')
"""
        specs = job_specs(workflow)
        needs = {
            "changes": {
                "result": "success",
                "outputs": {"manual-contract": "true", "global-config": "false"},
            },
            "manual-contract": {"result": "skipped"},
            "build": {"result": "success"},
            "ci-contract": {"result": "success"},
        }

        errors, _ = runtime_errors(needs, "pull_request", specs)

        self.assertTrue(any("manual-contract" in error and "skipped" in error for error in errors))

    def test_unimpacted_manual_contract_skip_is_reported_as_na(self) -> None:
        workflow = """
jobs:
  changes:
    outputs:
      manual-contract: ${{ steps.filter.outputs.manual-contract }}
      global-config: ${{ steps.filter.outputs.global-config }}
  manual-contract:
    needs: [changes]
    if: always() && (needs.changes.outputs['manual-contract'] == 'true' || needs.changes.outputs['global-config'] == 'true')
"""
        specs = job_specs(workflow)
        needs = {
            "changes": {
                "result": "success",
                "outputs": {"manual-contract": "false", "global-config": "false"},
            },
            "manual-contract": {"result": "skipped"},
            "build": {"result": "success"},
            "ci-contract": {"result": "success"},
        }

        errors, report = runtime_errors(needs, "pull_request", specs)

        self.assertEqual(errors, [])
        self.assertIn("manual-contract: N/A (intended skip)", report)


if __name__ == "__main__":
    unittest.main()
