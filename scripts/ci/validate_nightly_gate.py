#!/usr/bin/env python3
"""Full Nightly required-job 결과를 fail-closed 방식으로 검증한다."""

from __future__ import annotations

import argparse
import sys


def validate_results(results: str, *, full_scope: bool, cluster_result: str) -> list[str]:
    """쉼표로 구분한 ``needs.*.result`` 값의 gate 위반을 반환한다."""

    raw_statuses = results.split(",")
    if not results.strip():
        return ["Nightly results are empty"]
    if any(not status.strip() for status in raw_statuses):
        return ["Nightly results contain an empty status"]
    statuses = [status.strip() for status in raw_statuses]

    if full_scope:
        non_success = [status for status in statuses if status != "success"]
        errors: list[str] = []
        if non_success:
            errors.append(
                "Full Nightly requires every required job to be success; "
                f"non-success results: {', '.join(non_success)}"
            )
        if cluster_result != "success":
            errors.append(
                "Redis Cluster Full Nightly gate is "
                f"{cluster_result}, expected success"
            )
        return errors

    if any(status in {"failure", "cancelled"} for status in statuses):
        return ["Nightly smoke scope contains a failure or cancellation"]
    return []


def _parse_bool(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True)
    parser.add_argument("--full-scope", required=True, type=_parse_bool)
    parser.add_argument("--cluster-result", required=True)
    args = parser.parse_args(argv)

    errors = validate_results(
        args.results,
        full_scope=args.full_scope,
        cluster_result=args.cluster_result,
    )
    if errors:
        for error in errors:
            print(f"Nightly gate FAILED: {error}", file=sys.stderr)
        return 1

    print("Nightly gate OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
