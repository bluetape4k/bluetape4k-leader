#!/usr/bin/env python3
"""Detekt production-source guard의 configuration-cache 계약을 검증한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


GUARD_MARKER = "val detektProductionSourceGuard = tasks.register"
FORBIDDEN_ACTION_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bsubprojects\b"), "subprojects 참조"),
    (re.compile(r"\brootProject\b"), "rootProject 참조"),
    (re.compile(r"\.fileTree\s*\("), "Project.fileTree 호출"),
    (re.compile(r"\b(?:subproject|project)\.file\s*\("), "Project.file 호출"),
)


def _guard_action(source: str) -> str:
    guard_start = source.find(GUARD_MARKER)
    if guard_start < 0:
        raise ValueError(f"{GUARD_MARKER!r}를 찾을 수 없습니다")

    action_start = source.find("doLast {", guard_start)
    if action_start < 0:
        raise ValueError("detektProductionSourceGuard의 doLast를 찾을 수 없습니다")

    action_end = source.find("gradle.projectsEvaluated", action_start)
    if action_end < 0:
        raise ValueError("detektProductionSourceGuard의 doLast 경계를 찾을 수 없습니다")
    return source[action_start:action_end]


def validate_source(root: Path) -> list[str]:
    path = root.resolve() / "build.gradle.kts"
    if path.is_symlink() or not path.is_file():
        return [f"{path}: build.gradle.kts가 없습니다"]

    source = path.read_text(encoding="utf-8")
    try:
        action = _guard_action(source)
    except ValueError as error:
        return [f"{path}: {error}"]

    violations = [
        f"{path}: Detekt guard doLast에서 {description}을 제거하십시오"
        for pattern, description in FORBIDDEN_ACTION_PATTERNS
        if pattern.search(action)
    ]
    if "doLast { task ->" not in action and "val task = this" not in action:
        violations.append(f"{path}: doLast는 task receiver를 명시적으로 받아야 합니다")
    if "task.logger.lifecycle(" not in action:
        violations.append(f"{path}: 실행 로깅은 task.logger를 사용해야 합니다")
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    violations = validate_source(args.root)
    if violations:
        for violation in violations:
            print(f"::error::{violation}")
        return 1
    print("Detekt configuration-cache guard contract OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
