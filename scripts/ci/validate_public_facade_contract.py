#!/usr/bin/env python3
"""Validate that selected public Kotlin facades remain non-inline wrappers."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

PUBLIC_FACADES: dict[str, tuple[str, ...]] = {
    "leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulLeaderElector.kt": (
        "runIfLeader",
        "runAsyncIfLeader",
    ),
    "leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulLeaderGroupElector.kt": (
        "runIfLeaderGroup",
        "runAsyncIfLeaderGroup",
    ),
    "leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulSuspendLeaderElector.kt": (
        "suspendRunIfLeader",
    ),
    "leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulSuspendLeaderGroupElector.kt": (
        "suspendRunIfLeaderGroup",
    ),
    "leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcLeaderElectorExtensions.kt": (
        "suspendRunIfLeader",
        "suspendRunIfLeaderGroup",
    ),
}


def _header(lines: list[str], start: int) -> str:
    collected: list[str] = []
    for line in lines[start:]:
        collected.append(line)
        if "):" in line:
            return "\n".join(collected)
    return "\n".join(collected)


def validate_text(path: str, text: str) -> list[str]:
    lines = text.splitlines()
    errors: list[str] = []
    for function in PUBLIC_FACADES.get(path, ()):
        matches = [
            index
            for index, line in enumerate(lines)
            if re.search(rf"^\s*(?:suspend\s+)?(?:inline\s+)?fun\s+<T>\s+[^\s]+\.{function}\s*\(", line)
        ]
        if len(matches) != 1:
            errors.append(f"{path}#{function}: expected exactly one public facade declaration, found {len(matches)}")
            continue
        header = _header(lines, matches[0])
        if re.search(r"\binline\b", header):
            errors.append(f"{path}#{function}: public facade must not be inline")
        if re.search(r"\bcrossinline\b", header):
            errors.append(f"{path}#{function}: public facade must not use crossinline")
    return errors


def validate_sources(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    for relative, functions in PUBLIC_FACADES.items():
        del functions
        path = root / relative
        if not path.is_file():
            errors.append(f"missing public facade source: {relative}")
            continue
        errors.extend(validate_text(relative, path.read_text(encoding="utf-8")))
    return errors


def main() -> int:
    errors = validate_sources()
    if errors:
        print("Public Kotlin facade contract FAILED:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("Public Kotlin facade contract OK: Consul/R2DBC public wrappers remain non-inline")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
