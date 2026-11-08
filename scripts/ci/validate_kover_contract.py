#!/usr/bin/env python3
"""CI/Nightly Kover 실행 그래프와 fail-closed 집계 계약을 검증한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WORKFLOWS = (
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/nightly-tests.yml",
)


def _job_block(source: str, job_id: str) -> str | None:
    marker = f"  {job_id}:"
    start = source.find(marker)
    if start < 0 or (start and source[start - 1] != "\n"):
        return None
    next_job = re.search(
        r"^  [A-Za-z0-9][A-Za-z0-9_-]*:\s*$",
        source[start + len(marker):],
        flags=re.MULTILINE,
    )
    if next_job is None:
        return source[start:]
    return source[start: start + len(marker) + next_job.start()]


def _step_block(block: str, step_name: str) -> str | None:
    marker = f"- name: {step_name}"
    start = block.find(marker)
    if start < 0:
        return None
    step_start = block.rfind("\n", 0, start) + 1
    next_step = re.search(r"^      - name:", block[start + len(marker):], flags=re.MULTILINE)
    if next_step is None:
        return block[step_start:]
    return block[step_start: start + len(marker) + next_step.start()]


def _gradle_kover_commands(source: str) -> list[str]:
    return [
        line.strip()
        for line in source.splitlines()
        if "./gradlew" in line and "koverXmlReport" in line
    ]


def _coverage_artifact_uploads(source: str) -> list[tuple[str, str]]:
    uploads: list[tuple[str, str]] = []
    for job in re.finditer(r"^  ([A-Za-z0-9][A-Za-z0-9_-]*):\s*$", source, re.MULTILINE):
        block = _job_block(source, job.group(1))
        if block is None:
            continue
        upload = _step_block(block, "Upload coverage report")
        if upload is None:
            continue
        match = re.search(
            r"^\s+name:\s+(coverage-[A-Za-z0-9_-]+)\s*$",
            upload,
            flags=re.MULTILINE,
        )
        if match:
            uploads.append((job.group(1), match.group(1)))
    return sorted(uploads)


def _needs_job(block: str, job_id: str) -> bool:
    return bool(
        re.search(
            rf"(?:^|\[|,|-)\s*{re.escape(job_id)}\s*(?:$|\]|,)",
            block,
            flags=re.MULTILINE,
        )
    )


def _validate_nightly_gate_contract(source: str, path: Path) -> list[str]:
    if path.name != "nightly-tests.yml":
        return []

    nightly_status = _job_block(source, "nightly-status")
    if nightly_status is None:
        return [f"{path}: nightly-status job이 없습니다"]

    check_step = _step_block(nightly_status, "Check all jobs")
    if check_step is None:
        return [f"{path}: nightly-status에 Check all jobs step이 없습니다"]

    required_job_markers = (
        "actions/checkout@v7",
        "actions/setup-python@v7",
    )
    required_check_markers = (
        "scripts/ci/validate_nightly_gate.py",
        "--results",
        "--full-scope",
        "--cluster-result",
    )
    if any(marker not in nightly_status for marker in required_job_markers) or any(
        marker not in check_step for marker in required_check_markers
    ):
        return [
            f"{path}: nightly-status는 checkout/setup-python 후 scripts/ci/validate_nightly_gate.py로 Full Nightly 결과를 fail-closed 검증해야 합니다"
        ]
    return []


def _validate_k8s_coverage_contract(source: str, path: Path) -> list[str]:
    coverage_report = _job_block(source, "coverage-report")
    if coverage_report is None or "test-leader-k8s" not in coverage_report:
        return []

    violations: list[str] = []
    k8s_job = _job_block(source, "test-leader-k8s")
    if k8s_job is None:
        return [f"{path}: coverage-report가 의존하는 test-leader-k8s job이 없습니다"]

    k8s_commands = [line.strip() for line in k8s_job.splitlines() if "./gradlew" in line]
    if not any(
            all(
                task in command
                for task in (
                        ":bluetape4k-leader-k8s:test",
                        ":bluetape4k-leader-k8s:k8sTest",
                        ":bluetape4k-leader-k8s:koverXmlReport",
                )
            )
            for command in k8s_commands
    ):
        violations.append(
            f"{path}: test-leader-k8s는 test, k8sTest, koverXmlReport를 same Gradle invocation으로 실행해야 합니다"
        )

    upload = _step_block(k8s_job, "Upload coverage report")
    if upload is None:
        violations.append(f"{path}: test-leader-k8s에 coverage artifact upload step이 없습니다")
    elif "if-no-files-found: error" not in upload:
        violations.append(f"{path}: test-leader-k8s coverage upload은 if-no-files-found: error여야 합니다")

    if _job_block(source, "changes") is not None and "needs.changes.outputs['leader-k8s']" not in coverage_report:
        violations.append(f"{path}: coverage-report의 영향 필터에 leader-k8s가 없습니다")

    return violations


def validate_workflow(path: Path) -> list[str]:
    if path.is_symlink() or not path.is_file():
        return [f"{path}: workflow 파일이 없습니다"]

    source = path.read_text(encoding="utf-8")
    violations: list[str] = []
    commands = _gradle_kover_commands(source)
    if not commands:
        violations.append(f"{path}: koverXmlReport 실행이 없습니다")

    for command in commands:
        modules = re.findall(r"(:[A-Za-z0-9_-]+):koverXmlReport\b", command)
        if not modules:
            violations.append(f"{path}: Kover 명령에서 모듈을 찾을 수 없습니다: {command}")
            continue
        if re.search(r"(?:^|\s)-x\s+test(?:\s|$)", command):
            violations.append(f"{path}: Kover 명령이 test를 건너뜁니다: {command}")
        for module in modules:
            if f"{module}:test" not in command:
                violations.append(
                    f"{path}: {module}:test와 koverXmlReport는 same Gradle invocation이어야 합니다"
                )

    if "Generate Kover XML report" in source:
        violations.append(f"{path}: 독립적인 Generate Kover XML report step이 남아 있습니다")

    coverage_steps = [
        block
        for job in re.finditer(r"^  [A-Za-z0-9][A-Za-z0-9_-]*:\s*$", source, re.MULTILINE)
        for block in [_job_block(source, job.group(0).strip().rstrip(":"))]
        if block and "Upload coverage report" in block
    ]
    for block in coverage_steps:
        step = _step_block(block, "Upload coverage report")
        if step is None or "if-no-files-found: error" not in step:
            violations.append(f"{path}: coverage upload은 if-no-files-found: error여야 합니다")

    coverage_report = _job_block(source, "coverage-report")
    if coverage_report is not None:
        if _job_block(source, "changes") is not None:
            if "- changes" not in coverage_report:
                violations.append(f"{path}: coverage-report는 changes job에 의존해야 합니다")
            if "needs.changes.outputs" not in coverage_report or "workflow_dispatch" not in coverage_report:
                violations.append(
                    f"{path}: coverage-report는 영향받은 coverage job 또는 workflow_dispatch일 때만 실행되어야 합니다"
                )
        download = _step_block(coverage_report, "Download all coverage artifacts")
        if download is None:
            violations.append(f"{path}: coverage-report에 artifact download step이 없습니다")
        elif "continue-on-error: true" in download:
            violations.append(f"{path}: coverage artifact download가 continue-on-error로 fail-open입니다")
        aggregate = _step_block(coverage_report, "Aggregate Kover coverage summary")
        if aggregate is None or "aggregate-kover-coverage.py" not in aggregate:
            violations.append(f"{path}: coverage-report에 Kover 집계 step이 없습니다")
        elif path.name == "nightly-tests.yml":
            artifact_uploads = _coverage_artifact_uploads(source)
            artifact_names = [name for _, name in artifact_uploads]
            expected_artifacts = set(
                re.findall(r"--expected-artifact\s+([A-Za-z0-9_-]+)", aggregate)
            )
            if not artifact_names:
                violations.append(
                    f"{path}: Full Nightly coverage producer가 하나 이상 필요합니다"
                )
            elif len(artifact_names) != len(set(artifact_names)):
                violations.append(
                    f"{path}: Full Nightly coverage artifact 이름은 producer job 사이에서 중복될 수 없습니다"
                )
            if artifact_names and expected_artifacts != set(artifact_names):
                violations.append(
                    f"{path}: Full Nightly coverage 집계는 모든 coverage artifact를 "
                    "--expected-artifact로 전달해야 합니다"
                )
            for job_id, artifact_name in artifact_uploads:
                if not _needs_job(coverage_report, job_id):
                    violations.append(
                        f"{path}: coverage-report는 {artifact_name} producer인 {job_id} job을 needs에 포함해야 합니다"
                    )

    violations.extend(_validate_k8s_coverage_contract(source, path))
    violations.extend(_validate_nightly_gate_contract(source, path))

    return violations


def validate_workflows(workflows: tuple[Path, ...]) -> list[str]:
    if not workflows:
        return ["검증할 workflow가 없습니다"]
    violations: list[str] = []
    for workflow in workflows:
        violations.extend(validate_workflow(workflow))
    return violations


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workflow-contract", nargs="+", type=Path)
    parser.add_argument("--static", action="store_true", help="기본 CI/Nightly workflow를 검증")
    args = parser.parse_args(argv)
    workflows = tuple(args.workflow_contract or DEFAULT_WORKFLOWS)
    violations = validate_workflows(workflows)
    if violations:
        for violation in violations:
            print(f"Kover contract FAILED: {violation}", file=sys.stderr)
        return 1
    print("Kover contract OK: test and report share one Gradle graph; aggregation fails closed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
