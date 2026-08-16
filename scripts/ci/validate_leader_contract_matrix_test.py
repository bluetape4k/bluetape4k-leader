#!/usr/bin/env python3
"""Unit tests for the leader contract capability matrix validator."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from validate_leader_contract_matrix import (
    render_readme_capability_rows,
    validate_entries,
    validate_modules,
    validate_readme_capabilities,
    validate_required_entries,
)


class ValidateLeaderContractMatrixTest(unittest.TestCase):
    def readme_capability(self, source: str) -> dict[str, object]:
        return {
            "backend": "Local",
            "module": "bluetape4k-leader-core",
            "single_blocking": "N",
            "single_async": "B",
            "single_suspend": "N",
            "single_virtual": "B",
            "group_blocking": "N",
            "group_async": "B",
            "group_suspend": "N",
            "group_virtual": "B",
            "auto_extend": "S",
            "state": "S/G",
            "audit": "S",
            "sources": [
                {
                    "path": source,
                    "tokens": ["class LocalLeaderElector", "options.autoExtend"],
                }
            ],
        }

    def supported_entry(self, **overrides: str) -> dict[str, str]:
        entry = {
            "backend": "etcd",
            "module": "bluetape4k-leader-etcd",
            "contract": "leader-id-sync-single",
            "status": "supported",
            "test": "leader-etcd/src/test/Contract.kt",
            "base": "AbstractLeaderElectorLeaderIdContractTest",
        }
        entry.update(overrides)
        return entry

    def test_accepts_supported_file_with_base_and_explicit_na_reason(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            contract = root / "leader-etcd/src/test/Contract.kt"
            contract.parent.mkdir(parents=True)
            contract.write_text(
                "class Contract : AbstractLeaderElectorLeaderIdContractTest()",
                encoding="utf-8",
            )

            errors = validate_entries(
                {
                    "entries": [
                        self.supported_entry(),
                        {
                            "backend": "etcd",
                            "module": "bluetape4k-leader-etcd",
                            "contract": "lock-extender-async",
                            "status": "na",
                            "reason": "core fixture is not defined",
                        },
                    ]
                },
                root,
            )

            self.assertEqual(errors, [])

    def test_rejects_supported_entry_without_file_or_base(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = validate_entries(
                {"entries": [self.supported_entry(base="MissingBase")]},
                Path(directory),
            )

            self.assertIn("test file does not exist", " ".join(errors))

    def test_rejects_na_entry_without_reason(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = validate_entries(
                {
                    "entries": [
                        {
                            "backend": "etcd",
                            "module": "bluetape4k-leader-etcd",
                            "contract": "lock-extender-async",
                            "status": "na",
                            "reason": "   ",
                        }
                    ]
                },
                Path(directory),
            )

            self.assertIn("N/A reason is required", " ".join(errors))

    def test_rejects_na_entry_with_test_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = validate_entries(
                {
                    "entries": [
                        {
                            "backend": "etcd",
                            "module": "bluetape4k-leader-etcd",
                            "contract": "lock-extender-async",
                            "status": "na",
                            "test": "leader-etcd/src/test/Contract.kt",
                            "reason": "core fixture is not defined",
                        }
                    ]
                },
                Path(directory),
            )

            self.assertIn("N/A entry must not reference a test file", " ".join(errors))

    def test_rejects_module_missing_from_gradle_settings(self) -> None:
        errors = validate_modules(
            {"entries": [self.supported_entry()]},
            'include("bluetape4k-leader-consul")',
        )

        self.assertEqual(
            errors,
            ["matrix module is not declared in settings.gradle.kts: bluetape4k-leader-etcd"],
        )

    def test_rejects_required_contract_with_wrong_abstract_base(self) -> None:
        errors = validate_required_entries(
            {
                "entries": [
                    {
                        "backend": "etcd",
                        "contract": "leader-id-sync-group",
                        "status": "supported",
                        "base": "AbstractLeaderElectorLeaderIdContractTest",
                    }
                ]
            },
        )

        self.assertIn(
            "etcd/leader-id-sync-group must declare base "
            "AbstractLeaderGroupElectorLeaderIdContractTest",
            errors,
        )

    def test_rejects_readme_capability_when_source_anchor_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = "leader-core/src/main/kotlin/LocalLeaderElector.kt"
            source_file = root / source
            source_file.parent.mkdir(parents=True)
            source_file.write_text("class LocalLeaderElector", encoding="utf-8")
            row = self.readme_capability(source)
            rendered = render_readme_capability_rows([row])
            readme = f"<!-- LEADER_CAPABILITY_MATRIX:START -->\n{rendered}\n<!-- LEADER_CAPABILITY_MATRIX:END -->"

            errors = validate_readme_capabilities(
                {"readme_capabilities": {"rows": [row]}},
                root,
                readme,
                readme,
                expected_backends={"Local"},
            )

            self.assertIn("source token is missing: options.autoExtend", errors)

    def test_rejects_localized_readme_capability_matrix_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = "leader-core/src/main/kotlin/LocalLeaderElector.kt"
            source_file = root / source
            source_file.parent.mkdir(parents=True)
            source_file.write_text(
                "class LocalLeaderElector { val configured = options.autoExtend }",
                encoding="utf-8",
            )
            row = self.readme_capability(source)
            rendered = render_readme_capability_rows([row])
            readme = f"<!-- LEADER_CAPABILITY_MATRIX:START -->\n{rendered}\n<!-- LEADER_CAPABILITY_MATRIX:END -->"
            korean = readme.replace("| S/G | S |", "| G | — |")

            errors = validate_readme_capabilities(
                {"readme_capabilities": {"rows": [row]}},
                root,
                readme,
                korean,
                expected_backends={"Local"},
            )

            self.assertIn("README.ko.md capability rows differ from manifest", errors)

    def test_rejects_non_object_readme_capability_without_crashing(self) -> None:
        markers = (
            "<!-- LEADER_CAPABILITY_MATRIX:START -->\n"
            "<!-- LEADER_CAPABILITY_MATRIX:END -->"
        )

        errors = validate_readme_capabilities(
            {"readme_capabilities": {"rows": ["invalid"]}},
            Path("."),
            markers,
            markers,
            expected_backends=set(),
        )

        self.assertIn("readme_capabilities.rows[0] fields do not match capability contract", errors)


if __name__ == "__main__":
    unittest.main()
