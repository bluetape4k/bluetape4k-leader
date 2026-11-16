#!/usr/bin/env python3
"""Tests for the leader example static contract validator."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from validate_example_static_contract import validate, validate_operator_manifests


ROOT = Path(__file__).resolve().parents[2]


class ValidateExampleStaticContractTest(unittest.TestCase):
    def test_checked_in_examples_satisfy_the_static_contract(self) -> None:
        self.assertEqual(validate(ROOT), [])

    def test_rejects_latest_operator_image(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            operator_root = root / "examples/k8s-operator/k8s"
            operator_root.mkdir(parents=True)
            (operator_root / "rbac.yaml").write_text(
                'resources: [ "leases" ]\nverbs: [ "get", "create", "update", "patch" ]\n',
                encoding="utf-8",
            )
            (operator_root / "deployment.yaml").write_text(
                "image: ghcr.io/bluetape4k/bluetape4k-k8s-operator:latest\n"
                "startupProbe:\nlivenessProbe:\nreadinessProbe:\npath: /actuator/health\n",
                encoding="utf-8",
            )

            errors = validate_operator_manifests(root)

            self.assertIn("deployment.yaml contains forbidden token: :latest", errors)


if __name__ == "__main__":
    unittest.main()
