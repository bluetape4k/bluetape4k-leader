# Issue #602 최근 획득 실패 health window 설계

## 상태와 범위

- Issue: #602 `feat(leader-spring-boot): 최근 획득 실패 health window 추가`
- Epic: #700 Spring Boot 정책 및 route guard 확장
- Train: `SPRING-S-01`
- 기준: `origin/develop` `c17eb99fe7611f50802819512013b0c58d624e4f`
- 후행: #603 `SPRING-S-02`

이 문서는 승인된 범위를 구현 계약으로 고정한다. 최근 획득 실패는
애플리케이션 전체를 합산하는 전역 window로 관찰한다. lock 이름별 실패 목록이나
backend lock 열거는 추가하지 않는다.

## 독자와 결정 질문

주 독자는 Spring Boot 운영자와 이 모듈의 유지보수자다. 운영자는 readiness
상태가 `DOWN` 또는 `OUT_OF_SERVICE`가 된 직접 원인과 별개로, 최근 backend 획득
실패가 있었는지 확인할 수 있어야 한다. 유지보수자는 이 신호가 선거 결과, 일반
contention의 `null` 반환, backend별 상태 조회 계약을 바꾸지 않는지 확인해야 한다.

이번 변경의 결정 질문은 다음과 같다.

1. 어떤 관찰을 획득 실패로 분류할 것인가?
2. 실패 기록을 얼마나 오래, 어떤 메모리 상한으로 보관할 것인가?
3. 기존 Spring health와 진단 표면에 어떤 최소 정보를 추가할 것인가?

## 현재 근거

현재 구현에서 확인한 계약은 다음과 같다.

| 근거 | 현재 계약 | 설계 영향 |
|---|---|---|
| `leader-spring-boot/.../LeaderElectionReadinessHealthIndicator.kt` | JVM에서 관찰한 lock만 읽고 `UP`/`OUT_OF_SERVICE`/`DOWN`을 계산한다. `Clock`을 주입할 수 있다. | 최근 실패는 같은 indicator의 detail에만 추가하고 기존 status 판정은 유지한다. |
| `leader-spring-boot/.../LeaderElectionStatusEndpoint.kt` | `leaderElection` Actuator endpoint가 선택된 상태 provider와 lock 상태를 반환한다. | 같은 전역 상태 복사본을 응답에 추가해 health와 진단의 해석을 맞춘다. 기존 생성자와 `copy(locks)` 호환 진입점은 보존한다. |
| `leader-core/.../metrics/LeaderAopMetricsRecorder.kt` | AOP hot path가 시도·획득·미획득·작업 실패를 recorder로 best-effort fan-out한다. 기본 구현은 no-op이다. | Spring recorder를 기존 pipeline에 추가하고 core API는 변경하지 않는다. |
| `leader-core/.../metrics/SkipReason.kt` | `CONTENTION`, `BACKEND_ERROR`, `FAIL_OPEN_FORCED`를 구분한다. | `BACKEND_ERROR`만 획득 실패로 기록하고 contention과 정책상 fail-open은 기록하지 않는다. |
| `leader-spring-boot/.../LeaderElectionAspect.kt`, `LeaderGroupElectionAspect.kt` | backend 예외 경로는 failure mode에 따라 `BACKEND_ERROR` 또는 `FAIL_OPEN_FORCED`를 recorder에 전달한다. | 기존 reason 의미와 기존 Micrometer 카운터를 보존한다. |
| `leader-spring-boot/.../LeaderMicrometerAutoConfiguration.kt` | Micrometer recorder는 AOP보다 먼저 등록된다. | 새 Spring recorder도 AOP bean 생성 전에 등록되도록 별도 auto-configuration 순서를 둔다. |

## 용어와 분류 기준

### 획득 실패

`LeaderAopMetricsRecorder.onLockNotAcquired(name, options,
SkipReason.BACKEND_ERROR)` 호출을 하나의 획득 실패로 정의한다. 동기, 비동기,
suspend, Reactor, `Flow`, group AOP가 이 기존 callback을 보낼 때 모두 같은
분류를 사용한다.

다음은 획득 실패가 아니다.

- `SkipReason.CONTENTION`: 다른 owner가 이미 lock을 보유한 정상 skip
- `SkipReason.FAIL_OPEN_FORCED`: backend 오류 뒤 fail-open 정책으로 작업을 계속한
  결과. 해당 호출이 backend 오류에서 비롯되었다는 사실을 이 issue에서 새로
  재분류하지 않아 기존 recorder 의미와 중복 집계를 피한다.
