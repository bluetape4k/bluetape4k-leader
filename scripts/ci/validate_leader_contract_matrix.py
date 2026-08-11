#!/usr/bin/env python3
"""Validate the backend leader contract capability matrix."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
MATRIX_PATH = ROOT / "scripts/ci/leader-contract-capabilities.json"
WORKFLOW_PATH = ROOT / ".github/workflows/ci.yml"
BACKENDS = ("etcd", "consul", "dynamodb", "k8s")
REQUIRED_BASES = {
    "leader-id-sync-single": "AbstractLeaderElectorLeaderIdContractTest",
    "leader-id-sync-group": "AbstractLeaderGroupElectorLeaderIdContractTest",
    "leader-id-async-single": "AbstractAsyncLeaderElectorLeaderIdContractTest",
    "leader-id-async-group": "AbstractAsyncLeaderGroupElectorLeaderIdContractTest",
    "leader-id-suspend-single": "AbstractSuspendLeaderElectorLeaderIdContractTest",
    "leader-id-suspend-group": "AbstractSuspendLeaderGroupElectorLeaderIdContractTest",
    "lock-extender-sync-single": "AbstractSyncLockExtenderContractTest",
    "lock-extender-sync-group": "AbstractGroupLockExtenderContractTest",
    "lock-extender-suspend-single": "AbstractSuspendLockExtenderContractTest",
    "lock-extender-suspend-group": "AbstractSuspendGroupLockExtenderContractTest",
}
REQUIRED_DIRECT = {
    "etcd": {"virtual-thread-single", "virtual-thread-group-overload"},
    "consul": {"executor-overload-single", "executor-overload-group"},
    "dynamodb": {"virtual-thread-single", "virtual-thread-group"},
    "k8s": {"executor-overload-single", "executor-overload-group"},
}


def _path_error(path: str) -> bool:
    candidate = Path(path)
    return candidate.is_absolute() or ".." in candidate.parts


def validate_entries(matrix: dict[str, Any], root: Path) -> list[str]:
    """Validate entry shape and referenced test files without CI assumptions."""

    errors: list[str] = []
    entries = matrix.get("entries")
    if not isinstance(entries, list):
        return ["matrix.entries must be an array"]

    seen: set[tuple[str, str]] = set()
    for index, entry in enumerate(entries):
        prefix = f"entries[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{prefix} must be an object")
            continue

        required = ("backend", "module", "contract", "status")
        missing = [field for field in required if not entry.get(field)]
        if missing:
            errors.append(f"{prefix} missing required fields: {', '.join(missing)}")
            continue

        key = (str(entry["backend"]), str(entry["contract"]))
        if key in seen:
            errors.append(f"duplicate capability entry: {key[0]}/{key[1]}")
        seen.add(key)

        status = entry["status"]
        if status not in {"supported", "na"}:
            errors.append(f"{prefix} status must be supported or na")
            continue

        if status == "na":
            reason = entry.get("reason")
            if not isinstance(reason, str) or not reason.strip():
                errors.append(f"{prefix} N/A reason is required")
            continue

        test = entry.get("test")
        if not isinstance(test, str) or not test.strip():
            errors.append(f"{prefix} supported entry requires test file")
            continue
        if _path_error(test):
            errors.append(f"{prefix} test path must stay inside repository")
            continue

        test_path = root / test
        if not test_path.is_file():
            errors.append(f"{prefix} test file does not exist: {test}")
            continue

        try:
            source = test_path.read_text(encoding="utf-8")
        except OSError as exc:
            errors.append(f"{prefix} test file cannot be read: {exc}")
            continue

        base = entry.get("base")
        if base is not None and (not isinstance(base, str) or base not in source):
            errors.append(f"{prefix} abstract base is missing: {base}")
        if entry.get("direct") is not True and base is None:
            errors.append(f"{prefix} supported entry requires base or direct=true")

    return errors


def validate_required_entries(matrix: dict[str, Any]) -> list[str]:
    """Ensure every backend declares the common contract and direct paths."""

    errors: list[str] = []
    entries = matrix.get("entries", [])
    by_key = {
        (entry.get("backend"), entry.get("contract")): entry
        for entry in entries
        if isinstance(entry, dict)
    }

    for backend in BACKENDS:
        for contract, base in REQUIRED_BASES.items():
            entry = by_key.get((backend, contract))
            if entry is None:
                errors.append(f"{backend} is missing required contract: {contract}")
            elif entry.get("status") != "supported":
                errors.append(f"{backend}/{contract} must be supported with {base}")
        for contract in REQUIRED_DIRECT[backend]:
            entry = by_key.get((backend, contract))
            if entry is None:
                errors.append(f"{backend} is missing direct contract: {contract}")
            elif entry.get("status") != "supported" or entry.get("direct") is not True:
                errors.append(f"{backend}/{contract} must be supported direct=true")

    return errors


def validate_workflow(workflow: str) -> list[str]:
    """Confirm existing CI fan-out jobs execute all contract-bearing modules."""

    errors: list[str] = []
    required_fragments = {
        "etcd test job": ":bluetape4k-leader-etcd:test",
        "consul test job": ":bluetape4k-leader-consul:test",
        "dynamodb test job": ":bluetape4k-leader-dynamodb:test",
        "k8s unit test job": ":bluetape4k-leader-k8s:test",
        "k8s runtime test job": ":bluetape4k-leader-k8s:k8sTest",
    }
    for label, fragment in required_fragments.items():
        if fragment not in workflow:
            errors.append(f"CI workflow is missing {label}: {fragment}")
    if "validate_leader_contract_matrix.py --static" not in workflow:
        errors.append("ci-contract does not invoke leader contract matrix static validator")
    if "validate_leader_contract_matrix.py --self-test" not in workflow:
        errors.append("ci-contract does not invoke leader contract matrix self-test")
    return errors


def load_matrix(path: Path = MATRIX_PATH) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read matrix {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError("matrix root must be an object")
    return value


def run_static() -> int:
    try:
        matrix = load_matrix()
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    except ValueError as exc:
        print(f"Leader contract matrix FAILED: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"Leader contract matrix FAILED: {exc}", file=sys.stderr)
        return 1

    errors = validate_entries(matrix, ROOT)
    errors.extend(validate_required_entries(matrix))
    errors.extend(validate_workflow(workflow))
    if errors:
        print("Leader contract matrix FAILED:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Leader contract matrix OK: {MATRIX_PATH}")
    return 0


def run_self_test() -> int:
    with tempfile.TemporaryDirectory(prefix="leader-contract-matrix-") as directory:
        root = Path(directory)
        valid_file = root / "leader-etcd/src/test/Contract.kt"
        valid_file.parent.mkdir(parents=True)
        valid_file.write_text(
            "class Contract : AbstractLeaderElectorLeaderIdContractTest()",
            encoding="utf-8",
        )
        valid = {
            "entries": [
                {
                    "backend": "etcd",
                    "module": "bluetape4k-leader-etcd",
                    "contract": "leader-id-sync-single",
                    "status": "supported",
                    "test": "leader-etcd/src/test/Contract.kt",
                    "base": "AbstractLeaderElectorLeaderIdContractTest",
                },
                {
                    "backend": "etcd",
                    "module": "bluetape4k-leader-etcd",
                    "contract": "lock-extender-async",
                    "status": "na",
                    "reason": "core fixture is not defined",
                },
            ]
        }
        if validate_entries(valid, root):
            print("self-test rejected valid entries", file=sys.stderr)
            return 1

        broken_file = dict(valid["entries"][0])
        broken_file["base"] = "MissingBase"
        if not validate_entries({"entries": [broken_file]}, root):
            print("self-test did not catch a missing base", file=sys.stderr)
            return 1

        broken_na = dict(valid["entries"][1])
        broken_na["reason"] = "  "
        if not validate_entries({"entries": [broken_na]}, root):
            print("self-test did not catch an empty N/A reason", file=sys.stderr)
            return 1

    print("Leader contract matrix self-test OK")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--static", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    return run_static() if args.static else run_self_test()


if __name__ == "__main__":
    raise SystemExit(main())
