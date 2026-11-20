#!/usr/bin/env python3
"""Tests for the Detekt baseline validator."""

import unittest

from validate_detekt_baseline import validate_text


class DetektBaselineTest(unittest.TestCase):
    def test_rejects_wrapped_suppression_id(self) -> None:
        text = """\
<SmellBaseline>
  <CurrentIssues>
    <ID>LongMethod:Example.kt:fun\n      broken</ID>
  </CurrentIssues>
</SmellBaseline>
"""
        self.assertEqual(
            ["sample.xml: ID[1] contains non-canonical whitespace"],
            validate_text("sample.xml", text),
        )

    def test_accepts_canonical_suppression_id(self) -> None:
        text = """\
<SmellBaseline>
  <CurrentIssues>
    <ID>LongMethod:Example.kt:fun broken</ID>
  </CurrentIssues>
</SmellBaseline>
"""
        self.assertEqual([], validate_text("sample.xml", text))


if __name__ == "__main__":
    unittest.main()
