#!/usr/bin/env python3
"""Gitleaks 설치·history scan·baseline 계약을 fail-closed로 검증한다."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/ci.yml"
INSTALLER = ROOT / ".github/scripts/install-gitleaks.sh"
BASELINE = ROOT / ".gitleaks-baseline.json"
GITLEAKS_VERSION = "8.30.1"
GITLEAKS_SHA256 = "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"


def _job_block(source: str, job_id: str) -> str | None:
    marker = f"  {job_id}:"
    start = source.find(marker)
    if start < 0 or (start and source[start - 1] != "\n"):
        return None
    next_job = re.search(
        r"^  [A-Za-z0-9][A-Za-z0-9_-]*:\s*$",
        source[start + len(marker) :],
        flags=re.MULTILINE,
    )
    if next_job is None:
        return source[start:]
    return source[start : start + len(marker) + next_job.start()]


def validate_source(root: Path = ROOT) -> list[str]:
    workflow = root / ".github/workflows/ci.yml"
    installer = root / ".github/scripts/install-gitleaks.sh"
    baseline = root / ".gitleaks-baseline.json"
    violations: list[str] = []

    for path, description in (
        (workflow, "CI workflow"),
        (installer, "installer"),
        (baseline, "baseline"),
    ):
        if path.is_symlink() or not path.is_file():
            violations.append(f"{path}: {description} 파일이 없습니다")
    if violations:
        return violations

    workflow_source = workflow.read_text(encoding="utf-8")
    gitleaks_job = _job_block(workflow_source, "gitleaks")
    if gitleaks_job is None:
        violations.append(f"{workflow}: gitleaks job이 없습니다")
    else:
        required_workflow_fragments = (
            "bash .github/scripts/install-gitleaks.sh",
            f"GITLEAKS_VERSION: '{GITLEAKS_VERSION}'",
            f"GITLEAKS_SHA256: '{GITLEAKS_SHA256}'",
            "gitleaks git",
            "--baseline-path .gitleaks-baseline.json",
            '--log-opts=\"--all\"',
            "fetch-depth: 0",
            "python3 scripts/ci/validate_gitleaks_contract_test.py",
        )
        for fragment in required_workflow_fragments:
            if fragment not in gitleaks_job:
                violations.append(f"{workflow}: gitleaks job에 필수 계약이 없습니다: {fragment}")
        forbidden_workflow_fragments = (
            "releases/latest",
            "gitleaks detect",
            "--no-git",
            "continue-on-error: true",
        )
        for fragment in forbidden_workflow_fragments:
            if fragment in gitleaks_job:
                violations.append(f"{workflow}: gitleaks job에서 금지된 fail-open/비결정적 계약: {fragment}")

    installer_source = installer.read_text(encoding="utf-8")
    required_installer_fragments = (
        ': "${GITLEAKS_VERSION:?',
        ': "${GITLEAKS_SHA256:?',
        "GITLEAKS_RETRY_ATTEMPTS",
        "for ((attempt = 1; attempt <= attempts; attempt++))",
        "curl --fail --location",
        "sha256sum --check --strict",
        "tar --extract --gzip",
        "gitleaks download failed after",
    )
    for fragment in required_installer_fragments:
        if fragment not in installer_source:
            violations.append(f"{installer}: installer에 필수 계약이 없습니다: {fragment}")
    if "releases/latest" in installer_source:
        violations.append(f"{installer}: latest release API를 사용하면 안 됩니다")

    try:
        entries = json.loads(baseline.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        violations.append(f"{baseline}: JSON을 읽을 수 없습니다: {error}")
        entries = []
    if not isinstance(entries, list) or not entries:
        violations.append(f"{baseline}: 하나 이상의 history finding baseline이 필요합니다")
    else:
        for index, entry in enumerate(entries):
            if not isinstance(entry, dict):
                violations.append(f"{baseline}: entry[{index}]가 객체가 아닙니다")
                continue
            for field in ("RuleID", "File", "Commit", "Fingerprint"):
                if not entry.get(field):
                    violations.append(f"{baseline}: entry[{index}]에 {field}가 없습니다")
            if entry.get("Secret") != "REDACTED":
                violations.append(f"{baseline}: entry[{index}] Secret은 REDACTED여야 합니다")
            if "REDACTED" not in str(entry.get("Match", "")):
                violations.append(f"{baseline}: entry[{index}] Match는 redacted여야 합니다")

    return violations


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--static", action="store_true", help="저장소의 Gitleaks 계약을 검증")
    args = parser.parse_args(argv)
    del args

    violations = validate_source()
    if violations:
        for violation in violations:
            print(f"Gitleaks contract FAILED: {violation}", file=sys.stderr)
        return 1
    print(
        "Gitleaks contract OK: pinned installer, bounded retry, checksum, full history, and redacted baseline"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
