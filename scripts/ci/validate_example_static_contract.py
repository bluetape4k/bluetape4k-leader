#!/usr/bin/env python3
"""Validate static contracts for the leader example assets and manifests."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


PROMETHEUS_ALERTS = (
    "LeaderElectionNoAcquisitions",
    "LeaderElectionBackendErrors",
    "LeaderElectionTaskFailures",
    "LeaderHistorySinkFailures",
    "LeaderHistoryAcquireMissing",
    "LeaderActiveGaugeAnomaly",
    "LeaderLeaseRiskHighExecutionTime",
    "LeaderPrometheusScrapeMissing",
    "LeaderBackendConnectivityDown",
    "LeaderBackendConnectivityUnknown",
    "LeaderBackendConnectivityProbeExceptions",
)

README_TOKENS = (
    "examples-prometheus-dashboard-alert-runbook-01.png",
    "leader-alerts.yml",
    "LeaderElectionBackendErrors",
    "LeaderBackendConnectivityDown",
    "LeaderBackendConnectivityUnknown",
    "LeaderBackendConnectivityProbeExceptions",
    "leader_backend_connectivity_total",
    "PrometheusBackendConnectivityProbe",
    "DEMO_BACKEND_PROBE_TIMEOUT_MS",
    "PROVIDER_EXCEPTION",
    "LeaderHistorySinkFailures",
    "max by (lock_name) (leader_aop_active)",
)


def _read(root: Path, relative: str, errors: list[str]) -> str:
    path = root / relative
    if not path.is_file():
        errors.append(f"missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def _require(text: str, token: str, source: str, errors: list[str]) -> None:
    if token not in text:
        errors.append(f"{source} is missing required token: {token}")


def _forbid(text: str, token: str, source: str, errors: list[str]) -> None:
    if token in text:
        errors.append(f"{source} contains forbidden token: {token}")


def validate_prometheus_assets(root: Path) -> list[str]:
    errors: list[str] = []
    config = _read(
        root,
        "examples/prometheus-dashboard/provisioning/prometheus/prometheus.yml",
        errors,
    )
    rules = _read(
        root,
        "examples/prometheus-dashboard/provisioning/prometheus/rules/leader-alerts.yml",
        errors,
    )
    compose = _read(root, "examples/prometheus-dashboard/docker-compose.yml", errors)
    dashboard = _read(
        root,
        "examples/prometheus-dashboard/provisioning/grafana/dashboards/leader-dashboard.json",
        errors,
    )
    application = _read(
        root,
        "examples/prometheus-dashboard/src/main/resources/application.yml",
        errors,
    )
    english = _read(root, "examples/prometheus-dashboard/README.md", errors)
    korean = _read(root, "examples/prometheus-dashboard/README.ko.md", errors)

    _require(config, "rule_files:", "prometheus.yml", errors)
    _require(config, "/etc/prometheus/rules/leader-alerts.yml", "prometheus.yml", errors)
    _require(
        compose,
        "./provisioning/prometheus/rules:/etc/prometheus/rules:ro",
        "docker-compose.yml",
        errors,
    )

    for alert in PROMETHEUS_ALERTS:
        _require(rules, f"alert: {alert}", "leader-alerts.yml", errors)
    for token in (
        'leader_aop_lock_not_acquired_total{reason="BACKEND_ERROR"}',
        "leader_aop_task_failed_total",
        'leader_history_sink_failures_total{sink!="NoopLeaderHistorySink"}',
        'leader_history_acquire_missing_total{sink!="NoopLeaderHistorySink"}',
        'leader_aop_active{lock_name="dashboard-job"} > 1',
        "leader_aop_execution_duration_seconds_sum",
        "leader_backend_connectivity_total",
        'leader_backend_connectivity_total{status="DOWN",reason="DISCONNECTED"}',
        'leader_backend_connectivity_total{status="UNKNOWN",reason=~"CLIENT_STATE_UNCONFIRMED|PROVIDER_UNSUPPORTED"}',
        'leader_backend_connectivity_total{status="UNKNOWN",reason="PROVIDER_EXCEPTION"}',
        "notification: no-page",
        "for: 5m",
        "for: 10m",
        'absent(up{job="bluetape4k-leader"}) or up{job="bluetape4k-leader"} == 0',
        "https://github.com/bluetape4k/bluetape4k-leader/blob/develop/"
        "examples/prometheus-dashboard/README.md#alert-runbooks",
    ):
        _require(rules, token, "leader-alerts.yml", errors)

    for title in (
        "Acquisition Success Ratio",
        "Backend Error Rate",
        "Task Failure Rate",
        "History Sink Signals",
        "Lease Risk",
    ):
        _require(dashboard, f'"title": "{title}"', "leader-dashboard.json", errors)
    for token in (
        "clamp_min",
        'leader_aop_lock_not_acquired_total{reason=\\"BACKEND_ERROR\\"}',
        "max by (lock_name) (leader_aop_active)",
    ):
        _require(dashboard, token, "leader-dashboard.json", errors)

    for token in (
        "mode: REDACT",
        "redacted-value: redacted-lock",
        "backend-probe:",
        "DEMO_BACKEND_PROBE_FIXED_DELAY_MS",
        "DEMO_BACKEND_PROBE_INITIAL_DELAY_MS",
        "DEMO_BACKEND_PROBE_TIMEOUT_MS",
    ):
        _require(application, token, "application.yml", errors)
    _forbid(application, "mode: RAW", "application.yml", errors)

    svg_relative = "docs/images/readme-diagrams/examples-prometheus-dashboard-alert-runbook-01.svg"
    png_relative = "docs/images/readme-diagrams/examples-prometheus-dashboard-alert-runbook-01.png"
    svg = _read(root, svg_relative, errors)
    if not (root / png_relative).is_file():
        errors.append(f"missing required file: {png_relative}")
    _require(svg, "Prometheus Alert And Runbook Flow", svg_relative, errors)
    _require(svg, 'data-connector="observe-only-note"', svg_relative, errors)

    for readme, source in (
        (english, "examples/prometheus-dashboard/README.md"),
        (korean, "examples/prometheus-dashboard/README.ko.md"),
    ):
        for token in README_TOKENS:
            _require(readme, token, source, errors)
    return errors


def validate_operator_manifests(root: Path) -> list[str]:
    errors: list[str] = []
    rbac = _read(root, "examples/k8s-operator/k8s/rbac.yaml", errors)
    deployment = _read(root, "examples/k8s-operator/k8s/deployment.yaml", errors)

    for token in (
        'resources: [ "leases" ]',
        'verbs: [ "get", "create", "update", "patch" ]',
    ):
        _require(rbac, token, "rbac.yaml", errors)
    _forbid(rbac, "delete", "rbac.yaml", errors)

    for token in (
        "image: ghcr.io/bluetape4k/bluetape4k-k8s-operator:0.5.0",
        "startupProbe:",
        "livenessProbe:",
        "readinessProbe:",
        "path: /actuator/health",
    ):
        _require(deployment, token, "deployment.yaml", errors)
    _forbid(deployment, ":latest", "deployment.yaml", errors)
    return errors


def validate(root: Path) -> list[str]:
    return validate_prometheus_assets(root) + validate_operator_manifests(root)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = args.root.resolve()
    errors = validate(root)
    if errors:
        print("Leader example static contract FAILED:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("Leader example static contract OK: Prometheus assets and Kubernetes manifests validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
