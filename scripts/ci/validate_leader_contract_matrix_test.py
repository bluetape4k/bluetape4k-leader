#!/usr/bin/env python3
"""Unit tests for the leader contract capability matrix validator."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from validate_leader_contract_matrix import validate_entries, validate_modules, validate_required_entries


class ValidateLeaderContractMatrixTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
