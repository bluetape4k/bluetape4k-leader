#!/usr/bin/env python3
"""Exposed provider별 테스트·Kover artifact provenance 계약을 검증한다."""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

EXPECTED_JOBS: dict[str, tuple[str, str]] = {
    "test-exposed-jdbc-h2": ("leader-exposed-jdbc", "H2"),
    "test-exposed-jdbc-postgresql": ("leader-exposed-jdbc", "POSTGRESQL"),
    "test-exposed-jdbc-mysql": ("leader-exposed-jdbc", "MYSQL_V8"),
    "test-exposed-r2dbc-h2": ("leader-exposed-r2dbc", "H2"),
    "test-exposed-r2dbc-postgresql": ("leader-exposed-r2dbc", "POSTGRESQL"),
    "test-exposed-r2dbc-mysql": ("leader-exposed-r2dbc", "MYSQL_V8"),
}
EXPECTED_MODULES = frozenset(module for module, _ in EXPECTED_JOBS.values())
EXPECTED_PROVIDERS = frozenset(provider for _, provider in EXPECTED_JOBS.values())
MARKER_NAME = "leader-test-db.txt"
PROVIDER_ARTIFACT_SUFFIX = {
    "H2": "h2",
    "POSTGRESQL": "postgresql",
    "MYSQL_V8": "mysql",
}


def _job_block(source: str, job_id: str) -> str | None:
    marker = f"  {job_id}:"
    start = source.find(marker)
    if start < 0 or (start and source[start - 1] != "\n"):
        return None
    next_job_match = re.search(
        r"^  [A-Za-z0-9][A-Za-z0-9_-]*:\s*$",
        source[start + len(marker) :],
        flags=re.MULTILINE,
    )
    if next_job_match is None:
        return source[start:]
    next_job = start + len(marker) + next_job_match.start()
    return source[start:next_job]


def _step_block(block: str, step_name: str) -> str | None:
    marker = f"- name: {step_name}"
    start = block.find(marker)
    if start < 0:
        return None
    step_start = block.rfind("\n", 0, start) + 1
    next_step = block.find("\n      - name:", start + len(marker))
    return block[step_start:] if next_step < 0 else block[step_start : next_step + 1]


def _step_block_with_name_prefix(block: str, step_name_prefix: str) -> str | None:
    match = re.search(
        rf"^      - name: {re.escape(step_name_prefix)}[^\n]*$",
        block,
        flags=re.MULTILINE,
    )
    if match is None:
        return None
    step_start = match.start()
    next_step = re.search(r"^      - name:", block[match.end() :], flags=re.MULTILINE)
    if next_step is None:
        return block[step_start:]
    return block[step_start : match.end() + next_step.start()]


def _step_field_block(step: str, field_name: str) -> str:
    marker = f"        {field_name}:"
    start = step.find(marker)
    if start < 0 or (start and step[start - 1] != "\n"):
        return ""
    next_field = re.search(
        r"^        [A-Za-z0-9_-]+:",
        step[start + len(marker) :],
        flags=re.MULTILINE,
    )
    return step[start:] if next_field is None else step[start : start + len(marker) + next_field.start()]


def _has_env_provider(step: str, provider: str) -> bool:
    env = _step_field_block(step, "env")
    return re.search(
        rf'^          LEADER_TEST_DB:\s*"{re.escape(provider)}"\s*$',
        env,
        flags=re.MULTILINE,
    ) is not None


def _has_with_name(step: str, expected_name: str) -> bool:
    with_block = _step_field_block(step, "with")
    return re.search(
        rf"^          name:\s*{re.escape(expected_name)}\s*$",
        with_block,
        flags=re.MULTILINE,
    ) is not None


def _with_contains(step: str, value: str) -> bool:
    return value in _step_field_block(step, "with")


def _normalise_provider(provider: str) -> str:
    value = provider.strip().upper()
    aliases = {"POSTGRES": "POSTGRESQL", "MYSQL": "MYSQL_V8"}
    return aliases.get(value, value)