- action body 예외와 lease extension 오류: 획득 callback이 아닌 별도 failure
  관찰 대상이다.

이 기준은 관찰 전용이며 election decision, retry, failure mode, caller 예외를
변경하지 않는다.

## 선택지와 채택안

### A — Spring 전용 bounded recorder와 기존 AOP pipeline (채택)

`LeaderAcquisitionFailureWindow`를 `leader-spring-boot`에 추가하고
`LeaderAopMetricsRecorder`를 구현한다. 기존 AOP가 보낸 `BACKEND_ERROR`만 받아
전역 window에 timestamp를 저장한다. readiness indicator와 `leaderElection`
Actuator endpoint는 같은 bean의 불변 상태 복사본을 읽는다.

- 장점: 기존 callback과 `SkipReason`을 재사용하고 core API, backend module,
  Micrometer tag cardinality를 늘리지 않는다.
- 장점: lock 이름을 저장하지 않아 dynamic SpEL의 cardinality와 민감한 운영
  정보를 health/diagnostic으로 복제하지 않는다.
- 비용: Spring Boot auto-configuration 순서를 한 단계 추가하고, public
  response/health detail에 새 상태 복사본 필드를 추가해야 한다.

### B — 누적 Micrometer counter에서 최근 실패를 추론

기존 `leader.aop.lock.not.acquired{reason=BACKEND_ERROR}` counter를 읽어
최근 실패를 계산한다.

채택하지 않는다. 누적 counter만으로 window expiry와 last-failure timestamp를
복원할 수 없고, counter를 lock 이름별로 읽으면 기존 cardinality 문제가 health
표면으로 다시 전파된다.

### C — core에 새 failure event를 추가

`LeaderAopMetricsRecorder` 또는 `LeaderElectionListener`에 backend failure event를
추가하고 Spring/Ktor가 공통으로 소비한다.

채택하지 않는다. 현재 issue는 Spring health/diagnostic 표면에 한정되어 있고,
새 core callback을 모든 AOP branch와 backend-neutral listener에 확장하면 public
API와 호환성 범위가 불필요하게 커진다. 공통 이벤트가 필요한 후속 요구는 별도
issue에서 재평가한다.

## 제안 계약

### Window 저장소

새 internal recorder는 다음 의미를 갖는다.

```kotlin
internal class LeaderAcquisitionFailureWindow(
    window: Duration,
    clock: Clock = Clock.systemUTC(),
    capacity: Int = 1024,
) : LeaderAopMetricsRecorder {
    fun view(now: Instant = clock.instant()): LeaderAcquisitionFailureView
}

data class LeaderAcquisitionFailureView(
    val count: Int,
    val lastFailureAt: Instant?,
    val window: Duration,
    val capacity: Int,
    val overflowed: Boolean,
)
```

실제 public 노출 여부는 구현 단계에서 기존 패키지 경계를 따르되, health와
Actuator response가 공유하는 상태 복사본 필드는 위 의미를 고정한다.

- `window`는 양수이고 유한해야 한다. 기본값은 `5m`이다.
- `capacity`는 고정된 `1024`이며 Spring property로 무제한 확장할 수 없다.
- 저장소는 timestamp만 보관하고 lock name, backend name, exception message,
  leader identity를 보관하지 않는다.
- `onLockNotAcquired(..., BACKEND_ERROR)`가 호출되면 현재 `Clock`의 `Instant`를
  추가한다. callback은 항상 best-effort여야 하므로 저장소 내부 오류가 AOP
  결과나 caller 예외로 전파되지 않는다.
- 상태 복사본을 만들 때 `now - window`보다 오래된 timestamp를 제거한다. 경계는
  `failureAt >= now - window`를 최근 실패로 포함하고, 그보다 이전 항목은
  제거한다. 따라서 window 만료 경계가 deterministic test로 재현된다.
- 같은 timestamp를 포함한 concurrent 기록은 private lock으로 선형화한다.
  `view`는 짧은 임계구역에서 prune과 copy를 수행하고 외부에 mutable
  collection을 노출하지 않는다.
- capacity를 초과하면 가장 오래된 retained timestamp를 버리고 새 timestamp를
  보관한다. 이때 `overflowed=true`를 반환해 `count`가 window 내 전체 실패의
  하한일 수 있음을 명시한다. retained 항목이 모두 만료되면 overflow 표시는
  `false`로 되돌린다.

### Spring property

