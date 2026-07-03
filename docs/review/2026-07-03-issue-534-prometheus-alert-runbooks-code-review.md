# Issue #534 code review

## Scope

- `examples/prometheus-dashboard` Prometheus rules, dashboard, Compose wiring, README parity, and tests.
- `docs/images/readme-diagrams/examples-prometheus-dashboard-alert-runbook-01.{svg,png}`.
- Internal spec and plan for issue #534.

## Step 6-R result

Final gate: P0 = 0, P1 = 0.

| Tier | Focus | Result |
|---|---|---|
| Tier 1 | Performance and query cost | P0/P1 = 0. P3 noted 5s Grafana refresh is local-demo oriented. |
| Tier 2 | Stability and false alert risk | P0/P1 = 0 after excluding `NoopLeaderHistorySink` from history alerts and scoping active-gauge anomaly to `dashboard-job`. |
| Tier 3 | Security | P0/P1 = 0 after documenting Prometheus lifecycle exposure, auth/TLS/reverse-proxy requirements, and exception-label privacy. |
| Tier 4 | Operator/Ops | P0/P1 = 0 after avoiding default no-op history false alerts and constraining active-gauge anomaly to the single-leader demo lock. |
| Tier 5 | Developer/API/test | P0/P1 = 0. Static tests are intentionally lightweight; `promtool` and `jq` are the syntax gates. |
| Tier 6 | User/caller docs | P0/P1 = 0 after switching `runbook_url` to an absolute GitHub URL, splitting active gauge out of lease risk, and fixing diagram badge/text spacing. |

## Verification evidence

- `./gradlew :examples:prometheus-dashboard:test --no-configuration-cache --console=plain`
  - `PrometheusAssetsTest`: 4 tests passed.
  - `PrometheusScrapeTest`: 1 test passed.
- `./gradlew :examples:prometheus-dashboard:processAot :examples:prometheus-dashboard:processTestAot :examples:prometheus-dashboard:test --no-configuration-cache --console=plain`
  - Build successful.
- `docker run --rm --entrypoint promtool -v "$PWD/examples/prometheus-dashboard/provisioning/prometheus:/etc/prometheus:ro" prom/prometheus:v2.55.1 check rules /etc/prometheus/rules/leader-alerts.yml`
  - SUCCESS: 8 rules found.
- `docker run --rm --entrypoint promtool -v "$PWD/examples/prometheus-dashboard/provisioning/prometheus:/etc/prometheus:ro" prom/prometheus:v2.55.1 check config /etc/prometheus/prometheus.yml`
  - SUCCESS: 1 rule file found; config syntax valid.
- `jq empty examples/prometheus-dashboard/provisioning/grafana/dashboards/leader-dashboard.json`
  - JSON parsed.
- Diagram QA:
  - PNG render: `cairosvg ... -s 2`.
  - Geometry: `geometry_failures=0`.
  - Endpoint: PASS.
  - Mixed-corner: `paths=12 q_bends=9 failures=0`.
  - Connector: `markers=6 connectors=12 cards=12 intrusions=0 crossings=0`.
  - Marker parity: `marker_color_failures=0`.
- `git diff --check`
  - Clean.

## Residual notes

- `leader_history_*` meters are visible in the demo through `NoopLeaderHistorySink`, but alert rules exclude that sink because it intentionally returns no acquire key.
- `LeaderLeaseRiskHighExecutionTime` remains a symptom rule based on completed execution duration, not a direct lease-extension failure detector.
