#!/usr/bin/env python3
"""릴리스 게시 전에 immutable virtual-thread 좌표를 검증한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


GROUP = "io.github.bluetape4k"
REQUIRED_MODULES = (
    "bluetape4k-virtualthread-api",
    "bluetape4k-virtualthread-jdk25",
)
SNAPSHOT_SUFFIX = "-SNAPSHOT"


def read_gradle_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


def validate_provider_version(
    properties: dict[str, str],
) -> tuple[str | None, list[str]]:
    version = properties.get("bluetape4kVirtualThreadJdk25Version", "").strip()
    if not version:
        return None, [
            "릴리스에는 bluetape4kVirtualThreadJdk25Version이 필요하다",
        ]
    if version.endswith(SNAPSHOT_SUFFIX):
        return version, [
            "릴리스의 bluetape4kVirtualThreadJdk25Version은 -SNAPSHOT으로 끝날 수 없다: "
            f"{version}",
        ]
    return version, []


def validate_resolved_dependencies(
    resolution: str,
    expected_version: str,
) -> list[str]:
    errors: list[str] = []
    for module in REQUIRED_MODULES:
        coordinate_pattern = re.compile(
            rf"{re.escape(GROUP)}:{re.escape(module)}:"
            rf"(?P<requested>[^\s()]+)(?:\s+->\s+(?P<resolved>[^\s()]+))?"
        )
        versions = [
            match.group("resolved") or match.group("requested")
            for match in coordinate_pattern.finditer(resolution)
        ]
        if expected_version not in versions:
            errors.append(
                f"해석된 dependency에서 {GROUP}:{module}:{expected_version}을 찾을 수 없다"
            )
        snapshot_versions = sorted(
            version for version in versions if version.endswith(SNAPSHOT_SUFFIX)
        )
        if snapshot_versions:
            errors.append(
                f"해석된 dependency {GROUP}:{module}에 snapshot 좌표가 남아 있다: "
                + ", ".join(snapshot_versions)
            )
    return errors


def validate(
    properties_path: Path,
    resolution_path: Path,
) -> tuple[str | None, list[str]]:
    version, errors = validate_provider_version(read_gradle_properties(properties_path))
    if version is None:
        return version, errors
    errors.extend(
        validate_resolved_dependencies(
            resolution_path.read_text(encoding="utf-8"),
            version,
        )
    )
    return version, errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", type=Path, required=True)
    parser.add_argument("--resolution", type=Path, required=True)
    args = parser.parse_args()

    version, errors = validate(args.properties, args.resolution)
    if errors:
        for error in errors:
            print(f"::error::{error}")
        return 1

    print(
        "immutable JDK25 virtual-thread API/provider 해석 확인: "
        f"{GROUP}:{REQUIRED_MODULES[0]}:{version}, "
        f"{GROUP}:{REQUIRED_MODULES[1]}:{version}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