기존 `LeaderObservabilityHealthProperties`에 다음 값을 추가한다.

```yaml
bluetape4k:
  leader:
    observability:
      health:
        enabled: true
        acquisition-failure-window: 5m
```

기존 생성자와 Kotlin `copy` 진입점의 binary compatibility를 유지한다. 새 값은
양수·유한 `Duration`만 허용하고 잘못된 값은 설정 바인딩 시 즉시 거부한다.
`observability.enabled=false`이면 recorder와 상태 복사본 bean을 만들지 않는다.

### Auto-configuration 순서

`LeaderElectionAcquisitionFailureAutoConfiguration`을 추가하고 다음 조건을
적용한다.

- `bluetape4k.leader.observability.enabled=true` 또는 누락
- `LeaderAopMetricsRecorder` class 존재
- `LeaderAopAutoConfiguration`보다 먼저 실행

이 configuration은 `LeaderProperties`에서 window 설정을 읽어 recorder bean을
만든다. AOP aspect는 `ObjectProvider<LeaderAopMetricsRecorder>`를 생성 시점에
수집하므로 새 configuration을 `AutoConfiguration.imports`와 annotation에서
AOP보다 앞에 둔다. readiness와 Actuator configuration은 이 bean을 선택적으로
주입하고, 기존 조건과 endpoint enablement를 그대로 유지한다.

### Health detail

`LeaderElectionReadinessHealthIndicator`의 기존 detail에 다음 값을 추가한다.

| detail | 의미 |
|---|---|
| `recentAcquisitionFailures` | 현재 window에 retained된 `BACKEND_ERROR` 획득 실패 수 |
| `lastAcquisitionFailureAt` | 현재 window에 남아 있는 가장 최근 실패 시각. 없으면 `null` |
| `acquisitionFailureWindow` | window의 ISO-8601 `Duration` 표현 |
| `acquisitionFailureWindowCapacity` | 고정 retention 상한 `1024` |
| `acquisitionFailureWindowOverflowed` | window가 capacity를 초과해 count가 하한일 수 있는지 여부 |

이 값들은 `stateSupported=false`, state read failure, expiring lease 여부와
독립적으로 best-effort로 계산한다. 최근 획득 실패만으로 health `Status`를
`DOWN`이나 `OUT_OF_SERVICE`로 바꾸지 않는다. 기존 status는 state read와 lease
warning 계약을 그대로 따른다.

### Diagnostic response

기존 `leaderElection` Actuator response에 다음 immutable field를 추가한다.

```kotlin
val acquisitionFailures: LeaderAcquisitionFailureView
```

`LeaderElectionStatusResponse`의 기존 단일 인자 생성자와 `copy(locks)` 진입점은
보존하고, 새 field의 기본 상태 복사본은 빈 window다. endpoint는 readiness와 같은
window bean을 읽으며 backend lock을 열거하거나 상태 조회를 새로 수행하지 않는다.
기존 `locks`, `backend`, `stateProviderBean`, `stateSupported`의 이름과 의미는
변경하지 않는다.

## 실패 및 동시성 계약

- window recorder의 관찰 실패는 `LeaderElectionAspect`의 기존 recorder 격리
  경계를 통해 warning으로만 남고 선거 실행을 중단하지 않는다.
- 정상 contention은 count와 timestamp를 증가시키지 않는다.
- empty window는 `count=0`, `lastFailureAt=null`, `overflowed=false`다.
- 오래된 실패만 남은 경우 상태 복사본 조회가 이를 제거하고 empty window를 반환한다.
- capacity overflow 뒤에도 저장소 메모리 사용량은 `O(capacity)`를 넘지 않는다.
- clock이 뒤로 이동한 경우 timestamp는 삭제하지 않고 현재 시각 기준으로 다시
  평가한다. 운영 clock 조정은 관찰값의 의미를 바꿀 수 있지만 election 계약은
  영향을 받지 않는다.
- health/endpoint 조회 중 recorder가 기록해도 상태 복사본은 내부 일관성을 가진
  한 시점의 복사본이며, 다음 조회에서 최신 상태를 반영한다.

## 호환성과 범위 경계

포함한다.

- `leader-spring-boot`의 bounded failure recorder, properties, health detail,
  `leaderElection` diagnostic response, auto-configuration, unit/context tests
- Spring Boot 운영 문서의 신호 해석과 설정 예시(English/Korean pair)
- `docs/lessons/`의 구현 교훈과 `docs/superpowers/plans/`의 실행 계획

포함하지 않는다.

