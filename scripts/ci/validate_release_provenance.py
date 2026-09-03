#!/usr/bin/env python3
"""release workflow의 immutable source SHA provenance 계약을 검증한다."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WORKFLOW = ROOT / ".github/workflows/release.yml"


def validate_workflow(path: Path) -> list[str]:
    if path.is_symlink() or not path.is_file():
        return [f"{path}: workflow 파일이 없습니다"]

    source = path.read_text(encoding="utf-8")
    violations: list[str] = []
    required_fragments = {
        "resolve-version source_sha output": "source_sha: ${{ steps.resolve.outputs.source_sha }}",
        "tag commit resolution": 'git rev-parse --verify "refs/tags/$VERSION^{commit}"',
        "publish SHA checkout": "ref: ${{ needs.resolve-version.outputs.source_sha }}",
        "head equality guard": 'ACTUAL_SHA="$(git rev-parse HEAD)"',
        "tag equality guard": 'TAG_SHA="$(git rev-parse --verify "refs/tags/$VERSION^{commit}")"',
        "expected SHA comparison": '"$ACTUAL_SHA" != "$EXPECTED_SHA"',
        "tag SHA comparison": '"$TAG_SHA" != "$EXPECTED_SHA"',
    }
    for description, fragment in required_fragments.items():
        if fragment not in source:
            violations.append(f"{path}: {description}가 없습니다 ({fragment})")

    if "ref: ${{ steps.resolve.outputs.ref }}" in source:
        violations.append(f"{path}: resolve-version이 mutable ref output을 노출합니다")
    if "needs.resolve-version.outputs.ref" in source:
        violations.append(f"{path}: release checkout이 mutable tag ref를 사용합니다")

    if source.count("Verify immutable release source") < 2:
        violations.append(f"{path}: publish와 github-release 양쪽에 immutable source guard가 필요합니다")
    return violations


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workflow", type=Path, default=DEFAULT_WORKFLOW)
    args = parser.parse_args(argv)
    violations = validate_workflow(args.workflow)
    if violations:
        for violation in violations:
            print(f"Release provenance contract FAILED: {violation}", file=sys.stderr)
        return 1
    print("Release provenance contract OK: source SHA, checked-out HEAD, and tag commit are bound")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
