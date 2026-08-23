# Issue #602 구현 검토 — 최근 획득 실패 관찰 window

## 범위

- Epic: #700 Spring 관찰성·운영 신호 확장
- Issue: #602 최근 획득 실패 health window
- 모듈: `leader-spring-boot`
- 대상: bounded aggregate recorder, readiness detail, `leaderElection` Actuator 응답, Spring 설정·자동 구성, 영문/국문 운영 문서

## 평결

P0/P1/P2/P3 발견 항목: 0.

구현은 기존 AOP recorder fan-out에 `BACKEND_ERROR`만 수집하는 JVM-local timestamp window를 추가하고, readiness와 read-only endpoint에 동일한 aggregate view를 제공합니다. 최근 실패는 readiness 상태를 변경하지 않으며, lock name과 exception message는 새 view에 보존하지 않습니다.

## 7층 관문

| Tier | 판정 | 근거 |
|---|---|---|
| Tier 1 Security | PASS | 새 view는 count/time/window/capacity/overflowed만 반환합니다. lock name·exception message는 저장하지 않으며, 문서는 Actuator 보호를 요구합니다. 기존 readiness와 endpoint의 raw lock-name 노출 경계는 변경하지 않았습니다. |
| Tier 2 Architecture | PASS | 변경은 `leader-spring-boot`에 한정되고 core 공개 API나 AOP 실행 계약을 수정하지 않습니다. `LeaderAcquisitionFailureWindowAutoConfiguration`은 AOP보다 앞에 등록되며, AOT cycle을 만든 불필요한 `after` 의존성은 제거했습니다. |
| Tier 3 Data/State | PASS | 기본 window는 `5m`, capacity는 `1024`입니다. `failureAt >= now-window` 경계를 포함하고 오래된 timestamp를 제거합니다. capacity 초과 시 `overflowed=true`를 유지해 count가 하한값임을 나타내며, 모두 만료되면 overflow를 초기화합니다. |
| Tier 4 Correctness | PASS | `BACKEND_ERROR`만 기록하고 `CONTENTION`·`FAIL_OPEN_FORCED`는 제외합니다. readiness의 `UP`·`OUT_OF_SERVICE`·`DOWN`·`UNKNOWN` 판정은 기존 lock 상태 계산으로만 결정됩니다. endpoint는 readiness와 동일한 view를 반환합니다. |
| Tier 5 Test/Verification | PASS | recorder 경계·capacity·동시성·clock 실패, readiness 상태 불변, endpoint 응답·legacy constructor/copy, property binding, HTTP path를 검증했습니다. focused 33 tests, AOP 회귀 49 tests, 전체 Spring Boot 모듈 473 tests가 AOT 포함 GREEN입니다. |
| Tier 6 Concurrency/Performance | PASS | recorder의 `ArrayDeque` 접근과 prune은 하나의 monitor로 보호되고 capacity는 고정되어 메모리가 무한히 증가하지 않습니다. health 평가 비용은 기존 JVM-local lock registry의 이름 수에 선형이며, 문서에서 동적 registry를 bounded하게 유지하도록 안내합니다. |
| Tier 7 Docs/Release | PASS | `README.md`/`README.ko.md`, Spring Boot manual, 관측·운영 guide를 양 언어로 갱신했습니다. Korean terminology audit, README language-switch 37개 그룹, manual 37 tests/392 assertions가 통과했습니다. |

## API/ABI 확인

- `LeaderObservabilityHealthProperties`의 기존 2-인자 생성자, 2-인자 `copy`, `copy$default` descriptor를 유지했습니다.
- `LeaderElectionReadinessHealthIndicator`의 기존 public 4-인자 생성자와 `LeaderElectionStatusEndpoint`의 기존 2-인자 생성자를 유지했습니다.
- `LeaderElectionStatusResponse`의 기존 1-인자·4-인자 생성자와 기존 copy 진입점을 유지하면서 새 aggregate field를 기본 empty view로 채웠습니다.
- compile 후 `javap`로 위 legacy constructor/copy descriptor와 새 5-인자 response descriptor를 확인했습니다. 내부 window constructor는 source에서 `internal`로 제한했습니다.

## 구현 편차와 잔여 위험

- 계획의 auto-configuration 파일명 대신 실제 구현은 `LeaderAcquisitionFailureWindowAutoConfiguration.kt`를 사용합니다. 기능·import 순서·계약에는 영향이 없습니다.
- readiness/endpoint auto-configuration bean은 `ObjectProvider`로 window를 선택적으로 받습니다. 새 내부 auto-configuration을 명시적으로 import하지 않는 기존 수동 `ApplicationContextRunner`도 계속 동작해야 했기 때문입니다.
- recorder의 관찰 신호는 best-effort입니다. clock 오류는 수집을 버리거나 health detail을 기본 empty view로 대체합니다. backend 상태 조회 실패와 기존 lock-name detail의 운영 의미는 별도 경계로 남아 있습니다.
- 이번 검증에는 repository 전체 build와 외부 CI가 포함되지 않았습니다. PR 생성 후 exact head 기준 CI를 별도 확인해야 합니다.

## 검증 증거

- `./gradlew :bluetape4k-leader-spring-boot:test --no-configuration-cache --no-build-cache --console=plain`: `SUCCESS: Executed 473 tests`, AOT processing 포함, `BUILD SUCCESSFUL`.
- `./gradlew :bluetape4k-leader-spring-boot:test` AOP 회귀 선택 세트: `SUCCESS: Executed 49 tests`.
- readiness clock fallback 추가 회귀: `SUCCESS: Executed 11 tests`.
- `./gradlew :bluetape4k-leader-spring-boot:detekt --no-configuration-cache --no-build-cache --console=plain`: `BUILD SUCCESSFUL`.
- `node .../audit-korean-terms.mjs ...`: 3개 파일, findings 0.
- `node scripts/check-readme-language-switches.mjs`: 37개 그룹, failures 0.
- `ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'`: 37 tests, 392 assertions, failures 0.
- `git diff --check`: 통과.
