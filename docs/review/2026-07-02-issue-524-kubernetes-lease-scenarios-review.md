# 검토 - Issue #524 Kubernetes Lease 시나리오 벤치마크

범위:

- `benchmark/src/kubernetesBenchmark/kotlin/io/bluetape4k/leader/benchmark/KubernetesBackendLeaderElectorBenchmark.kt`
- `benchmark/README.md`
- `benchmark/README.ko.md`
- `docs/benchmarks/2026-07-02-issue-524-kubernetes-lease-scenarios.md`
- `docs/benchmarks/2026-07-02-issue-524-kubernetes-scenarios-*.json`
- `docs/images/readme-charts/leader-kubernetes-scenarios-*-chart-01.{svg,png}`

## 조사 결과

P0/P1 발견 항목이 없습니다.

## 리뷰 노트

- 벤치마크는 `kubernetesBenchmark` 소스 세트에서 격리된 상태로 유지되므로 Fabric8 Vert.x 4 런타임 라인은 기본 미리 보기 백엔드 클래스 경로를 변경하지 않습니다.
- 공개 선거인 행에는 차단 및 정지 차선 모두에 대한 신규 획득, 활성 보유자 건너뛰기, 만료된 인수가 포함됩니다.
- 동일 보유자 갱신 및 오래된 `resourceVersion` 충돌 행은 직접 임대 API 프로브로 문서화되어 있습니다. 전체 사용자 작업 실행이 아닌 API 서버 업데이트 및 충돌 동작을 의도적으로 측정합니다.
- JMH 상태 설비는 시나리오 상태에 따라 호출 또는 평가판 범위에서 리소스를 재설정하여 이전 반복이 신규 또는 만료된 경로를 동일한 소유자 경로로 변환하는 것을 방지합니다.
- CodeGraph 검토에서는 벤치마크 소스 세트에 대해 변경된 기능 노드가 반환되지 않았으므로 수동 diff 검토와 컴파일, K3s JMH 스모크, 차트 QA, README 스위치 검증 및 `git diff --check`가 검토 증거로 사용되었습니다.

## 7층 관문

| Tier | Verdict | Evidence |
|---|---|---|
| Tier 1 Security | PASS | No production auth, credential, or network policy boundary changed; the benchmark uses the existing local K3s Testcontainers wrapper. |
| Tier 2 Architecture | PASS | New coverage stays in the existing benchmark source set and does not change production leader APIs. |
| Tier 3 Data/State | PASS | Lease fixtures are created, updated, cleaned, and isolated by scenario-specific JMH state. |
| Tier 4 Correctness | PASS | Blocking and suspend rows cover fresh acquire, pre-held skip, expired takeover, renewal update, and stale conflict scenarios. |
| Tier 5 Test/Benchmark | PASS | `compileKubernetesBenchmarkKotlin`, `kubernetesBenchmarkBenchmarkJar`, and two K3s JMH smoke runs produced 10 throughput rows and 10 average-time rows. |
| Tier 6 Performance | PASS | README and benchmark report state that the numbers are short K3s smoke snapshots and separate direct API probes from full elector paths. |
| Tier 7 Docs/Release | PASS | README and README.ko were updated together with raw JSON, generated SVG/PNG charts, commands, tables, and interpretation. |

P0: 0 P1: 0
