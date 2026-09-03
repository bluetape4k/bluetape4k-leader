#!/usr/bin/env python3
"""
Kover XML 리포트를 집계해서 GitHub Step Summary에 모듈별 coverage 표를 출력합니다.

Usage:
    aggregate-kover-coverage.py [coverage-root] [--expected-artifact NAME ...]
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


class CoverageReportError(ValueError):
    """Kover report가 집계에 사용할 수 없을 때 발생하는 오류."""


def parse_report(path: str) -> tuple[int, int]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        raise CoverageReportError(f"{path}: malformed Kover XML ({error})") from error

    counters = [
        counter for counter in root.findall("counter") if counter.get("type") == "INSTRUCTION"
    ]
    if len(counters) != 1:
        raise CoverageReportError(
            f"{path}: exactly one INSTRUCTION counter is required (found {len(counters)})"
        )

    counter = counters[0]
    try:
        covered = int(counter.get("covered", ""))
        missed = int(counter.get("missed", ""))
    except (TypeError, ValueError) as error:
        raise CoverageReportError(f"{path}: INSTRUCTION counter values must be integers") from error
    if covered < 0 or missed < 0:
        raise CoverageReportError(f"{path}: INSTRUCTION counter values must be non-negative")
    if covered + missed == 0:
        raise CoverageReportError(f"{path}: INSTRUCTION counter is empty")
    return covered, missed


def module_from_path(root_dir: str, path: str) -> str:
    rel = os.path.relpath(path, root_dir)
    parts = rel.split(os.sep)
    for i in range(len(parts) - 1, -1, -1):
        if parts[i] == "build" and i >= 1:
            return parts[i - 1]
    return os.path.basename(os.path.dirname(os.path.dirname(path)))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root_dir", nargs="?", default="coverage-artifacts")
    parser.add_argument("--expected-artifact", action="append", default=[])
    args = parser.parse_args(argv)
    root_dir = args.root_dir
    expected_artifacts = args.expected_artifact
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")

    patterns = [f"{root_dir}/**/report.xml", f"{root_dir}/**/reportJvm.xml"]
    rows: list[tuple[str, int, int, float]] = []
    total_covered = total_missed = 0
    errors: list[str] = []

    for pattern in patterns:
        for xml_path in sorted(glob.glob(pattern, recursive=True)):
            module = module_from_path(root_dir, xml_path)
            try:
                covered, missed = parse_report(xml_path)
            except CoverageReportError as error:
                errors.append(str(error))
                continue
            total = covered + missed
            pct = (covered * 100.0 / total) if total else 0.0
            rows.append((module, covered, missed, pct))
            total_covered += covered
            total_missed += missed

    artifact_root = Path(root_dir)
    artifact_dirs = (
        sorted(path for path in artifact_root.glob("coverage-*") if path.is_dir())
        if artifact_root.is_dir()
        else []
    )
    for artifact_dir in artifact_dirs:
        if not any(artifact_dir.glob("**/report.xml")) and not any(
            artifact_dir.glob("**/reportJvm.xml")
        ):
            errors.append(f"{artifact_dir}: no Kover XML report found")
    for expected_artifact in expected_artifacts:
        expected_path = artifact_root / expected_artifact
        if not expected_path.is_dir():
            errors.append(f"{expected_path}: expected coverage artifact is missing")
        elif not any(expected_path.glob("**/report.xml")) and not any(
            expected_path.glob("**/reportJvm.xml")
        ):
            errors.append(f"{expected_path}: expected Kover XML report is missing")

    if not rows and not errors:
        errors.append(f"{root_dir}: No coverage reports found")

    lines: list[str] = ["## Kover Coverage Summary", ""]
    if rows:
        lines += [
            "| Module | Instruction Covered | Instruction Missed | Coverage |",
            "|--------|--------------------:|-------------------:|---------:|",
        ]
        for module, covered, missed, pct in rows:
            lines.append(f"| `{module}` | {covered} | {missed} | {pct:.2f}% |")
        grand_total = total_covered + total_missed
        grand_pct = (total_covered * 100.0 / grand_total) if grand_total else 0.0
        lines.append(f"| **TOTAL** | **{total_covered}** | **{total_missed}** | **{grand_pct:.2f}%** |")
    if errors:
        lines += ["", "### Coverage validation errors", ""]
        lines.extend(f"- {error}" for error in errors)

    output = "\n".join(lines) + "\n"
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as fp:
            fp.write(output)
    print(output)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
