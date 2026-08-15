#!/usr/bin/env python3
"""Kotlin 테스트의 예외·assertion contract 위반을 정적으로 검출한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


FORBIDDEN_KOTLIN_TEST_IMPORT = re.compile(
    r"^\s*import\s+kotlin\.test\.assert[A-Z]\w*\b"
)
FORBIDDEN_JUNIT_IMPORT = re.compile(
    r"^\s*import\s+org\.junit\.jupiter\.api\."
    r"(?:assertDoesNotThrow|assertThrows|Assertions\.)"
)
FORBIDDEN_JUNIT_CALL = re.compile(
    r"\b(?:Assertions\.)?assert(?:DoesNotThrow|Throws|Equals|NotEquals|"
    r"True|False|Null|NotNull|Same|NotSame|ArrayEquals|IterableEquals|"
    r"LinesMatch|InstanceOf|All|Timeout)\s*\("
)


def kotlin_test_sources(root: Path) -> list[Path]:
    """Return library and example Kotlin test sources, excluding bootstrap tests."""

    module_roots = [path for path in root.glob("leader-*") if path.is_dir()]
    examples_root = root / "examples"
    if examples_root.is_dir():
        module_roots.append(examples_root)

    sources: list[Path] = []
    for module_root in module_roots:
        for source in module_root.rglob("*.kt"):
            if source.is_symlink() or not source.is_file():
                continue
            if "src" in source.parts and "test" in source.parts:
                sources.append(source)
    return sorted(sources)


def _matching_brace(source: str, opening: int) -> int | None:
    """Find a Kotlin block's closing brace while ignoring strings and comments."""

    depth = 0
    index = opening
    state = "code"
    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""

        if state == "line-comment":
            if char == "\n":
                state = "code"
            index += 1
            continue
        if state == "block-comment":
            if char == "*" and next_char == "/":
                state = "code"
                index += 2
            else:
                index += 1
            continue
        if state == "string":
            if char == "\\":
                index += 2
            elif char == '"':
                state = "code"
                index += 1
            else:
                index += 1
            continue
        if state == "triple-string":
            if source.startswith('"""', index):
                state = "code"
                index += 3
            else:
                index += 1
            continue
        if state == "char":
            if char == "\\":
                index += 2
            elif char == "'":
                state = "code"
                index += 1
            else:
                index += 1
            continue

        if char == "/" and next_char == "/":
            state = "line-comment"
            index += 2
            continue
        if char == "/" and next_char == "*":
            state = "block-comment"
            index += 2
            continue
        if source.startswith('"""', index):
            state = "triple-string"
            index += 3
            continue
        if char == '"':
            state = "string"
            index += 1
            continue
        if char == "'":
            state = "char"
            index += 1
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return None


def _line_number(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def _run_catching_violations(path: Path, source: str) -> list[str]:
    violations: list[str] = []
    for match in re.finditer(r"(?:kotlin\.)?\brunCatching\s*\{", source):
        closing = _matching_brace(source, source.find("{", match.start(), match.end()))
        if closing is None:
            continue
        suffix = source[closing + 1 : closing + 700]
        direct_access = re.match(r"\s*\.\s*(isFailure|isSuccess|exceptionOrNull)\b", suffix)
        variable_match = re.search(
            r"\b(?:val|var)\s+(\w+)\s*=\s*$",
            source[max(0, match.start() - 120) : match.start()],
        )
        variable_access = False
        if variable_match:
            variable = variable_match.group(1)
            variable_access = re.search(
                rf"\b{re.escape(variable)}\.(?:isFailure|isSuccess|exceptionOrNull)\b",
                suffix,
            ) is not None
        if direct_access or variable_access:
            violations.append(
                f"{path}:{_line_number(source, match.start())}: "
                "runCatching 결과로 예외 contract를 확인하지 말고 "
                "io.bluetape4k.assertions.assertFailsWith 또는 성공 호출을 사용하십시오"
            )
    return violations


def validate_source(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8")
    violations: list[str] = []
    for line_number, line in enumerate(source.splitlines(), start=1):
        if FORBIDDEN_KOTLIN_TEST_IMPORT.search(line):
            violations.append(
                f"{path}:{line_number}: kotlin.test assertion은 Bluetape assertion으로 대체하십시오"
            )
        if FORBIDDEN_JUNIT_IMPORT.search(line):
            violations.append(
                f"{path}:{line_number}: JUnit assertion import는 Bluetape assertion으로 대체하십시오"
            )
        if FORBIDDEN_JUNIT_CALL.search(line):
            violations.append(
                f"{path}:{line_number}: JUnit assertion 호출은 Bluetape assertion으로 대체하십시오"
            )
    violations.extend(_run_catching_violations(path, source))
    return violations


def validate_sources(root: Path) -> list[str]:
    """Validate all leader and example Kotlin test sources under root."""

    return [
        violation
        for source in kotlin_test_sources(root)
        for violation in validate_source(source)
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    violations = validate_sources(args.root.resolve())
    if violations:
        for violation in violations:
            print(f"::error::{violation}")
        return 1
    print("Kotlin test assertion contract OK: Bluetape assertions and explicit exception types")
    return 0


if __name__ == "__main__":
    sys.exit(main())
