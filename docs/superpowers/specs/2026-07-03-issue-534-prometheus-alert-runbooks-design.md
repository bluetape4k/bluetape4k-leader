# Issue #534 Prometheus alert and runbook design

## Context

Issue #534 extends `examples/prometheus-dashboard` with example-scoped
Prometheus alert rules, Grafana panels, README runbooks, and a supporting
diagram. The example already exports leader AOP metrics through
`/actuator/prometheus`, provisions Prometheus/Grafana with Docker Compose, and
documents JVM-local active gauge semantics.

## Source evidence

- GitHub issue #534 requires alert rules for acquisition failures, backend
  errors, missing history sink writes, active gauge anomalies, and lease
  extension risk.
- Alert-relevant `MicrometerNames` currently maps to these Prometheus metric families:
  `leader_aop_attempts_total`, `leader_aop_acquired_total`,
  `leader_aop_lock_not_acquired_total`, `leader_aop_task_failed_total`,
  `leader_aop_execution_duration_seconds_*`, `leader_aop_active`,
  `leader_history_sink_failures_total`, and
  `leader_history_acquire_missing_total`.
- `leader_aop_active` is JVM-local; Prometheus examples must use
  `max by (lock_name)` rather than `sum`.
- `LockExtender` does not yet expose a lease-extension metric or observation
  hook. The example can only alert on lease-risk symptoms visible today:
  sustained execution duration near the demo lease time.

## Scope

### In scope

- Add `leader-alerts.yml` under the Prometheus provisioning tree.
- Wire the rule file into `prometheus.yml` and Docker Compose.
- Add Grafana panels for alert-oriented signals.
- Add tests that fail before the new provisioning assets exist, then pass after
  implementation.
- Update `README.md` and `README.ko.md` with alert semantics, runbook actions,
  PromQL examples, and validation guidance.
- Add a README diagram showing the leader work path separately from the
  Prometheus scrape/alert/runbook path.

### Out of scope

- Promoting alert rules as reusable production defaults.
- Adding new production metrics, lease-extension hooks, or OpenTelemetry
  exporters.
- Running `docker compose up` as a required CI validation path.

## Alert rule design

| Alert | Expression | Window / `for` | Labels | Purpose |
|---|---|---|---|---|
| `LeaderElectionNoAcquisitions` | `sum by (lock_name) (increase(leader_aop_attempts_total[5m])) > 0 unless sum by (lock_name) (increase(leader_aop_acquired_total[5m])) > 0` | 5m / 5m | `severity=warning`, `component=leader-election` | Detect jobs that keep attempting but never become leader. |
| `LeaderElectionBackendErrors` | `sum by (lock_name) (rate(leader_aop_lock_not_acquired_total{reason="BACKEND_ERROR"}[5m])) > 0` | 5m / 2m | `severity=warning`, `component=leader-election` | Detect backend acquisition errors reported by the AOP path. |
| `LeaderElectionTaskFailures` | `sum by (lock_name, exception) (rate(leader_aop_task_failed_total[5m])) > 0` | 5m / 2m | `severity=warning`, `component=leader-election` | Detect exceptions thrown by elected task bodies. |
| `LeaderHistorySinkFailures` | `sum by (sink) (rate(leader_history_sink_failures_total{sink!="NoopLeaderHistorySink"}[5m])) > 0` | 5m / 2m | `severity=warning`, `component=leader-history` | Detect real history/audit sink write failures while excluding the demo no-op sink. |
| `LeaderHistoryAcquireMissing` | `sum by (sink) (rate(leader_history_acquire_missing_total{sink!="NoopLeaderHistorySink"}[5m])) > 0` | 5m / 5m | `severity=warning`, `component=leader-history` | Detect missing history acquire writes for real sinks while excluding the default no-op sink. |
| `LeaderActiveGaugeAnomaly` | `leader_aop_active{lock_name="dashboard-job"} > 1` | instant / 1m | `severity=critical`, `component=leader-election` | Preserve `instance` and detect one JVM reporting more than one active task for this single-leader demo lock. |
| `LeaderLeaseRiskHighExecutionTime` | `(sum by (lock_name) (rate(leader_aop_execution_duration_seconds_sum[5m])) / clamp_min(sum by (lock_name) (rate(leader_aop_execution_duration_seconds_count[5m])), 0.001)) > 24` | 5m / 5m | `severity=warning`, `component=leader-election` | Demonstrate a delayed lease-risk symptom for the example's 30s lease time. |
| `LeaderPrometheusScrapeMissing` | `absent(up{job="bluetape4k-leader"}) or up{job="bluetape4k-leader"} == 0` | instant / 1m | `severity=critical`, `component=observability` | Detect missing scrape target, absent series, or app health failure. |

The lease-risk rule must explicitly state that it is a symptom rule, not a
lease-extension failure detector. A direct extension alert needs future core
instrumentation. Because the current timer records completed executions, this
rule can be delayed and can hide a single long-running execution in the mean.
It is included as an example-scoped heuristic only.

Every rule must include `runbook_url: "README.md#alert-runbooks"` and a concise
summary/description annotation.

## Documentation and diagram design

README updates must:

- Keep English/Korean parity.
- Explain alert interpretation before remediation commands.
- State that high-cardinality lock names should stay redacted unless the lock
  set is small and static.
- Keep the example-scoped boundary explicit.

The new diagram must:

- Place leader work execution on one path.
- Place Actuator scrape, Prometheus rule evaluation, Grafana, and operator
  runbooks on a separate observation path.
- Avoid implying Prometheus or Grafana triggers leader work.
- Render to SVG and PNG under `docs/images/readme-diagrams`.

## Acceptance criteria

- Prometheus config loads the alert rule file.
- Docker Compose mounts the rule directory read-only.
- Grafana dashboard contains alert-oriented panels that use existing metric
  names and JVM-local gauge semantics.
- README files embed the alert/runbook diagram and document the same alert set.
- Static tests verify Prometheus provisioning, Grafana expressions, and README
  references.
- Existing scrape smoke test still verifies representative leader metrics.
- Diagram validation records render and geometry evidence.
