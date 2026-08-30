#!/usr/bin/env python3
"""Validate the CI dependency fan-out and required-job runtime contract."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_WORKFLOW = Path(".github/workflows/ci.yml")
TEST_JOB_PREFIX = "test-"
MANUAL_CONTRACT_JOB_ID = "manual-contract"
MANUAL_CONTRACT_OUTPUT = "manual-contract"
GLOBAL_CONFIG_OUTPUT = "global-config"
REQUIRED_ROOT_PATHS = {
    "settings.gradle.kts",
    "build.gradle.kts",
    "buildSrc/**",
    "gradle/**",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "bluetape4k-leader-bom/**",
    "leader-core/**",
}
SPRING_BOOT_DEPENDENCIES = {
    "leader-redis-lettuce/**",
    "leader-redis-redisson/**",
    "leader-exposed-core/**",
    "leader-exposed-jdbc/**",
    "leader-exposed-r2dbc/**",
    "leader-mongodb/**",
    "leader-hazelcast/**",
    "leader-etcd/**",
    "leader-consul/**",
    "leader-dynamodb/**",
    "leader-micrometer/**",
}


@dataclass(frozen=True)
class JobSpec:
    job_id: str
    block: str
    condition: str
    output_keys: tuple[str, ...]


def workflow_path() -> Path:
    return Path(os.environ.get("CI_WORKFLOW_PATH", DEFAULT_WORKFLOW))


def job_blocks(workflow: str) -> dict[str, str]:
    lines = workflow.splitlines()
    jobs_start = next((i for i, line in enumerate(lines) if line == "jobs:"), None)
    if jobs_start is None:
        raise ValueError("workflow does not contain a jobs section")

    starts: list[tuple[str, int]] = []
    for index in range(jobs_start + 1, len(lines)):
        match = re.match(r"^  ([A-Za-z0-9_-]+):$", lines[index])
        if match:
            starts.append((match.group(1), index))

    blocks: dict[str, str] = {}
    for position, (job_id, start) in enumerate(starts):
        end = starts[position + 1][1] if position + 1 < len(starts) else len(lines)
        blocks[job_id] = "\n".join(lines[start:end])
    return blocks


def job_specs(workflow: str) -> dict[str, JobSpec]:
    specs: dict[str, JobSpec] = {}
    condition_pattern = re.compile(r"^    if:\s*(.*)$", re.MULTILINE)
    output_pattern = re.compile(
        r"needs\.changes\.outputs\[['\"]([^'\"]+)['\"]\]\s*==\s*['\"]true['\"]"
    )
    for job_id, block in job_blocks(workflow).items():
        condition_match = condition_pattern.search(block)
        condition = condition_match.group(1) if condition_match else ""
        output_keys = tuple(dict.fromkeys(output_pattern.findall(condition)))
        specs[job_id] = JobSpec(job_id, block, condition, output_keys)
    return specs


def section_paths(workflow: str, section: str) -> set[str]:
    lines = workflow.splitlines()
    start = next(
        (i for i, line in enumerate(lines) if re.match(rf"^\s+{re.escape(section)}:\s*$", line)),
        None,
    )
    if start is None:
        return set()
    indent = len(lines[start]) - len(lines[start].lstrip())
    paths: set[str] = set()
    for line in lines[start + 1 :]:
        stripped = line.strip()
        current_indent = len(line) - len(line.lstrip())
        if stripped and current_indent <= indent:
            break
        match = re.match(r"^\s+-\s+['\"]([^'\"]+)['\"]\s*$", line)
        if match:
            paths.add(match.group(1))
    return paths


def event_paths_ignore(workflow: str, event: str) -> set[str]:
    """Return paths ignored by a top-level push/pull_request event."""

    lines = workflow.splitlines()
    event_start = next(
        (i for i, line in enumerate(lines) if line == f"  {event}:"),
        None,
    )
    if event_start is None:
        return set()
    event_indent = len(lines[event_start]) - len(lines[event_start].lstrip())
    event_end = len(lines)
    for index in range(event_start + 1, len(lines)):
        line = lines[index]
        if line.strip() and len(line) - len(line.lstrip()) <= event_indent:
            event_end = index
            break
    block = "\n".join(lines[event_start:event_end])
    return section_paths(block, "paths-ignore")


def static_errors(workflow: str) -> list[str]:
    errors: list[str] = []
    specs = job_specs(workflow)
    changes = specs.get("changes")
    declared_outputs = set()
    if changes is not None:
        declared_outputs = set(
            re.findall(r"^      ([A-Za-z0-9_-]+):\s+\$\{\{\s*steps\.filter\.outputs\.", changes.block, re.MULTILINE)
        )
    output_line = re.search(
        r"^\s+dependency-graph:\s+\$\{\{\s*steps\.filter\.outputs\.dependency-graph\s*\}\}\s*$",
        workflow,
        re.MULTILINE,
    )
    if not output_line:
        errors.append("changes.outputs is missing dependency-graph")

    required_contract_outputs = {MANUAL_CONTRACT_OUTPUT, GLOBAL_CONFIG_OUTPUT}
    missing_contract_outputs = required_contract_outputs - declared_outputs
    if missing_contract_outputs:
        errors.append(
            "changes.outputs is missing: " + ", ".join(sorted(missing_contract_outputs))
        )

    for event in ("push", "pull_request"):
        ignored = event_paths_ignore(workflow, event)
        if ignored & {"**.md", "docs/**", "README*", "CHANGELOG.md", "WIP.md"}:
            errors.append(
                f"{event}.paths-ignore suppresses manual-contract changes: "
                + ", ".join(sorted(ignored & {"**.md", "docs/**", "README*", "CHANGELOG.md", "WIP.md"}))
            )

    manual_filter_paths = section_paths(workflow, MANUAL_CONTRACT_OUTPUT)
    required_manual_paths = {"**.md", "docs/**", "scripts/ci/**"}
    missing_manual_paths = required_manual_paths - manual_filter_paths
    if missing_manual_paths:
        errors.append(
            "manual-contract filter is missing: " + ", ".join(sorted(missing_manual_paths))
        )

    global_filter_paths = section_paths(workflow, GLOBAL_CONFIG_OUTPUT)
    required_global_paths = {
        ".github/workflows/**",
        "settings.gradle.kts",
        "build.gradle.kts",
        "buildSrc/**",
        "gradle/**",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "bluetape4k-leader-bom/**",
    }
    missing_global_paths = required_global_paths - global_filter_paths
    if missing_global_paths:
        errors.append(
            "global-config filter is missing: " + ", ".join(sorted(missing_global_paths))
        )

    dependency_paths = section_paths(workflow, "dependency-graph")
    missing_root = REQUIRED_ROOT_PATHS - dependency_paths
    if missing_root:
        errors.append("dependency-graph filter is missing: " + ", ".join(sorted(missing_root)))

    spring_paths = section_paths(workflow, "leader-spring-boot")
    missing_spring = SPRING_BOOT_DEPENDENCIES - spring_paths
    if missing_spring:
        errors.append("leader-spring-boot filter is missing: " + ", ".join(sorted(missing_spring)))

    test_jobs = [
        spec
        for spec in specs.values()
        if spec.job_id.startswith(TEST_JOB_PREFIX) or spec.job_id == "compile-benchmark"
    ]
    if not test_jobs:
        errors.append("no test jobs were found")
    for spec in test_jobs:
        if "needs:" not in spec.block or "changes" not in spec.block:
            errors.append(f"{spec.job_id} does not depend on changes")
        if "needs.changes.outputs" not in spec.condition:
            errors.append(f"{spec.job_id} has no changes output condition")
        if "needs.changes.outputs['dependency-graph']" not in spec.condition:
            errors.append(f"{spec.job_id} does not fan out on dependency-graph")
        undeclared = set(spec.output_keys) - declared_outputs
        if undeclared:
            errors.append(f"{spec.job_id} references undeclared changes outputs: {', '.join(sorted(undeclared))}")

    snapshot = specs.get("snapshot-warmup")
    if snapshot is None or "needs.changes.outputs['dependency-graph']" not in snapshot.condition:
        errors.append("snapshot-warmup does not fan out on dependency-graph")

    k8s = specs.get("test-leader-k8s")
    if k8s is None or ":bluetape4k-leader-k8s:k8sTest" not in k8s.block:
        errors.append("test-leader-k8s does not run k8sTest")

    contract = specs.get("ci-contract")
    if contract is None or "validate_ci_fanout.py --static" not in contract.block:
        errors.append("ci-contract does not invoke the static validator")

    manual_contract = specs.get(MANUAL_CONTRACT_JOB_ID)
    if manual_contract is None:
        errors.append("manual-contract job is missing")
    else:
        if "needs:" not in manual_contract.block or "changes" not in manual_contract.block:
            errors.append("manual-contract does not depend on changes")
        for output in (MANUAL_CONTRACT_OUTPUT, GLOBAL_CONFIG_OUTPUT):
            if f"needs.changes.outputs['{output}']" not in manual_contract.condition:
                errors.append(f"manual-contract condition does not reference {output}")
        if "workflow_dispatch" not in manual_contract.condition:
            errors.append("manual-contract condition does not include workflow_dispatch")
        if "validate_manual_contract.py" not in manual_contract.block:
            errors.append("manual-contract does not invoke the manual validator")

    status = specs.get("ci-status")
    if status is None:
        errors.append("ci-status job is missing")
    else:
        if "toJSON(needs)" not in status.block:
            errors.append("ci-status does not pass toJSON(needs) to the runtime validator")
        if "validate_ci_fanout.py --runtime" not in status.block:
            errors.append("ci-status does not invoke the runtime validator")
        if "skipped jobs treated as success" in status.block:
            errors.append("ci-status still treats skipped jobs as success")
        if "- ci-contract" not in status.block:
            errors.append("ci-status does not depend on ci-contract")
        if f"- {MANUAL_CONTRACT_JOB_ID}" not in status.block:
            errors.append("ci-status does not depend on manual-contract")

    return errors


def runtime_errors(needs: dict[str, Any], event_name: str, specs: dict[str, JobSpec]) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    report: list[str] = []
    for required in ("changes", "build", "ci-contract"):
        result = needs.get(required, {}).get("result")
        if result != "success":
            errors.append(f"required job {required} result is {result!r}, expected success")

    for job_id, payload in needs.items():
        result = payload.get("result")
        if result in {"failure", "cancelled"}:
            errors.append(f"{job_id} finished with {result}")
        elif result not in {"success", "skipped"}:
            errors.append(f"{job_id} finished with unexpected result {result!r}")

    outputs = needs.get("changes", {}).get("outputs") or {}
    for spec in specs.values():
        if not (spec.job_id.startswith(TEST_JOB_PREFIX) or spec.job_id == "compile-benchmark"):
            continue
        expected = event_name == "workflow_dispatch" or any(
            str(outputs.get(key, "false")).lower() == "true" for key in spec.output_keys
        )
        payload = needs.get(spec.job_id)
        if payload is None:
            errors.append(f"missing runtime result for {spec.job_id}")
            continue
        result = payload.get("result")
        if expected and result == "skipped":
            errors.append(f"{spec.job_id} was skipped although its impact filter is true")
            report.append(f"{spec.job_id}: REQUIRED but skipped")
        elif expected:
            report.append(f"{spec.job_id}: REQUIRED ({result})")
        elif result == "skipped":
            report.append(f"{spec.job_id}: N/A (intended skip)")
        else:
            report.append(f"{spec.job_id}: N/A condition false, result={result}")

    manual_spec = specs.get(MANUAL_CONTRACT_JOB_ID)
    if manual_spec is not None:
        expected = event_name == "workflow_dispatch" or any(
            str(outputs.get(key, "false")).lower() == "true"
            for key in manual_spec.output_keys
        )
        payload = needs.get(MANUAL_CONTRACT_JOB_ID)
        if payload is None:
            errors.append(f"missing runtime result for {MANUAL_CONTRACT_JOB_ID}")
        else:
            result = payload.get("result")
            if expected and result == "skipped":
                errors.append(
                    f"{MANUAL_CONTRACT_JOB_ID} was skipped although its impact filter is true"
                )
                report.append(f"{MANUAL_CONTRACT_JOB_ID}: REQUIRED but skipped")
            elif expected:
                report.append(f"{MANUAL_CONTRACT_JOB_ID}: REQUIRED ({result})")
            elif result == "skipped":
                report.append(f"{MANUAL_CONTRACT_JOB_ID}: N/A (intended skip)")
            else:
                report.append(f"{MANUAL_CONTRACT_JOB_ID}: N/A condition false, result={result}")
    return errors, report


def load_workflow() -> tuple[Path, str]:
    path = workflow_path()
    try:
        return path, path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError(f"cannot read workflow {path}: {exc}") from exc


def run_static() -> int:
    path, workflow = load_workflow()
    errors = static_errors(workflow)
    if errors:
        print(f"CI fan-out contract FAILED: {path}", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"CI fan-out contract OK: {path}")
    return 0


def run_runtime() -> int:
    _, workflow = load_workflow()
    raw_needs = os.environ.get("CI_NEEDS_JSON")
    if not raw_needs:
        print("CI_NEEDS_JSON is required for runtime validation", file=sys.stderr)
        return 2
    try:
        needs = json.loads(raw_needs)
    except json.JSONDecodeError as exc:
        print(f"CI_NEEDS_JSON is not valid JSON: {exc}", file=sys.stderr)
        return 2
    if not isinstance(needs, dict):
        print("CI_NEEDS_JSON must be a JSON object", file=sys.stderr)
        return 2
    errors, report = runtime_errors(needs, os.environ.get("CI_EVENT_NAME", ""), job_specs(workflow))
    print("CI runtime fan-out report:")
    for line in report:
        print(f"- {line}")
    if errors:
        print("CI runtime fan-out FAILED:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("CI runtime fan-out OK")
    return 0


def run_self_test() -> int:
    path, workflow = load_workflow()
    static = static_errors(workflow)
    if static:
        print("self-test static contract failed: " + "; ".join(static), file=sys.stderr)
        return 1
    specs = job_specs(workflow)
    test_ids = [
        spec.job_id
        for spec in specs.values()
        if spec.job_id.startswith(TEST_JOB_PREFIX) or spec.job_id == "compile-benchmark"
    ]
    base: dict[str, Any] = {
        "changes": {"result": "success", "outputs": {"dependency-graph": "true"}},
        "build": {"result": "success"},
        "ci-contract": {"result": "success"},
        MANUAL_CONTRACT_JOB_ID: {"result": "success"},
    }
    base.update({job_id: {"result": "success"} for job_id in test_ids})
    broken = dict(base)
    broken["test-core"] = {"result": "skipped"}
    errors, _ = runtime_errors(broken, "pull_request", specs)
    if not any("test-core" in error and "skipped" in error for error in errors):
        print("self-test did not catch an impacted skipped job", file=sys.stderr)
        return 1
    manual_broken = dict(base)
    manual_broken["changes"] = {
        "result": "success",
        "outputs": {"dependency-graph": "false", MANUAL_CONTRACT_OUTPUT: "true", GLOBAL_CONFIG_OUTPUT: "false"},
    }
    manual_broken[MANUAL_CONTRACT_JOB_ID] = {"result": "skipped"}
    errors, _ = runtime_errors(manual_broken, "pull_request", specs)
    if not any(MANUAL_CONTRACT_JOB_ID in error and "skipped" in error for error in errors):
        print("self-test did not catch an impacted manual contract skip", file=sys.stderr)
        return 1
    n_a = dict(base)
    n_a["changes"] = {
        "result": "success",
        "outputs": {
            "dependency-graph": "false",
            MANUAL_CONTRACT_OUTPUT: "false",
            GLOBAL_CONFIG_OUTPUT: "false",
        },
    }
    n_a.update({job_id: {"result": "skipped"} for job_id in test_ids})
    n_a[MANUAL_CONTRACT_JOB_ID] = {"result": "skipped"}
    errors, _ = runtime_errors(n_a, "pull_request", specs)
    if errors:
        print("self-test rejected intended N/A skips: " + "; ".join(errors), file=sys.stderr)
        return 1
    print(f"CI fan-out self-test OK: {path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--static", action="store_true")
    mode.add_argument("--runtime", action="store_true")
    mode.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.static:
        return run_static()
    if args.runtime:
        return run_runtime()
    return run_self_test()


if __name__ == "__main__":
    raise SystemExit(main())
