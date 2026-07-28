# 이슈 #530 코드 검토

## 범위

- 문제: #530, `feat(leader-micrometer): add metric tag cardinality controls`
- 분기: `feat/issue-530-cardinality-controls`
- 마일스톤: `0.5.0`
- 검토 대상: `leader-micrometer`, Spring Boot 바인딩, 관찰 통합, Prometheus 대시보드 문서 및 README 로케일 패리티에 대한 메트릭 태그 카디널리티 제어.

## 칠층문 결과

| Tier | Result | Evidence |
|---|---|---|
| Tier 1 Performance | PASS | HASH now reuses a `ThreadLocal<MessageDigest>`; duration PromQL aggregates numerator and denominator by `lock_name`. |
| Tier 2 Stability | PASS | Explicit raw-name registration is tracked per exported tag; deregistration keeps collapsed gauges until the last raw source is removed; in-flight active gauges are retained. Rerun result: P0=0, P1=0. |
| Tier 3 Security | PASS | Observation listener and recorder sanitize opt-in `lock.name` and `leader.id`; Spring Observation auto-config passes the same tag policy. Rerun result: P0=0, P1=0. |
| Tier 4 Operations | PASS | Fresh affected-module verification completed; prior evidence gap was closed by full module tests. |
| Tier 5 Developer/API | PASS | Existing constructors remain available for `LeaderObservationOptions` and `LeaderAopProperties.Metrics`; javap confirmed binary-compatible entry points. |
| Tier 6 User/Caller | PASS | README EN/KO now explains RAW, HASH, TRUNCATE, allowlist risk, and that built-in meters do not currently emit `backend.name`. Rerun result: P0=0, P1=0. |
| Tier 7 Evidence | PASS | Tracked review artifact and lessons are included before PR creation; README architecture diagram was updated after the cardinality-control documentation changed. |

최종 차단 횟수: P0=0, P1=0.

## 발견 사항 및 수정 사항

- P1 안정성: `registerMetricsFor` 호출을 반복하고 수정된 태그 정리를 축소하면 미터가 너무 일찍 제거될 수 있습니다. 내보낸 태그별로 원시 등록을 추적하고 활성 게이지가 양수인 동안 정리를 보호하여 문제를 해결했습니다.
- P1 보안: 관찰 수신기는 높은 카디널리티 필드가 활성화된 경우 원시 `lock.name`를 노출할 수 있습니다. `LeaderObservationOptions.tagOptions`에서 `LeaderMetricTagSanitizer`를 구성하고 관찰 키 값을 내보내기 전에 정리하여 문제를 해결했습니다.
- P1 Spring 패리티: 관찰 자동 구성이 AOP 측정항목 태그 정책을 관찰 옵션에 전달하지 않았습니다. 스프링 바인딩 태그 옵션이 미터 및 관찰 경로 모두에 적용되도록 레코더 및 리스너 Bean 생성이 수정되었습니다.
- P2 성능: HASH 모드는 호출당 다이제스트 인스턴스를 생성했습니다. 스레드 로컬 SHA-256 다이제스트 및 사용별 재설정으로 수정되었습니다.
- P2 작업: 대시보드 기간 PromQL은 별도로 집계된 시리즈로 나뉩니다. 합계 및 개수에서 `sum by (lock_name)`가 일치하도록 수정되었습니다.
- P2/P3 문서: HASH 및 백엔드 이름 의미가 모호했습니다. README EN/KO는 이제 결정론적 무염 해시 위험, 정적 허용 목록 기대치 및 현재 `backend.name` 방출 상태를 명시합니다.
- 다이어그램 드리프트: 재사용된 `leader-micrometer` 아키텍처 다이어그램은 공유된 새니타이저 정책 없이 여전히 원시/높은 카디널리티 태그를 설명했습니다. `LeaderMetricTagSanitizer`, 위생화된 측정기/관찰 태그 및 REDACT/RAW/HASH/TRUNCATE 가드레일을 표시하도록 SVG/PNG를 업데이트했습니다.

## 검증

- `./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-spring-boot:test --tests '*LeaderMetricTagOptionsTest' --tests '*MicrometerLeaderAopMetricsRecorderTest' --tests '*InstrumentedLeaderElectorsTest' --tests '*MicrometerLeaderElectionListenerTest' --tests '*MicrometerObservationLeaderAopMetricsRecorderTest' --tests '*MicrometerObservationLeaderElectionListenerTest' --tests '*PrometheusExportTest' --tests '*LeaderAopPropertiesBindingTest' --tests '*LeaderMicrometerAutoConfigurationTest' --tests '*LeaderObservationAutoConfigurationTest'`
  - 결과: PASS, Micrometer 75 합격, Spring 23 합격.
- `./gradlew :examples:prometheus-dashboard:test --tests '*PrometheusScrapeTest'`
  - 결과: PASS, 1개 통과.
- `./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-spring-boot:test :examples:prometheus-dashboard:test`
  - 결과: PASS, 349 통과, `BUILD SUCCESSFUL in 1m 36s`.
- `javap -classpath leader-micrometer/build/classes/kotlin/main io.bluetape4k.leader.micrometer.LeaderObservationOptions`
  - 결과: PASS, 생성자에는 `(boolean, boolean, boolean, LeaderMetricTagOptions)`, `(boolean, boolean, boolean)` 및 no-arg가 포함됩니다.
- `javap -classpath leader-spring-boot/build/classes/kotlin/main 'io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties$Metrics'`
  - 결과: PASS, 생성자에는 `(boolean, Tags)`, `(boolean)` 및 no-arg가 포함됩니다.
- `git diff --check`
  - 결과: 통과.
- `jq empty leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json examples/prometheus-dashboard/provisioning/grafana/dashboards/leader-dashboard.json`
  - 결과: 통과.
- 금지된 어설션, 오래된 원시 잠금 문서 및 임시 동시성 도우미에 대한 패턴 스캔입니다.
  - 결과: 접촉된 범위에 대해 통과; 동시 적용 범위는 `MultithreadingTester`를 사용합니다.
- `xmllint --noout docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - 결과: 통과.
- `~/.local/bin/cairosvg docs/images/readme-diagrams/leader-micrometer-architecture-01.svg -o docs/images/readme-diagrams/leader-micrometer-architecture-01.png -s 2`
  - 결과: PASS, PNG가 3692x2240에서 렌더링되었습니다.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - 결과: 통과, `geometry_failures=0`.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - 결과: 통과, `files=1`.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-mixed-corner-audit.py docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - 결과: 통과, `paths=16`, `q_bends=0`, `failures=0`; 이 자산의 모든 커넥터는 직선입니다.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-connector-audit.py docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - 결과: 통과, `markers=5`, `connectors=16`, `cards=19`, `intrusions=0`, `crossings=0`.
- `view_image`를 사용한 전체 크기 PNG 검사
  - 결과: 통과, 눈에 띄는 텍스트 오버플로 없음, 커넥터/카드 침입, 라벨 겹침 또는 잘린 가드레일 텍스트.

## 잔여 위험

- 전체 저장소 테스트가 실행되지 않았습니다. 영향을 받는 Micrometer, Spring Boot 및 Prometheus 대시보드 모듈에 대한 검증이 이루어졌습니다.
- 종료 시간 Mongo/Lettuce 재연결 로그 노이즈는 영향을 받는 전체 모듈 테스트 출력에 나타났지만 Gradle는 349개의 테스트를 통과하여 성공적으로 완료되었습니다.
