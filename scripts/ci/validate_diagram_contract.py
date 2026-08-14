#!/usr/bin/env python3
"""Validate changed SVG diagram XML, connector geometry, and arrowheads."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


NUMBER = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"
POINT_RE = re.compile(rf"({NUMBER})\s*,?\s*({NUMBER})")
CSS_MARKER_RE = re.compile(r"\.([A-Za-z0-9_-]+)\s*\{[^}]*marker-end:\s*url\(#([^\)]+)\)", re.DOTALL)


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def numbers(value: str) -> list[float]:
    return [float(token) for token in re.findall(NUMBER, value)]


def marker_reference(value: str) -> str | None:
    match = re.search(r"url\(#([^\)]+)\)", value)
    return match.group(1) if match else None


def css_marker_references(root: ET.Element) -> dict[str, str]:
    styles = [element.text or "" for element in root.iter() if local_name(element.tag) == "style"]
    return {class_name: marker_id for style in styles for class_name, marker_id in CSS_MARKER_RE.findall(style)}


def validate_svg(path: Path) -> list[str]:
    relative = path.as_posix()
    errors: list[str] = []
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        return [f"{relative}: invalid SVG XML: {exc}"]

    if local_name(root.tag) != "svg":
        errors.append(f"{relative}: root element is not svg")
    view_box = numbers(root.attrib.get("viewBox", ""))
    if len(view_box) != 4 or view_box[2] <= 0 or view_box[3] <= 0:
        errors.append(f"{relative}: viewBox must contain positive width and height")
    for element in root.iter():
        if local_name(element.tag) in {"title", "desc"} and not (element.text or "").strip():
            errors.append(f"{relative}: {local_name(element.tag)} must not be empty")
    if not any(local_name(element.tag) == "title" for element in root.iter()):
        errors.append(f"{relative}: accessible title missing")
    if not any(local_name(element.tag) == "desc" for element in root.iter()):
        errors.append(f"{relative}: accessible description missing")

    markers = {
        element.attrib.get("id"): element
        for element in root.iter()
        if local_name(element.tag) == "marker" and element.attrib.get("id")
    }
    class_markers = css_marker_references(root)

    def effective_marker(element: ET.Element) -> str | None:
        explicit = marker_reference(element.attrib.get("marker-end", ""))
        if explicit:
            return explicit
        for class_name in element.attrib.get("class", "").split():
            if class_name in class_markers:
                return class_markers[class_name]
        return None

    directed = [
        element
        for element in root.iter()
        if local_name(element.tag) in {"path", "line", "polyline", "polygon"}
        and effective_marker(element) is not None
    ]
    for marker_id, marker in markers.items():
        try:
            width = float(marker.attrib.get("markerWidth", "0"))
            height = float(marker.attrib.get("markerHeight", "0"))
        except ValueError:
            width = height = 0
        if width <= 0 or height <= 0:
            errors.append(f"{relative}: marker {marker_id} has non-positive dimensions")
        if marker.attrib.get("markerUnits") != "userSpaceOnUse":
            errors.append(f"{relative}: marker {marker_id} must use userSpaceOnUse units")
        marker_paths = [element for element in marker.iter() if local_name(element.tag) == "path"]
        if not marker_paths or not any("Z" in element.attrib.get("d", "").upper() for element in marker_paths):
            errors.append(f"{relative}: marker {marker_id} has no closed arrowhead path")

    for element in directed:
        marker_id = effective_marker(element)
        if marker_id not in markers:
            errors.append(f"{relative}: directed element references unknown marker {marker_id!r}")
        if local_name(element.tag) == "path":
            points = [(float(match.group(1)), float(match.group(2))) for match in POINT_RE.finditer(element.attrib.get("d", ""))]
            if len(points) < 2 or len(set(points)) < 2:
                errors.append(f"{relative}: directed path {element.attrib.get('id', '<anonymous>')} has degenerate geometry")
            if not re.search(r"[LHVCSQTAZ]", element.attrib.get("d", "").upper()):
                errors.append(f"{relative}: directed path {element.attrib.get('id', '<anonymous>')} has no drawable segment")

    return errors


def changed_svg_paths(root: Path, base_ref: str | None, head_ref: str | None) -> list[Path]:
    if not base_ref or not head_ref:
        return []
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{base_ref}...{head_ref}", "--", "*.svg"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git diff failed")
    return [root / line for line in result.stdout.splitlines() if line.endswith(".svg") and (root / line).is_file()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--base-ref")
    parser.add_argument("--head-ref")
    parser.add_argument("paths", nargs="*")
    args = parser.parse_args()
    root = args.root.resolve()
    paths = [root / path for path in args.paths] if args.paths else changed_svg_paths(root, args.base_ref, args.head_ref)
    if not paths:
        print("Diagram XML/geometry/arrowhead contract: N/A (no changed SVG files)")
        return 0
    errors = [error for path in paths for error in validate_svg(path)]
    if errors:
        print("Diagram XML/geometry/arrowhead contract FAILED:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Diagram XML/geometry/arrowhead contract OK: {len(paths)} changed SVG file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
