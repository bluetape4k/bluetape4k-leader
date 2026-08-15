#!/usr/bin/env python3
"""TEST-02 대상 Kotlin 테스트의 무제한·wall-clock timing 의존성을 검출한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


TARGET_PATTERNS: dict[str, tuple[tuple[re.Pattern[str], str], ...]] = {
    "leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonSuspendLeaderGroupElectorTest.kt": (
        (re.compile(r"(?m)^\s*while\s*\("), "무제한 polling"),
    ),
    "leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtenderStressTest.kt": (
        (re.compile(r"\bdelay\(5\.seconds\)"), "고정 delay"),
        (re.compile(r"\bThread\.sleep\s*\("), "Thread.sleep"),
        (
            re.compile(r"\.await\(\s*600\s*,\s*TimeUnit\.MILLISECONDS\s*\)"),
            "latch deadline",
        ),
    ),
    "leader-core/src/test/kotlin/io/bluetape4k/leader/coroutines/LocalSuspendLeaderElectorTest.kt": (
        (re.compile(r"\bdelay\(300\.milliseconds\)"), "wall-clock delay"),
    ),
}


def _line_number(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def validate_source(root: Path, relative_path: str) -> list[str]:
    path = root / relative_path
    if path.is_symlink() or not path.is_file():
        return []

    source = path.read_text(encoding="utf-8")
    violations: list[str] = []
    for pattern, description in TARGET_PATTERNS[relative_path]:
        for match in pattern.finditer(source):
            violations.append(
                f"{path}:{_line_number(source, match.start())}: {description}는 "
                "bounded helper 또는 controllable fixture로 대체하십시오"
            )
    return violations


def validate_sources(root: Path) -> list[str]:
    """Validate the three TEST-02 timing-sensitive sources under ``root``."""

    resolved_root = root.resolve()
    return [
        violation
        for relative_path in TARGET_PATTERNS
        for violation in validate_source(resolved_root, relative_path)
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    violations = validate_sources(args.root)
    if violations:
        for violation in violations:
            print(f"::error::{violation}")
        return 1
    print("Kotlin timing contract OK: bounded polling and controllable fixtures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
