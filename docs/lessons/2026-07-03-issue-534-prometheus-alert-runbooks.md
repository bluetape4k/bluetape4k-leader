# Issue #534 Prometheus alert runbooks

## Context

The Prometheus dashboard example needed alert rules, Grafana panels, runbook
documentation, and a diagram for operational leader-election signals.

## Decision

- Keep all assets example-scoped under `examples/prometheus-dashboard`.
- Use only currently exported metrics for rules and panels.
- Exclude `NoopLeaderHistorySink` from history alerts because the default no-op
  sink intentionally returns no acquire key.
- Scope active-gauge anomaly to the demo single-leader lock, `dashboard-job`,
  so copied group-election workloads do not false-page.
- Treat lease risk as a completed-duration symptom until direct lease-extension
  instrumentation exists.

## Outcome

The example now has Prometheus alert rules, Grafana alert panels, English/Korean
runbooks, a rendered alert/runbook diagram, and static asset tests.

## Verification

- `:examples:prometheus-dashboard:test` passed with 5 tests.
- `:examples:prometheus-dashboard:processAot :examples:prometheus-dashboard:processTestAot :examples:prometheus-dashboard:test` passed.
- `promtool check rules` found 8 rules.
- `promtool check config` found 1 rule file and valid config.
- Diagram audits passed with no geometry failures, no intrusions, and no crossings.

## Next time

When adding example alerts, check whether the metric is present in the runnable
example, whether default no-op components produce healthy counter increments,
and whether copied rules are safe for group-election locks.