- core `LeaderElectionListener` 또는 `LeaderAopMetricsRecorder` public API 변경
- Micrometer metric 이름, tag, 누적 counter semantics 변경
- backend lock 목록 조회, lock 이름별 failure history, persistent storage,
  background cleanup thread, retry, alert rule, election decision 변경
- Ktor 또는 다른 framework의 진단 surface
- readiness status를 최근 실패만으로 강등하는 정책

## 검증 설계

1. `LeaderAcquisitionFailureWindow` 단위 테스트
   - `BACKEND_ERROR` 기록, `CONTENTION` 무시, duplicate/concurrent record
   - `now - window` 경계 포함과 만료
   - capacity 상한, overflow 표시, retained 항목 만료 후 reset
   - fixed/mutable `Clock`을 사용한 deterministic 결과
2. `LeaderElectionReadinessHealthIndicatorTest`
   - backend failure가 detail에 나타나고 기존 status는 유지
   - normal contention은 detail을 증가시키지 않음
   - state read failure와 recent acquisition failure가 동시에 있어도 raw
     exception이 노출되지 않음
3. `LeaderElectionStatusEndpointTest` 및 response compatibility 테스트
   - 새 상태 복사본 shape와 empty default
   - 기존 lock/backend/provider fields와 생성자/copy semantics 보존
4. `LeaderElectionObservabilityAutoConfigurationTest`
   - property 기본값, invalid duration, observability disabled, AOP bean 생성
     전 recorder registration, readiness/endpoint 조건
5. 문서/정적 검증
   - English/Korean Spring manual의 설정·해석 문장 일치
   - `git diff --check`, writer terminology audit, module test, detekt

## 승인 조건과 DoD

| ID | 완료 조건 | 증거 |
|---|---|---|
| S-01 | 분류 기준이 `BACKEND_ERROR`와 contention을 구분한다. | recorder 및 분류 단위 테스트 |
| S-02 | window expiry와 boundary가 deterministic하다. | fixed/mutable clock 테스트 |
| S-03 | memory와 lock-name cardinality가 bounded다. | capacity/overflow 테스트와 detail에 이름 미포함 확인 |
| S-04 | health와 diagnostic이 같은 상태 복사본 의미를 사용한다. | indicator/endpoint shape 테스트 |
| S-05 | 기존 election decision, status mapping, public compatibility가 유지된다. | 회귀 테스트와 기존 module test |
| S-06 | 관찰 오류가 best-effort로 격리된다. | recorder failure-path 테스트 및 AOP 회귀 검증 |
| S-07 | 운영 문서가 signal의 하한/overflow와 non-decision 성격을 설명한다. | English/Korean 문서 read-back |

## Writer gate

- [x] **SPW-01 — 독자·목적·근거 고정**: Spring Boot 운영자/유지보수자를
  독자로 정하고 Issue #602, `origin/develop` SHA, 기존 recorder/health/endpoint/
  property source를 확인했다. 미확정 사항은 구현 단계의 실제 constructor와
  response serialization 검증으로 남긴다.
- [x] **SPW-02 — 설계 계약 충족**: 문제, 선택지, 채택안, 분류 기준, 저장·실패·
  호환성 경계, 검증 설계와 DoD를 이 문서에 기록했다.
- [x] **SPW-03 — 한국어 기술 문체**: 코드 토큰, 설정 키, 상태 이름, 숫자와
  경계식을 보존하고 `window`, `cardinality`, `best-effort`의 의미를 문맥에 맞게
  고정했다. `korean-naturalness-checklist.md` 기준으로 문장을 읽어 보았다.
- [x] **SPW-04 — 기술 의미 추적성**: 현재 source ledger와 구현 선택을 대조하고,
  기존 status/endpoint/recorder semantics를 변경하지 않는다는 경계를 명시했다.
- [x] **SPW-05 — read-back**: 최종 Markdown을 다시 읽어 heading, 표, code fence,
  acceptance mapping을 확인했다. 이 문서의 검토와 사용자 승인이 끝나기 전에는
  세부 구현 계획과 코드 변경을 진행하지 않는다.

## 미해결 및 사용자 검토 항목

- `LeaderAcquisitionFailureView`의 최종 JVM visibility와 JSON serialization
  annotation은 구현 시 현재 Spring Boot/Jackson 관례에 맞춰 확정한다.
- public constructor/copy compatibility 검증은 구현 후 `javap`와 module test로
  증명한다.
- 본 문서는 사용자 검토와 승인을 기다리는 상태다.
