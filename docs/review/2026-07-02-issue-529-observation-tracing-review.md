# 이슈 #529 관찰 추적 검토

## 범위

- `leader-micrometer` 관찰 레코더/리스너 및 공개 상수.
- `leader-spring-boot` 관찰 자동 구성, 속성 바인딩 및 미터-레코더 공존.
- `examples/prometheus-dashboard` 데모 관찰 처리기 및 README 범위.

## 7단계 평결

| Tier | Verdict | Evidence |
|---|---|---|
| Security | PASS | Raw lock name, leader ID, and raw throwable export are opt-in. `includeExceptionDetails=false` by default. README warns that values are not redacted. |
| Performance | PASS | No per-lock queues or background workers. Recorder no-ops attempt/start callbacks and returns immediately for `ObservationRegistry.NOOP`. |
| Stability | PASS | Observations are short terminal events, so same-lock start/stop pairing cannot leak scopes. `MultithreadingTester` covers concurrent terminal callbacks. |
| API/Compatibility | PASS | Existing meter names stay unchanged. Custom generic `LeaderAopMetricsRecorder` still suppresses default meter recorder; custom Observation recorder does not. |
| Spring/Config | PASS | `LeaderObservationAutoConfiguration` is ordered after metrics and before AOP; properties are guarded by parent and child switches. |
| Docs/Examples | PASS | English/Korean README files describe direct API, Spring properties, demo handler, cardinality risks, and #559 lease-extension follow-up. |
| Evidence | PASS_WITH_NOTE | Focused tests pass. Full three-module run failed in pre-existing Redis-backed spring tests with `Connection refused` to `localhost:34545`; new tests passed in that run. |

## 조사 결과

P0/P1 결과가 남아 있지 않습니다.

검토 중 해결됨:

- `acquire.elapsed.ms` 및 `execution.elapsed.ms`는 처음에는 카디널리티가 낮은 관찰 키였습니다. 이제 경과된 값은 제한이 없기 때문에 높은 카디널리티 키를 사용합니다.
- 현재 Spring AOP는 실제 리더 ID를 노출하지 않습니다. 구현은 노드 ID 또는 잠금 이름에서 `leader.id`를 합성하지 않습니다. 문서에는 `include-leader-id=true`에 `LeaderAopMetricsContext.Identified`가 필요하다고 나와 있습니다.

## 검증 증거

| Command | Result |
|---|---|
| `./gradlew :bluetape4k-leader-micrometer:test --tests '*MicrometerObservation*' :bluetape4k-leader-spring-boot:test --tests '*LeaderObservationAutoConfigurationTest' --tests '*LeaderMicrometerAutoConfigurationTest' :examples:prometheus-dashboard:test --no-daemon --no-configuration-cache --console=plain` | PASS: 16 micrometer tests, 16 spring tests, 1 example test |
| `./gradlew :examples:prometheus-dashboard:compileKotlin :examples:prometheus-dashboard:compileTestKotlin --no-daemon --no-configuration-cache --console=plain` | PASS |
| `./gradlew :bluetape4k-leader-micrometer:dependencies --configuration runtimeClasspath ...` and `:bluetape4k-leader-spring-boot:dependencies --configuration runtimeClasspath ...` | PASS: no `opentelemetry-*`, `micrometer-tracing-bridge`, exporter, or collector matches |
| `git diff --check` | PASS |
| Full attempted run: `./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-spring-boot:test :examples:prometheus-dashboard:test ...` | FAIL in existing Redis-backed spring tests: Redisson/Lettuce connection refused to `localhost:34545`; new observation tests passed |

## 잔여 위험

- 임대 연장 관찰에는 코어 후크가 필요하며 #559에서 추적됩니다.
- 진정한 수명이 긴 범위에는 향후 호출별 AOP/코어 SPI가 필요합니다. #529는 의도적으로 독립형 터미널 관찰을 내보냅니다.
