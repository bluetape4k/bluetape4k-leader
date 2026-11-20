#!/usr/bin/env python3
"""Validate that selected Detekt suppression IDs stay canonical and single-line."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATHS = (
    Path("leader-dynamodb/detekt-baseline.xml"),
    Path("leader-etcd/detekt-baseline.xml"),
)


def validate_text(path: str, text: str) -> list[str]:
    try:
        document = ET.fromstring(text)
    except ET.ParseError as error:
        return [f"{path}: invalid XML: {error}"]

    errors: list[str] = []
    for index, node in enumerate(document.findall(".//ID"), start=1):
        value = node.text or ""
        if value != " ".join(value.split()):
            errors.append(f"{path}: ID[{index}] contains non-canonical whitespace")
    return errors


def validate_sources(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    for relative in BASELINE_PATHS:
        path = root / relative
        if not path.is_file():
            errors.append(f"missing Detekt baseline: {relative}")
            continue
        errors.extend(validate_text(str(path.relative_to(root)), path.read_text(encoding="utf-8")))
    return errors


def main() -> int:
    errors = validate_sources()
    if errors:
        print("Detekt baseline contract FAILED:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("Detekt baseline contract OK: DynamoDB and etcd suppression IDs are canonical")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
