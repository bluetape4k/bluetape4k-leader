#!/usr/bin/env python3
"""release source SHA/tag equality 계약 테스트."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/validate_release_provenance.py"
WORKFLOW = ROOT / ".github/workflows/release.yml"


def run_validator(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *arguments],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
    )


class ReleaseProvenanceContractTest(unittest.TestCase):
    def test_release_workflow_resolves_and_checks_an_immutable_source_sha(self) -> None:
        result = run_validator("--workflow", str(WORKFLOW))

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_validator_rejects_mutable_tag_checkout(self) -> None:
        source = """
jobs:
  resolve-version:
    outputs:
      ref: ${{ steps.resolve.outputs.ref }}
  publish:
    steps:
      - uses: actions/checkout@v7
        with:
          ref: ${{ needs.resolve-version.outputs.ref }}
"""

        path = self._write_workflow(source)
        result = run_validator("--workflow", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source_sha", result.stdout + result.stderr)

    def test_validator_requires_tag_and_head_equality_guard(self) -> None:
        source = """
jobs:
  resolve-version:
    outputs:
      source_sha: ${{ steps.resolve.outputs.source_sha }}
    steps:
      - uses: actions/checkout@v7
      - id: resolve
        run: |
          TAG_SHA=$(git rev-parse --verify "refs/tags/$VERSION^{commit}")
          echo "source_sha=$TAG_SHA" >> "$GITHUB_OUTPUT"
  publish:
    steps:
      - uses: actions/checkout@v7
        with:
          ref: ${{ needs.resolve-version.outputs.source_sha }}
"""

        path = self._write_workflow(source)
        result = run_validator("--workflow", str(path))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("equality", result.stdout + result.stderr)

    def _write_workflow(self, source: str) -> Path:
        directory_handle = tempfile.TemporaryDirectory()
        self.addCleanup(directory_handle.cleanup)
        directory = Path(directory_handle.name)
        path = directory / "release.yml"
        path.write_text(source, encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