def validate_build_source(root: Path) -> list[str]:
    path = root.resolve() / "build.gradle.kts"
    if path.is_symlink() or not path.is_file():
        return [f"{path}: build.gradle.kts가 없습니다"]

    source = path.read_text(encoding="utf-8")
    violations: list[str] = []
    required_patterns = {
        r"providers\s*\.\s*environmentVariable\(\"LEADER_TEST_DB\"\)": "LEADER_TEST_DB Provider 입력",
        r"map\s*\{[\s\S]*?trim\(\)\.uppercase\(\)[\s\S]*?\}": "provider 정규화",
        r'orElse\("ALL"\)': "provider 기본값",
        r"test-results/test/leader-test-db\.txt": "테스트 marker 출력",
        r"reports/kover/leader-test-db\.txt": "Kover marker 출력",
        r'matching\s*\{\s*it\.name\s*==\s*"koverXmlReport"\s*\}': "Kover report task 구성",
    }
    for pattern, description in required_patterns.items():
        if re.search(pattern, source) is None:
            violations.append(f"{path}: {description}이 없습니다 ({pattern})")

    input_count = source.count('inputs.property("leaderTestDb"')
    if input_count < 2:
        violations.append(
            f"{path}: test와 Kover report 모두에 leaderTestDb 입력을 선언해야 합니다 (현재 {input_count}개)"
        )
    if source.count("LEADER_TEST_DB=") < 2:
        violations.append(f"{path}: test와 Kover marker에 provider 값을 기록해야 합니다")
    if source.count("providerMarker.get().asFile.delete()") < 2:
        violations.append(f"{path}: test와 Kover 실행 전에 이전 provider marker를 삭제해야 합니다")
    return violations


def validate_workflow(path: Path) -> list[str]:
    if path.is_symlink() or not path.is_file():
        return [f"{path}: workflow 파일이 없습니다"]

    source = path.read_text(encoding="utf-8")
    violations: list[str] = []
    for job_id, (module, provider) in EXPECTED_JOBS.items():
        block = _job_block(source, job_id)
        if block is None:
            violations.append(f"{path}: {job_id} job이 없습니다")
            continue

        gradle_task = f":bluetape4k-{module}:test"
        if gradle_task not in block:
            violations.append(f"{path}: {job_id}에 {gradle_task} 실행이 없습니다")

        test_step = _step_block_with_name_prefix(block, f"Test {module}")
        if test_step is None or not _has_env_provider(test_step, provider):
            violations.append(f"{path}: {job_id} test env에 LEADER_TEST_DB={provider}가 없습니다")

        kover = _step_block(block, "Generate Kover XML report")
        if kover is None:
            violations.append(f"{path}: {job_id}에 Kover report step이 없습니다")
        elif not _has_env_provider(kover, provider):
            violations.append(
                f"{path}: {job_id} Kover report env에 LEADER_TEST_DB={provider}가 없습니다"
            )

        test_upload = _step_block(block, "Upload test results")
        artifact_suffix = PROVIDER_ARTIFACT_SUFFIX[provider]
        artifact_module = module.removeprefix("leader-")
        expected_test_artifact = f"test-results-{artifact_module}-{artifact_suffix}"
        if (
            test_upload is None
            or not _has_with_name(test_upload, expected_test_artifact)
            or not _with_contains(test_upload, "**/build/test-results/test/*.xml")
            or not _with_contains(test_upload, MARKER_NAME)
        ):
            violations.append(
                f"{path}: {job_id} test artifact 이름/내용이 {expected_test_artifact}/{MARKER_NAME}이어야 합니다"
            )

        coverage_upload = _step_block(block, "Upload coverage report")
        expected_coverage_artifact = f"coverage-{artifact_module}-{artifact_suffix}"
        if (
            coverage_upload is None
            or not _has_with_name(coverage_upload, expected_coverage_artifact)
            or not _with_contains(coverage_upload, "**/build/reports/kover/")
            or not _with_contains(coverage_upload, MARKER_NAME)
        ):
            violations.append(
                f"{path}: {job_id} coverage artifact 이름/경로/marker가 올바르지 않습니다"
            )

        verify = _step_block(block, "Verify provider artifact provenance")
        expected_args = (
            "validate_provider_artifacts.py",
            f"--module {module}",
            f"--provider {provider}",
        )
        if verify is None or any(argument not in verify for argument in expected_args):
            violations.append(
                f"{path}: {job_id}에 {module}/{provider} provenance 검증 step이 없습니다"
            )
    return violations


