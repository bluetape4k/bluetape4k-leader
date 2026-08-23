# Issue #602 lesson — bounded acquisition failure window

## 결정

최근 backend 획득 실패를 운영자가 해석할 수 있도록 lock별 목록 대신 JVM-local bounded timestamp aggregate를 둡니다. `LeaderAopMetricsRecorder.onLockNotAcquired`에서 `SkipReason.BACKEND_ERROR`만 기록하고, `CONTENTION`과 `FAIL_OPEN_FORCED`는 정상 경쟁·명시적 fail-open 신호이므로 분리합니다.

기본 관찰 window는 `5m`, capacity는 `1024`입니다. 경계 시각은 포함하고 오래된 항목은 health/endpoint view를 읽을 때 제거합니다. capacity가 넘으면 `overflowed=true`를 표시해 count를 하한값으로 해석하게 하며, lock name과 exception message는 저장하지 않습니다.

## 다음 변경자에게 적용할 규칙

1. 새 운영 신호는 readiness 상태 결정과 분리합니다. 최근 실패는 detail과 `leaderElection` 응답에 표시할 수 있지만 `UP`, `OUT_OF_SERVICE`, `DOWN`, `UNKNOWN` 판정을 직접 바꾸지 않습니다.
2. bounded recorder는 하나의 동기화 경계와 고정 capacity를 사용합니다. 동적 lock-name registry를 backend 목록처럼 열거하거나 per-lock failure history로 확장하지 않습니다.
3. 관찰성 bean을 기존 수동 Spring context에 강제로 요구하지 않습니다. 내부 bean이 없는 legacy context를 위해 `ObjectProvider.getIfAvailable()` 같은 호환 경계를 사용합니다.
4. auto-configuration ordering을 바꿀 때 AOT cycle을 확인합니다. 이번 변경에서는 observability가 이미 AOP 뒤에 위치하므로 새 recorder에는 `before = [LeaderAopAutoConfiguration::class]`만 두고 역방향 `after`를 추가하지 않았습니다.
5. Kotlin data class에 public field를 추가할 때 legacy constructor/copy/`copy$default` descriptor를 유지하고 `javap`로 확인합니다. 생성자에 `@JvmSynthetic`을 붙일 수 없으므로 내부 constructor는 source-level `internal`과 별도 primary/marker 구조로 제한합니다.
6. best-effort 관찰 경로의 clock·recorder 오류는 사용자 작업이나 health 판정을 깨뜨리지 않아야 합니다. recorder 자체와 health detail fallback을 각각 테스트합니다.

## 검증 루프

RED 단계에서 새 recorder/property/readiness/endpoint 계약의 컴파일 실패를 먼저 확인하고, 각 단계 GREEN 후 AOT를 함께 실행했습니다. AOP backend 오류에서 throwing recorder가 healthy recorder와 원래 예외 결과를 방해하지 않는 fan-out 회귀도 추가했습니다. 최종적으로 focused set, AOP 회귀, 전체 `leader-spring-boot` 473 tests, detekt, README 언어 스위치, 한국어 용어 audit, manual tests를 모두 통과시켰습니다.

## 운영 해석

`recentAcquisitionFailures`는 현재 window에 남아 있는 보관 수이며 overflow 시 실제 실패 총량이 아닐 수 있습니다. `lastAcquisitionFailureAt == null`은 보관된 실패가 window 밖으로 만료되었음을 뜻합니다. 이 값만으로 장애를 단정하지 말고 backend 상태, lock 상태, 지연 시간, 기존 동적 lock-name cardinality 경고와 함께 확인합니다.
