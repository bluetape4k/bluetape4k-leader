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
SETTINGS_PATH = ROOT / "settings.gradle.kts"
README_PATH = ROOT / "README.md"
README_KO_PATH = ROOT / "README.ko.md"
BACKENDS = ("etcd", "consul", "dynamodb", "k8s")
README_CAPABILITY_START = "<!-- LEADER_CAPABILITY_MATRIX:START -->"
README_CAPABILITY_END = "<!-- LEADER_CAPABILITY_MATRIX:END -->"
README_CAPABILITY_FIELDS = {
    "backend",
    "module",
    "single_blocking",
    "single_async",
    "single_suspend",
    "single_virtual",
    "group_blocking",
    "group_async",
    "group_suspend",
    "group_virtual",
    "auto_extend",
    "state",
    "audit",
    "sources",
}
EXPECTED_README_BACKENDS = {
    "Local",
    "Lettuce",
    "Redisson",
    "Exposed JDBC",
    "Exposed R2DBC",
    "MongoDB",
    "Hazelcast",
    "etcd",
    "Consul",
    "DynamoDB",
    "Kubernetes",
    "ZooKeeper",
}
EXPECTED_MODULES = {
    "etcd": "bluetape4k-leader-etcd",
    "consul": "bluetape4k-leader-consul",
    "dynamodb": "bluetape4k-leader-dynamodb",
    "k8s": "bluetape4k-leader-k8s",
}
COMMON_REQUIRED_BASES = {
    "leader-id-sync-single": "AbstractLeaderElectorLeaderIdContractTest",
    "leader-id-sync-group": "AbstractLeaderGroupElectorLeaderIdContractTest",
    "leader-id-suspend-single": "AbstractSuspendLeaderElectorLeaderIdContractTest",
    "leader-id-suspend-group": "AbstractSuspendLeaderGroupElectorLeaderIdContractTest",
    "lock-extender-sync-single": "AbstractSyncLockExtenderContractTest",
    "lock-extender-sync-group": "AbstractGroupLockExtenderContractTest",
    "lock-extender-suspend-single": "AbstractSuspendLockExtenderContractTest",
    "lock-extender-suspend-group": "AbstractSuspendGroupLockExtenderContractTest",
}
ASYNC_REQUIRED_BASES = {
    "leader-id-async-single": "AbstractAsyncLeaderElectorLeaderIdContractTest",
    "leader-id-async-group": "AbstractAsyncLeaderGroupElectorLeaderIdContractTest",
}
REQUIRED_BASES_BY_BACKEND = {
    "etcd": {
        contract: base
        for contract, base in COMMON_REQUIRED_BASES.items()
        if contract not in {"leader-id-sync-single", "leader-id-suspend-single"}
    },
    "consul": {**COMMON_REQUIRED_BASES, **ASYNC_REQUIRED_BASES},
    "dynamodb": {**COMMON_REQUIRED_BASES, **ASYNC_REQUIRED_BASES},
    "k8s": {**COMMON_REQUIRED_BASES, **ASYNC_REQUIRED_BASES},
}
LOCK_EXTENDER_NA = {"lock-extender-async", "lock-extender-virtual"}
REQUIRED_NA_BY_BACKEND = {
    "etcd": {
        *LOCK_EXTENDER_NA,
        "leader-id-sync-single",
        "leader-id-async-single",
        "leader-id-async-group",
        "leader-id-suspend-single",
        "virtual-thread-slot",
    },
    "consul": LOCK_EXTENDER_NA,
    "dynamodb": LOCK_EXTENDER_NA,
    "k8s": LOCK_EXTENDER_NA,
}
REQUIRED_DIRECT = {
    "etcd": {"virtual-thread-wrapper", "executor-overload-group"},
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

        backend = str(entry["backend"])
        module = str(entry["module"])
        if backend not in EXPECTED_MODULES:
            errors.append(f"{prefix} unknown backend: {backend}")
        elif module != EXPECTED_MODULES[backend]:
            errors.append(
                f"{prefix} module does not match backend {backend}: expected {EXPECTED_MODULES[backend]}"
            )

        key = (backend, str(entry["contract"]))
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
            if "test" in entry:
                errors.append(f"{prefix} N/A entry must not reference a test file")
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
    """Ensure every backend declares supported, N/A, and direct paths."""

    errors: list[str] = []
    entries = matrix.get("entries", [])
    by_key = {
        (entry.get("backend"), entry.get("contract")): entry
        for entry in entries
        if isinstance(entry, dict)
    }

    for backend in BACKENDS:
        for contract, base in REQUIRED_BASES_BY_BACKEND[backend].items():
            entry = by_key.get((backend, contract))
            if entry is None:
                errors.append(f"{backend} is missing required contract: {contract}")
            elif entry.get("status") != "supported":
                errors.append(f"{backend}/{contract} must be supported with {base}")
            elif entry.get("base") != base:
                errors.append(f"{backend}/{contract} must declare base {base}")
        for contract in REQUIRED_DIRECT[backend]:
            entry = by_key.get((backend, contract))
            if entry is None:
                errors.append(f"{backend} is missing direct contract: {contract}")
            elif entry.get("status") != "supported" or entry.get("direct") is not True:
                errors.append(f"{backend}/{contract} must be supported direct=true")
        for contract in REQUIRED_NA_BY_BACKEND[backend]:
            entry = by_key.get((backend, contract))
            if entry is None:
                errors.append(f"{backend} is missing explicit N/A contract: {contract}")
            elif entry.get("status") != "na" or not str(entry.get("reason", "")).strip():
                errors.append(f"{backend}/{contract} must be N/A with a reason")

    return errors


def render_readme_capability_rows(rows: list[dict[str, Any]]) -> str:
    """Render the locale-neutral capability rows embedded in both READMEs."""

    columns = (
        "backend",
        "module",
        "single_blocking",
        "single_async",
        "single_suspend",
        "single_virtual",
        "group_blocking",
        "group_async",
        "group_suspend",
        "group_virtual",
        "auto_extend",
        "state",
        "audit",
    )
    rendered = []
    for row in rows:
        cells = [str(row.get(column, "")) for column in columns]
        cells[1] = f"`{cells[1]}`"
        rendered.append("| " + " | ".join(cells) + " |")
    return "\n".join(rendered)


def _readme_capability_block(readme: str, label: str) -> tuple[str | None, list[str]]:
    errors: list[str] = []
    if readme.count(README_CAPABILITY_START) != 1 or readme.count(README_CAPABILITY_END) != 1:
        return None, [f"{label} must contain one capability matrix marker pair"]
    _, separator, remainder = readme.partition(README_CAPABILITY_START)
    if not separator:
        return None, [f"{label} capability matrix start marker is missing"]
    block, separator, _ = remainder.partition(README_CAPABILITY_END)
    if not separator:
        errors.append(f"{label} capability matrix end marker is missing")
        return None, errors
    return block.strip(), errors


def validate_readme_capabilities(
    matrix: dict[str, Any],
    root: Path,
    readme: str,
    readme_ko: str,
    expected_backends: set[str] = EXPECTED_README_BACKENDS,
) -> list[str]:
    """Validate capability rows, Kotlin source anchors, and EN/KO README parity."""

    errors: list[str] = []
    capability = matrix.get("readme_capabilities")
    if not isinstance(capability, dict):
        return ["matrix.readme_capabilities must be an object"]
    rows = capability.get("rows")
    if not isinstance(rows, list):
        return ["matrix.readme_capabilities.rows must be an array"]

    seen: set[str] = set()
    execution_codes = {"N", "B", "—"}
    auto_extend_codes = {"S", "—"}
    state_codes = {"S", "G", "S/G", "—"}
    audit_codes = {"S", "—"}

    for index, row in enumerate(rows):
        prefix = f"readme_capabilities.rows[{index}]"
        if not isinstance(row, dict) or set(row) != README_CAPABILITY_FIELDS:
            errors.append(f"{prefix} fields do not match capability contract")
            continue
        backend = row["backend"]
        module = row["module"]
        if not isinstance(backend, str) or not backend:
            errors.append(f"{prefix} backend must be a non-empty string")
            continue
        if backend in seen:
            errors.append(f"duplicate README capability backend: {backend}")
        seen.add(backend)
        if not isinstance(module, str) or not module.startswith("bluetape4k-leader-"):
            errors.append(f"{prefix} module must be a publishable leader module")

        for field in (
            "single_blocking",
            "single_async",
            "single_suspend",
            "single_virtual",
            "group_blocking",
            "group_async",
            "group_suspend",
            "group_virtual",
        ):
            if row[field] not in execution_codes:
                errors.append(f"{prefix}.{field} has invalid execution code: {row[field]}")
        if row["auto_extend"] not in auto_extend_codes:
            errors.append(f"{prefix}.auto_extend has invalid code: {row['auto_extend']}")
        if row["state"] not in state_codes:
            errors.append(f"{prefix}.state has invalid code: {row['state']}")
        if row["audit"] not in audit_codes:
            errors.append(f"{prefix}.audit has invalid code: {row['audit']}")

        sources = row["sources"]
        if not isinstance(sources, list) or not sources:
            errors.append(f"{prefix} requires source anchors")
            continue
        for source_index, source in enumerate(sources):
            source_prefix = f"{prefix}.sources[{source_index}]"
            if not isinstance(source, dict) or set(source) != {"path", "tokens"}:
                errors.append(f"{source_prefix} fields do not match source anchor contract")
                continue
            path = source["path"]
            tokens = source["tokens"]
            if not isinstance(path, str) or not path or _path_error(path):
                errors.append(f"{source_prefix} path must stay inside repository")
                continue
            root_path = root.resolve()
            source_path = (root / path).resolve()
            try:
                source_path.relative_to(root_path)
            except ValueError:
                errors.append(f"{source_prefix} path escapes repository: {path}")
                continue
            if not source_path.is_file():
                errors.append(f"{source_prefix} file does not exist: {path}")
                continue
            if (
                not isinstance(tokens, list)
                or not tokens
                or not all(isinstance(token, str) and token for token in tokens)
            ):
                errors.append(f"{source_prefix} tokens must be non-empty strings")
                continue
            try:
                source_text = source_path.read_text(encoding="utf-8")
            except (OSError, UnicodeError) as exc:
                errors.append(f"{source_prefix} file cannot be read: {exc}")
                continue
            for token in tokens:
                if token not in source_text:
                    errors.append(f"source token is missing: {token}")

    if seen != expected_backends:
        missing = sorted(expected_backends - seen)
        extra = sorted(seen - expected_backends)
        if missing:
            errors.append("README capability backends are missing: " + ", ".join(missing))
        if extra:
            errors.append("README capability backends are unexpected: " + ", ".join(extra))

    renderable_rows = [row for row in rows if isinstance(row, dict)]
    expected_rows = render_readme_capability_rows(renderable_rows)
    for label, contents in (("README.md", readme), ("README.ko.md", readme_ko)):
        actual_rows, marker_errors = _readme_capability_block(contents, label)
        errors.extend(marker_errors)
        if actual_rows is not None and actual_rows != expected_rows:
            errors.append(f"{label} capability rows differ from manifest")
    return errors


def validate_modules(matrix: dict[str, Any], settings: str) -> list[str]:
    """Confirm every matrix module is declared by the Gradle settings file."""

    errors: list[str] = []
    modules = {
        entry.get("module")
        for entry in matrix.get("entries", [])
        if isinstance(entry, dict) and isinstance(entry.get("module"), str)
    }
    capability = matrix.get("readme_capabilities", {})
    if isinstance(capability, dict):
        rows = capability.get("rows", [])
        if isinstance(rows, list):
            modules.update(
                row.get("module")
                for row in rows
                if isinstance(row, dict) and isinstance(row.get("module"), str)
            )
    for module in sorted(modules):
        if f'"{module}"' not in settings and f'":{module}"' not in settings:
            errors.append(f"matrix module is not declared in settings.gradle.kts: {module}")
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
        settings = SETTINGS_PATH.read_text(encoding="utf-8")
        readme = README_PATH.read_text(encoding="utf-8")
        readme_ko = README_KO_PATH.read_text(encoding="utf-8")
    except ValueError as exc:
        print(f"Leader contract matrix FAILED: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"Leader contract matrix FAILED: {exc}", file=sys.stderr)
        return 1

    errors = validate_entries(matrix, ROOT)
    errors.extend(validate_required_entries(matrix))
    errors.extend(validate_readme_capabilities(matrix, ROOT, readme, readme_ko))
    errors.extend(validate_modules(matrix, settings))
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

        capability_source = root / "leader-core/src/main/kotlin/Local.kt"
        capability_source.parent.mkdir(parents=True)
        capability_source.write_text("class LocalLeaderElector { val enabled = options.autoExtend }", encoding="utf-8")
        capability_row = {
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
            "sources": [{"path": "leader-core/src/main/kotlin/Local.kt", "tokens": ["options.autoExtend"]}],
        }
        capability_rows = render_readme_capability_rows([capability_row])
        capability_readme = (
            f"{README_CAPABILITY_START}\n{capability_rows}\n{README_CAPABILITY_END}"
        )
        capability_matrix = {"readme_capabilities": {"rows": [capability_row]}}
        if validate_readme_capabilities(
            capability_matrix,
            root,
            capability_readme,
            capability_readme,
            expected_backends={"Local"},
        ):
            print("self-test rejected valid README capability rows", file=sys.stderr)
            return 1
        if not validate_readme_capabilities(
            capability_matrix,
            root,
            capability_readme,
            capability_readme.replace("| S/G | S |", "| G | — |"),
            expected_backends={"Local"},
        ):
            print("self-test did not catch README capability drift", file=sys.stderr)
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