def validate_workflow_contract(workflows: tuple[Path, ...]) -> list[str]:
    if not workflows:
        return ["검증할 workflow가 없습니다"]
    violations = []
    for workflow in workflows:
        violations.extend(validate_workflow(workflow))
    root = workflows[0].resolve().parents[2]
    violations.extend(validate_build_source(root))
    return violations


def _read_marker(path: Path, expected: str, label: str) -> list[str]:
    if path.is_symlink() or not path.is_file():
        return [f"{label}: {path}가 없습니다"]
    try:
        lines = [line.strip() for line in path.read_text(encoding="utf-8").splitlines()]
    except OSError as error:
        return [f"{label}: {path}를 읽을 수 없습니다 ({error})"]
    actual = f"LEADER_TEST_DB={expected}"
    if lines != [actual]:
        return [f"{label}: {path} 내용이 {actual!r}와 다릅니다"]
    return []


def validate_artifacts(root: Path, module: str, provider: str) -> list[str]:
    module = module.strip()
    provider = _normalise_provider(provider)
    if module not in EXPECTED_MODULES:
        return [f"지원하지 않는 module: {module}"]
    if provider not in EXPECTED_PROVIDERS:
        return [f"지원하지 않는 provider: {provider}"]

    module_root = root.resolve() / module / "build"
    test_root = module_root / "test-results" / "test"
    kover_root = module_root / "reports" / "kover"
    violations = []
    test_xml = sorted(test_root.rglob("*.xml")) if test_root.is_dir() else []
    if not test_xml:
        violations.append(f"{module}: test result XML이 없습니다 ({test_root})")
    kover_xml = sorted(kover_root.rglob("*.xml")) if kover_root.is_dir() else []
    if not kover_xml:
        violations.append(f"{module}: Kover XML이 없습니다 ({kover_root})")
    violations.extend(
        _read_marker(test_root / MARKER_NAME, provider, "test provider marker")
    )
    violations.extend(
        _read_marker(kover_root / MARKER_NAME, provider, "Kover provider marker")
    )
    return violations


def _self_test() -> list[str]:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        test_root = root / "leader-exposed-jdbc/build/test-results/test"
        kover_root = root / "leader-exposed-jdbc/build/reports/kover"
        test_root.mkdir(parents=True)
        kover_root.mkdir(parents=True)
        (test_root / "TEST-self.xml").write_text("<testsuite/>", encoding="utf-8")
        (kover_root / "report.xml").write_text("<report/>", encoding="utf-8")
        (test_root / MARKER_NAME).write_text("LEADER_TEST_DB=H2\n", encoding="utf-8")
        (kover_root / MARKER_NAME).write_text("LEADER_TEST_DB=H2\n", encoding="utf-8")
        violations = validate_artifacts(root, "leader-exposed-jdbc", "H2")
        if violations:
            return violations
        (kover_root / MARKER_NAME).write_text("LEADER_TEST_DB=MYSQL_V8\n", encoding="utf-8")
        if not validate_artifacts(root, "leader-exposed-jdbc", "H2"):
            return ["불일치 provider marker를 거부하지 못했습니다"]
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--workflow-contract",
        nargs="+",
        type=Path,
        metavar="WORKFLOW",
        help="CI/Nightly workflow의 provider fan-out 계약을 검증한다",
    )
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--module")
    parser.add_argument("--provider")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        violations = _self_test()
    elif args.workflow_contract:
        violations = validate_workflow_contract(tuple(path.resolve() for path in args.workflow_contract))
    elif args.module and args.provider:
        violations = validate_artifacts(args.root, args.module, args.provider)
    else:
        parser.error("--workflow-contract, --self-test, 또는 --module과 --provider가 필요합니다")

    if violations:
        for violation in violations:
            print(f"::error::{violation}")
        return 1
    print("Exposed provider artifact contract OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
