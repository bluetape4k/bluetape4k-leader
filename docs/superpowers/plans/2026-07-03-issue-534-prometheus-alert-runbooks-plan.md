# Issue #534 Prometheus alert and runbook plan

## 한국어 해설

이 문서는 `Issue #534 Prometheus alert and runbook plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Gate 0/1 - 방향 확인

조치:

- Work from a dedicated branch/worktree based on latest `origin/develop`.
- Read issue #534, current Prometheus dashboard example, related merged PR #257,
  and current Micrometer metric source.

예상 DoD:

- Worktree exists.
- Issue scope, existing assets, and current metric names are known.

단계 DoD:

- Worktree: `.worktrees/feat-issue-534-prometheus-alert-runbooks`.
- Current metric evidence: `MicrometerNames`, `MicrometerLeaderAopMetricsRecorder`,
  history decorators, dashboard JSON, README, Prometheus config, and Compose.

## Gate 2/3 - 설계

조치:

- Record a design that maps issue requirements to existing metrics without
  inventing unsupported lease-extension metrics.
- Define alert, dashboard, README, diagram, and validation scope.

예상 DoD:

- Spec and plan files exist.
- P0/P1 design risks are either mitigated or explicitly constrained.

검증:

- Review spec against issue body and metric source.

## Gate 4 - TDD와 구현

조치:

1. Add a failing static test for Prometheus rule provisioning, dashboard alert
   expressions, and README diagram/rule references.
2. Add `provisioning/prometheus/rules/leader-alerts.yml` using the exact
   alert expressions, windows, labels, and annotations defined in the spec.
3. Add `rule_files` to `prometheus.yml` and mount the rules directory in
   `docker-compose.yml`.
4. Add Grafana alert-oriented panels.
5. Update `README.md` and `README.ko.md`.
6. Add a README alert/runbook diagram as SVG plus rendered PNG.

예상 DoD:

- The new test fails before implementation for missing rule/config/dashboard
  evidence and passes after implementation.
- No production Kotlin behavior changes are required.
- Test code follows `bluetape4k-code-patterns`: JUnit 5, class-level reusable
  state, bluetape4k assertions, and no weak boolean-only coverage for core
  assets.

대상 파일:

- `examples/prometheus-dashboard/src/test/kotlin/.../PrometheusAssetsTest.kt`
- `examples/prometheus-dashboard/provisioning/prometheus/prometheus.yml`
- `examples/prometheus-dashboard/provisioning/prometheus/rules/leader-alerts.yml`
- `examples/prometheus-dashboard/provisioning/grafana/dashboards/leader-dashboard.json`
- `examples/prometheus-dashboard/docker-compose.yml`
- `examples/prometheus-dashboard/README.md`
- `examples/prometheus-dashboard/README.ko.md`
- `docs/images/readme-diagrams/examples-prometheus-dashboard-alert-runbook-01.svg`
- `docs/images/readme-diagrams/examples-prometheus-dashboard-alert-runbook-01.png`

## Gate 4-T - 테스트

조치:

- Run the affected example test module serially.
- Run rule syntax validation with `promtool` when available locally or through
  the configured Prometheus image.
- Validate JSON and whitespace.

예상 DoD:

- `./gradlew :examples:prometheus-dashboard:test --no-configuration-cache --console=plain` passes.
- Prometheus rule syntax check passes or an exact environment blocker is
  recorded with static test fallback.
- `jq` parses the Grafana dashboard.
- `git diff --check` passes.

## Gate 5/6 - 검증과 리뷰

조치:

- Verify implementation against this spec and plan.
- Run material Step 6-R review lanes for operator/Ops, developer/API/test, user
  documentation, and diagram/readability risks.
- Run diagram render and geometry audit.

예상 DoD:

- P0/P1 findings are zero after fixes.
- Diagram evidence includes render command, dimensions, connector/card counts,
  and audit result.

## Gate 7 - PR

조치:

- Commit with Lore trailers.
- Open PR against `develop` closing #534.
- Set assignee `debop`, milestone `0.5.0`, and issue labels where supported.
- Verify PR body live with final `## DoD Status` section.
- Watch required CI until completed or report exact running/failing state.

예상 DoD:

- PR exists with verified metadata.
- PR body lists tests, rule validation, diagram evidence, and known gaps.
- No merge is performed without a later user request.
