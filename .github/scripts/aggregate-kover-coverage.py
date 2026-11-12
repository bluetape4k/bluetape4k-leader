#!/usr/bin/env python3
"""
Kover XML 리포트를 집계해서 GitHub Step Summary에 모듈별 coverage 표를 출력합니다.

Usage:
    aggregate-kover-coverage.py [coverage-root] [--expected-artifact NAME ...]
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


class CoverageReportError(ValueError):
    """Kover report가 집계에 사용할 수 없을 때 발생하는 오류."""


@dataclass(frozen=True)
class CoverageCandidate:
    module: str
    report_variant: str
    artifact: str
    path: str
    covered: int
    missed: int

    @property
    def instruction_total(self) -> int:
        return self.covered + self.missed

    @property
    def coverage(self) -> float:
        return self.covered / self.instruction_total


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


def artifact_from_path(root_dir: str, path: str) -> str:
    rel = Path(os.path.relpath(path, root_dir))
    for part in rel.parts:
        if part.startswith("coverage-"):
            return part
    return rel.parts[0] if rel.parts else Path(path).parent.name


def report_variant(path: str) -> str:
    """Report filename is the stable source/report-set discriminator."""
    return Path(path).stem


def select_candidate(candidates: list[CoverageCandidate]) -> CoverageCandidate:
    totals = {candidate.instruction_total for candidate in candidates}
    if len(totals) != 1:
        details = ", ".join(
            f"{candidate.artifact}={candidate.instruction_total}"
            for candidate in sorted(candidates, key=lambda item: item.artifact)
        )
        raise CoverageReportError(
            f"{candidates[0].module}/{candidates[0].report_variant}: "
            f"instruction denominator differs across artifacts ({details})"
        )

    # Matrix jobs exercise the same source set. Keep the best deterministic
    # representative instead of counting the same instructions once per DB.
    return min(
        candidates,
        key=lambda candidate: (
            -candidate.coverage,
            -candidate.covered,
            candidate.missed,
            candidate.artifact,
            candidate.path,
        ),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root_dir", nargs="?", default="coverage-artifacts")
    parser.add_argument("--expected-artifact", action="append", default=[])
    args = parser.parse_args(argv)
    root_dir = args.root_dir
    expected_artifacts = args.expected_artifact
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")

    patterns = [f"{root_dir}/**/report.xml", f"{root_dir}/**/reportJvm.xml"]
    candidates_by_key: dict[tuple[str, str], list[CoverageCandidate]] = {}
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
            candidate = CoverageCandidate(
                module=module,
                report_variant=report_variant(xml_path),
                artifact=artifact_from_path(root_dir, xml_path),
                path=xml_path,
                covered=covered,
                missed=missed,
            )
            candidates_by_key.setdefault(
                (candidate.module, candidate.report_variant), []
            ).append(candidate)

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

    rows: list[CoverageCandidate] = []
    collapsed: list[tuple[str, str, int, str]] = []
    for key in sorted(candidates_by_key):
        candidates = candidates_by_key[key]
        try:
            selected = select_candidate(candidates)
        except CoverageReportError as error:
            errors.append(str(error))
            continue
        rows.append(selected)
        if len(candidates) > 1:
            collapsed.append(
                (
                    selected.module,
                    selected.report_variant,
                    len(candidates),
                    selected.artifact,
                )
            )
        total_covered += selected.covered
        total_missed += selected.missed

    if not rows and not errors:
        errors.append(f"{root_dir}: No coverage reports found")

    lines: list[str] = ["## Kover Coverage Summary", ""]
    if rows:
        lines += [
            "| Module | Report variant | Selected artifact | Instruction Covered | Instruction Missed | Coverage |",
            "|--------|----------------|-------------------|--------------------:|-------------------:|---------:|",
        ]
        for row in rows:
            lines.append(
                f"| `{row.module}` | `{row.report_variant}` | `{row.artifact}` | "
                f"{row.covered} | {row.missed} | {row.coverage * 100:.2f}% |"
            )
        grand_total = total_covered + total_missed
        grand_pct = (total_covered * 100.0 / grand_total) if grand_total else 0.0
        lines.append(
            f"| **TOTAL** | **{len(rows)} unique reports** | — | **{total_covered}** | "
            f"**{total_missed}** | **{grand_pct:.2f}%** |"
        )
    if collapsed:
        lines += [
            "",
            "### Deduplicated matrix reports",
            "",
            "동일 module/report variant의 matrix artifact는 instruction coverage가 "
            "가장 높은 대표 artifact 하나만 TOTAL에 포함했습니다.",
            "",
            "| Module | Report variant | Candidates | Selected artifact |",
            "|--------|----------------|----------:|-------------------|",
        ]
        for module, variant, count, artifact in collapsed:
            lines.append(f"| `{module}` | `{variant}` | {count} | `{artifact}` |")
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
