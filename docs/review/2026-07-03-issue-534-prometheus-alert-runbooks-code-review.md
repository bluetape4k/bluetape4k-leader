# 이슈 #534 코드 검토

## 범위

- `examples/prometheus-dashboard` Prometheus 규칙, 대시보드, Compose 배선, README 패리티 및 테스트.
- `docs/images/readme-diagrams/examples-prometheus-dashboard-alert-runbook-01.{svg,png}`.
- Issue #534에 대한 내부 사양 및 계획.

## 6-R단계 결과

최종 게이트: P0 = 0, P1 = 0.

| Tier | Focus | Result |
|---|---|---|
| Tier 1 | Performance and query cost | P0/P1 = 0. P3 noted 5s Grafana refresh is local-demo oriented. |
| Tier 2 | Stability and false alert risk | P0/P1 = 0 after excluding `NoopLeaderHistorySink` from history alerts and scoping active-gauge anomaly to `dashboard-job`. |
| Tier 3 | Security | P0/P1 = 0 after documenting Prometheus lifecycle exposure, auth/TLS/reverse-proxy requirements, and exception-label privacy. |
| Tier 4 | Operator/Ops | P0/P1 = 0 after avoiding default no-op history false alerts and constraining active-gauge anomaly to the single-leader demo lock. |
| Tier 5 | Developer/API/test | P0/P1 = 0. Static tests are intentionally lightweight; `promtool` and `jq` are the syntax gates. |
| Tier 6 | User/caller docs | P0/P1 = 0 after switching `runbook_url` to an absolute GitHub URL, splitting active gauge out of lease risk, and fixing diagram badge/text spacing. |

## 검증 증거

- `./gradlew :examples:prometheus-dashboard:test --no-configuration-cache --console=plain`
  - `PrometheusAssetsTest`: 4개 테스트를 통과했습니다.
  - `PrometheusScrapeTest`: 1개의 테스트가 통과되었습니다.
- `./gradlew :examples:prometheus-dashboard:processAot :examples:prometheus-dashboard:processTestAot :examples:prometheus-dashboard:test --no-configuration-cache --console=plain`
  - 빌드에 success했습니다.
- `docker run --rm --entrypoint promtool -v "$PWD/examples/prometheus-dashboard/provisioning/prometheus:/etc/prometheus:ro" prom/prometheus:v2.55.1 check rules /etc/prometheus/rules/leader-alerts.yml`
  - success: 8개의 규칙이 발견되었습니다.
- `docker run --rm --entrypoint promtool -v "$PWD/examples/prometheus-dashboard/provisioning/prometheus:/etc/prometheus:ro" prom/prometheus:v2.55.1 check config /etc/prometheus/prometheus.yml`
  - success: 규칙 파일 1개를 찾았습니다. 구성 구문이 유효합니다.
- `jq empty examples/prometheus-dashboard/provisioning/grafana/dashboards/leader-dashboard.json`
  - JSON이 구문 분석되었습니다.
- 다이어그램 QA:
  - PNG 렌더링: `cairosvg ... -s 2`.
  - 기하학: `geometry_failures=0`.
  - 끝점: 통과.
  - 혼합 코너: `paths=12 q_bends=9 failures=0`.
  - 커넥터: `markers=6 connectors=12 cards=12 intrusions=0 crossings=0`.
  - 마커 패리티: `marker_color_failures=0`.
- `git diff --check`
  - 깨끗하다.

## 잔여 지폐

- `leader_history_*` 미터는 `NoopLeaderHistorySink`를 통해 데모에서 볼 수 있지만 의도적으로 획득 키를 반환하지 않기 때문에 경고 규칙은 해당 싱크를 제외합니다.
- `LeaderLeaseRiskHighExecutionTime`는 직접적인 임대 연장 failure 감지기가 아닌 완료된 실행 기간을 기반으로 하는 증상 규칙으로 남아 있습니다.
