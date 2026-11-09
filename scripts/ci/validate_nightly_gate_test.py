#!/usr/bin/env python3
"""Full Nightly required-job gate regression tests."""

from __future__ import annotations

import unittest

from validate_nightly_gate import validate_results


class NightlyGateTest(unittest.TestCase):
    def test_full_scope_rejects_skipped_required_job(self) -> None:
        errors = validate_results("success,skipped,success", full_scope=True, cluster_result="success")

        self.assertTrue(errors)
        self.assertIn("skipped", errors[0])

    def test_full_scope_accepts_all_successful_required_jobs(self) -> None:
        self.assertEqual(
            validate_results("success,success,success", full_scope=True, cluster_result="success"),
            [],
        )

    def test_smoke_scope_preserves_skipped_jobs(self) -> None:
        self.assertEqual(
            validate_results("success,skipped,success", full_scope=False, cluster_result="skipped"),
            [],
        )

    def test_full_scope_rejects_any_non_success_status(self) -> None:
        errors = validate_results("success,neutral,success", full_scope=True, cluster_result="success")

        self.assertTrue(errors)
        self.assertIn("neutral", errors[0])

    def test_full_scope_rejects_empty_result_token(self) -> None:
        errors = validate_results("success,,success", full_scope=True, cluster_result="success")

        self.assertEqual(errors, ["Nightly results contain an empty status"])

    def test_full_scope_rejects_a_non_success_cluster_result(self) -> None:
        errors = validate_results("success,success,success", full_scope=True, cluster_result="skipped")

        self.assertTrue(errors)
        self.assertIn("Redis Cluster", errors[0])


if __name__ == "__main__":
    unittest.main()
