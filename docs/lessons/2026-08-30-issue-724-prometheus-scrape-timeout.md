# Issue #724: Prometheus scrape readiness 안정화

## 결정

Actuator scrape 자체는 Spring context가 준비된 뒤 응답할 수 있지만,
`@Scheduled` job과 backend probe의 첫 실행은 전체 저장소 부하와 scheduler 시작
순서에 따라 늦어질 수 있다. 이를 scrape 테스트의 readiness로 추정하면
`untilAsserted` 안의 여러 assertion이 30초 뒤 모호한 boolean timeout으로
실패한다.

테스트에서는 scheduler/probe delay를 충분히 길게 설정하고, AOP 대상 job과
connectivity probe를 명시적으로 호출한다. 따라서 검증 대상인 AOP 계측·metric
export는 유지하면서 scheduler 초기 실행 순서를 테스트 결과에서 분리한다.
HTTP response는 status와 body를 보존하고, metric readiness helper는 누락된
series 이름과 scrape body를 assertion 메시지에 포함한다. Awaitility에는 alias와
bounded poll interval을 지정해 남은 endpoint readiness도 관찰 가능하게 했다.

## 검증 계약

- RED: status/body와 누락 metric 이름을 버리던 helper 계약 테스트가 2건 실패했다.
- 최종 GREEN: helper 계약 테스트 2/2와 `PrometheusScrapeTest` 1/1을 같은
  실행에서 3/3으로 통과했고, strict label matcher 보정 후 module/full proof를
  다시 수렴했다.
- GREEN: `prometheus-dashboard` 모듈 15/15, `leader-micrometer` 132/132,
  `leader-redis-lettuce` 318/318, 전체 Detekt가 통과했다.
- GREEN: exact head에서 전체 저장소 테스트가 451 suites, 4,173 tests,
  `skipped=0`, failures/errors 0으로 통과했다.
- 참고: 첫 전체 실행에서는 범위 밖 `BoundedLeaderAuditExporterTest`가
  `ConcurrentHashMap` iterator `NoSuchElementException`으로 1회 실패했으나,
  해당 클래스 단독 재실행 21/21과 전체 재실행은 통과했다. #724 변경과는
  무관한 기존 flaky 증거로 분리했다.
- 참고: label closing quote를 엄격하게 복원한 중간 실행에서 matcher가 실제
  scrape 형식과 어긋나 1/3이 실패했다. 실패 body의
  `lock_name="redacted-lock"` 형식을 확인해 matcher를 교정한 뒤 targeted
  3/3과 최종 full run을 다시 실행했다.

## 향후 지침

1. Spring scheduler가 metric producer인 scrape 테스트는 첫 scheduled callback을
   readiness 신호로 암묵적으로 사용하지 않는다.
2. 검증 대상 callback을 직접 호출하거나 명시적인 준비 신호를 노출하고,
   scheduler 자체의 cadence는 별도 테스트에서 검증한다.
3. HTTP status, 응답 body, 누락 metric 이름을 bounded assertion 진단에 남기며,
   Kotlin 테스트에서는 `(<condition>).shouldBeTrue()` 대신 의미 있는 matcher,
   계약 helper 또는 명시적 assertion을 사용한다.
