# Issue #724 Prometheus scrape timeout plan

- 이슈: [#724](https://github.com/bluetape4k/bluetape4k-leader/issues/724)
- 분류: Type C bugfix (`bluetape-bugfix`)
- 기준 브랜치: `develop` (`05efcc841c936ac6a15ccf898a43928288d3f9b3`)
- 작업 브랜치: `fix/issue-724-prometheus-scrape-timeout`
- 대상: `examples/prometheus-dashboard`의 전체 저장소 실행 flaky scrape 계약
- milestone: `1.0.0` 유지 (현재 `1.0.0` release/tag가 아직 없어 post-release 이슈 근거 없음)

## 목표

전체 저장소 실행과 예제 모듈 단독 실행에서 `/actuator/prometheus` scrape 테스트가
기동 순서·공유 리소스·metric readiness 때문에 30초 timeout으로 red가 되지 않도록
원인을 고정하고, 실패 시 HTTP 상태/body 또는 누락 metric을 진단 가능하게 한다.

## 확인된 현재 증거

- Issue #724는 `OPEN`, assignee `debop`, labels `bug/ci/test/example`, milestone
  `1.0.0`이다.
- 이슈 생성 시점의 전체 테스트에서 `PrometheusScrapeTest`가 30초 안에 metric
  assertion을 수렴하지 못했지만, 같은 checkout의 모듈·클래스 재실행은 통과했다.
- 현재 테스트는 하나의 `untilAsserted` 안에서 raw HTTP body만 읽고, endpoint readiness,
  status, 누락 series를 별도 계약으로 고정하지 않는다.
- 현재 예제는 Spring scheduler와 Testcontainers Redis singleton을 함께 사용하며,
  #827 이후 backend probe scheduler/metric도 같은 scrape에 포함된다.

## 실행 계획

1. 현재 exact head에서 모듈 테스트와 전체 테스트를 직렬로 반복해 flaky 증상을 재현하고,
   JUnit XML/로그에서 최초 미충족 assertion·HTTP 상태·metric readiness를 보존한다.
2. `PrometheusScrapeTest`, `PrometheusDashboardApp`, probe/recorder와 Redis fixture의
   lifecycle 및 호출 순서를 추적해 단일 root-cause hypothesis를 세운다.
3. root cause만 겨냥하는 최소 RED 회귀 테스트를 먼저 추가한다. 테스트는 status/body 또는
   누락 metric 이름을 포함하고, readiness와 metric 초기화 순서를 명시적으로 검증한다.
4. 기존 Spring/Micrometer/Testcontainers 패턴을 재사용해 최소 production/test 설정을
   적용한다. Core API, dependency, unrelated CI 변경은 하지 않는다.
5. RED에서 GREEN으로 전환한 뒤 targeted test, 예제 전체, 관련 Micrometer/Redis 테스트,
   Detekt/compile, 직렬화된 전체 저장소 테스트와 `git diff --check`를 실행한다.
6. 재발 방지 규칙이 일반화 가능하면 한국어 lesson을 작성·GNO에 인덱싱하고, exact head
   리뷰/CI/PR DoD를 수렴한다.

## 수용 기준

- 전체 `./gradlew test --no-daemon --console=plain` 반복 실행에서 해당 timeout이 재현되지
  않거나 실패 원인이 deterministic하게 분리된다.
- `/actuator/prometheus` readiness와 `leader_aop_*`, history, connectivity metric의
  초기화 순서가 테스트 계약으로 고정된다.
- 실패 시 HTTP status/body 또는 누락 metric 이름이 assertion 메시지에 남는다.
- Redis/Testcontainers와 Spring context 공유 원인이면 fixture 격리 또는 명시적 cleanup이
  고정된다.
- 모듈 단독·전체 테스트 결과, static validation, `git diff --check`를 fresh evidence로
  남긴다.

## 범위 밖

- `leader-core`/Micrometer public API 변경
- unrelated flaky test 또는 CI infrastructure 수정
- `1.0.0` release/tag/publication
