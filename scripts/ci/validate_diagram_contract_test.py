#!/usr/bin/env python3
"""Tests for changed SVG diagram contract validation."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))

from validate_diagram_contract import validate_svg


class ValidateDiagramContractTest(unittest.TestCase):
    def test_accepts_directed_svg_with_closed_arrowhead(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "diagram.svg"
            path.write_text(
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
                '<title>Title</title><desc>Description</desc><defs>'
                '<marker id="arrow" markerWidth="14" markerHeight="14" markerUnits="userSpaceOnUse">'
                '<path d="M 0 0 L 10 5 L 0 10 Z"/></marker></defs>'
                '<path id="route" d="M 10 10 L 90 90" marker-end="url(#arrow)"/></svg>',
                encoding="utf-8",
            )

            self.assertEqual(validate_svg(path), [])

    def test_accepts_comma_separated_path_coordinates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "diagram.svg"
            path.write_text(
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
                '<title>Title</title><desc>Description</desc><defs>'
                '<marker id="arrow" markerWidth="14" markerHeight="14" markerUnits="userSpaceOnUse">'
                '<path d="M0,0 L10,5 L0,10 Z"/></marker></defs>'
                '<path id="route" d="M10,10L90,90" marker-end="url(#arrow)"/></svg>',
                encoding="utf-8",
            )

            self.assertEqual(validate_svg(path), [])

    def test_rejects_degenerate_connector_and_open_arrowhead(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "diagram.svg"
            path.write_text(
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'
                '<title>Title</title><desc>Description</desc><defs>'
                '<marker id="arrow" markerWidth="14" markerHeight="14" markerUnits="userSpaceOnUse">'
                '<path d="M 0 0 L 0 0"/></marker></defs>'
                '<path id="route" d="M 10 10 L 10 10" marker-end="url(#arrow)"/></svg>',
                encoding="utf-8",
            )

            errors = validate_svg(path)

            self.assertTrue(any("degenerate geometry" in error for error in errors))
            self.assertTrue(any("closed arrowhead" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
