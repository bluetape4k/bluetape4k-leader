---
title: "Lease-extension 관찰"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Lease-extension 관찰

> Issue #559(OBS-02)를 위한 미배포 초안입니다. observer hook과 adapter는
> 고정한 `0.5.0` 매뉴얼보다 새 API입니다. release manifest와 release commit을
> 갱신하기 전에는 이 초안을 해당 배포본의 기능으로 안내하지 않습니다.

## 추가되는 기능

`leader-core`는 명시적인 `LockExtender` 호출과
`LeaderLeaseAutoExtender` watchdog tick에 대해 하나의 framework-neutral
terminal event를 노출합니다. 이 event는 진단 전용입니다. ownership을
획득하거나 deadline을 변경하지 않으며 watchdog의 retry/stop 여부를 결정하지도
않습니다. Core registry는 process-local이므로 프로세스 간 집계가 필요하면
애플리케이션의 기존 metrics 또는 tracing 시스템에 adapter를 연결해야 합니다.

Delivery 경로의 in-flight admission만 bounded입니다. Publication은 permit을
기다리지 않고 non-blocking으로 시도하므로 느리거나 포화된 observer를 기다리지
않습니다. 현재 in-flight 한도는 전체 1,024개, registration별 256개입니다.
이 registry의 registration 수와 callback fan-out에는 고정 상한이 없으므로
애플리케이션 등록 수를 작게 유지하고 callback을 짧게 작성하세요. Delivery가
거부되면 `LeaderLeaseExtensionObservers.droppedCount()`가 증가하고 core가
재시도하지 않습니다.

## 사전 조건과 source 계약

- 프로젝트 toolchain이 요구하는 JDK 25 이상.
- Core hook을 사용하기 위한 `io.github.bluetape4k.leader:bluetape4k-leader-core`.
- Micrometer adapter를 사용하기 위한 `io.github.bluetape4k.leader:bluetape4k-leader-micrometer`.
- Spring Boot lifecycle 관리가 필요할 때만
  `io.github.bluetape4k.leader:bluetape4k-leader-spring-boot`.

구현 source는 [`LockExtender`](https://github.com/bluetape4k/bluetape4k-leader/blob/develop/leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt),
[`LeaderLeaseAutoExtender`](https://github.com/bluetape4k/bluetape4k-leader/blob/develop/leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt),
[`ExtendOutcome`](https://github.com/bluetape4k/bluetape4k-leader/blob/develop/leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt),
[`LeaderLeaseExtensionObserver`](https://github.com/bluetape4k/bluetape4k-leader/blob/develop/leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserver.kt)입니다.
이 초안은 현재 `develop` 구현을 설명하며 고정한 매뉴얼은 변경하지 않습니다.

## Core 최소 등록

진단 stream이 필요한 동안만 observer를 등록하세요. 명시적인 extension 호출은
`@LeaderElection`, `@LeaderGroupElection` 또는 직접 elector body가 만든 일치하는
user-owned active scope 안에서 실행해야 합니다. Scope 밖 호출이나 named lock이
활성 scope와 일치하지 않는 호출은 `NotHeld`를 반환하고 `context = null`인 event를
publish합니다. Fail-open `NotHeld` event에는 `context`의 lock name이 남고
`auditLeaderId = null`입니다. `WATCHDOG` event는 단일 리더의 `autoExtend = true`
경로에서만 발생하며 group election slot은 group auto-extension을 끄므로 watchdog를
만들지 않습니다. 아래 코드는 blocking 예제입니다. Suspend active scope에서는 같은
registration을 유지하되 suspend 함수 안에서 `LockExtender.extendActiveLockDetailedSuspend(60.seconds)`를
호출하세요. 소유 component가 종료될 때는 반환된 handle을 반드시 닫습니다.

```kotlin
val registration = LeaderLeaseExtensionObservers.addObserver { event ->
    logger.info {
        "lease extension source=${event.source} execution=${event.execution} " +
            "outcome=${event.outcome::class.simpleName}"
    }
}

try {
    LockExtender.extendActiveLockDetailed(60.seconds)
} finally {
    registration.close()
}
```

`addObserver`는 idempotent한 `AutoCloseable`을 반환합니다. `close()`는 해당
registration만 제거합니다. 이미 admission을 통과한 callback은 close 뒤에도
끝날 수 있으며 registry는 callback 순서나 drain barrier를 보장하지 않습니다.
Callback의 `Exception`은 격리되고 warning interval마다 최대 한 번 기록되며
extension 결과를 바꾸지 않습니다. Core는 extension delegate의
`CancellationException`이나 `Error`를 잡아 outcome으로 평탄화하지 않으며 해당
경로에서는 event를 publish하지 않고 그대로 다시 던집니다.

위의 짧은 예제는 하나의 명시적 `USER` 시도만 관찰합니다. `WATCHDOG` tick이 필요하면
단일 리더의 `autoExtend = true` action 또는 component 전체 수명 동안 registration을
유지하고 종료 시 닫으세요. Group election slot은 명시적 `LockExtender` 호출은
지원하지만 group auto-extension을 사용하지 않아 `WATCHDOG` event를 만들지 않습니다.

## Event 계약

각 event는 publication까지 도달한 하나의 terminal extension 시도를 나타냅니다.
Event는 immutable하며 다음 필드를 가집니다.

| 필드 | 값과 의미 |
|---|---|
| `source` | `LockExtender`는 `USER`, `LeaderLeaseAutoExtender`는 `WATCHDOG`. |
| `execution` | blocking delegate는 `BLOCKING`, suspend delegate는 `SUSPEND`. |
| `outcome` | 아래에 설명하는 기존 `ExtendOutcome` 값 중 하나. |
| `elapsedNanos` | 0 이상인 caller 측 delegate 호출 시간. scope 밖 lookup이나 즉시 queue admission 거부처럼 호출이 delegate 실행 없이 끝난 경우에는 `0`이며, timeout된 user queue 명령은 이후 실행될 수 있어 0보다 클 수 있습니다. |
| `context` | 일치하는 user 소유 active scope에서 선택적으로 전달되는 `LeaderLeaseExtensionContext`; watchdog, scope 밖 호출, 이름 불일치에서는 `null`. Fail-open `NotHeld` event에는 lock name과 `auditLeaderId = null`이 남습니다. |

`LeaderLeaseExtensionContext`는 in-process adapter를 위해 `lockName`과 선택적인
`auditLeaderId`를 담습니다. `toString()`은 redaction됩니다. 두 값은 민감한
정보로 취급하고 명시적인 sanitisation 정책 없이 로그나 export에 포함하지 마세요.

## Outcome 의미

- `Extended(observedExpireAt)`는 backend가 lease를 연장하고 관측한 만료 시각을
  반환했다는 뜻입니다. User 경로는 publish 전에 deadline을 기록하며 watchdog는
  기존 retry/stop 처리를 유지합니다.
- `Rejected`는 watchdog admission reservation 실패, bounded operation queue 포화, 또는
  명령이 완료되기 전에 user queue 작업이 timeout된 경우를 뜻합니다. Timeout된 queue
  명령은 이후 실행될 수 있으므로 backend 작업이 전혀 없었다거나 ownership을 잃었다는
  뜻이 아닙니다. 이는 skip 신호이며 registry in-flight admission에서 observer delivery가
  거부된 횟수를 세는 `droppedCount()`와는 별도 신호입니다.
- `NotHeld`는 현재 scope에 사용할 수 있는 ownership이 없다는 뜻입니다. 만료,
  takeover, mismatch, fail-open scope, 활성 scope 밖 호출을 포함합니다.
- `WrongThread`는 thread-bound backend를 잘못된 thread에서 호출했다는 뜻입니다.
- `BackendError(cause)`는 backend `Exception`을 보존합니다. Extension delegate가
  `Exception`을 던진 경우 blocking 또는 suspend user 호출은 event를 publish한 뒤
  그 예외를 다시 던집니다. Delegate가 이미 `ExtendOutcome.BackendError` 값을
  반환한 경우에는 해당 outcome을 publish하고 반환합니다. Watchdog는 publish 후
  기존 classifier와 retry/stop 정책을 적용합니다. Core는 `cause`를 redaction하지
  않으므로 custom observer가 로그나 export 전에 별도로 sanitise해야 합니다.

정상 lock contention은 예외가 아니라 result/skip 상태로 남습니다. Callback이
관찰 결과를 control-plane 결정으로 바꾸어서는 안 됩니다.

## Blocking·suspend·watchdog 동등성

Blocking 메서드 `extendActiveLockDetailed(...)`와 suspend 메서드
`extendActiveLockDetailedSuspend(...)`는 같은 event shape을 publish합니다.
Watchdog는 delegate에 따라 `BLOCKING` 또는 `SUSPEND`를 사용하며 `WATCHDOG`
event에는 user ownership context가 없습니다. 두 경로 모두 기존 atomic ownership
검사, user deadline 보호, cancellation 전파, backend error 분류를 유지합니다.
Delegate가 던진 `CancellationException`이나 `Error`는 event를 publish하지 않고
그대로 다시 던집니다. Watchdog delegate의 `RejectedExecutionException`은
`BackendError`로 publish한 뒤 설정한 classifier와 무관하게 watchdog를 중지합니다.

Observer가 없으면 core는 event/context allocation을 건너뛰고 extension 경로를
기존과 동일하게 실행합니다. 따라서 observer 등록은 진단 delivery 작업만
추가하며 lease extension을 observer와 동기화하거나 fail-open 동작을 바꾸지
않습니다.

## Micrometer adapter

`MicrometerObservationLeaderLeaseExtensionObserver`는 각 core event를
`bluetape4k.leader.lease.extension`이라는 짧은 terminal Observation 하나로
변환합니다.

```kotlin
val observer = MicrometerObservationLeaderLeaseExtensionObserver(
    registry = observationRegistry,
    options = LeaderObservationOptions(),
)
val registration = LeaderLeaseExtensionObservers.addObserver(observer)

try {
    LockExtender.extendActiveLockDetailed(60.seconds)
} finally {
    registration.close()
}
```

이 Micrometer snippet은 blocking 상세 API를 사용합니다. Suspend active scope에서는
registration을 유지하되 suspend 함수 안에서 `extendActiveLockDetailedSuspend(60.seconds)`를
호출하세요.

bounded low-cardinality tag는 `source`, `execution`, `outcome`, `result`입니다.

| `ExtendOutcome` | `outcome` | `result` |
|---|---|---|
| `Extended` | `extended` | `success` |
| `Rejected` | `rejected` | `skipped` |
| `NotHeld` | `not_held` | `skipped` |
| `WrongThread` | `wrong_thread` | `error` |
| `BackendError` | `backend_error` | `error` |

`elapsedNanos`는 tag가 아니며 Observation 자체가 timing boundary로 남습니다.
`includeLockName`과 `includeLeaderId`는 기본값이 꺼져 있습니다. 켜더라도
설정한 `LeaderMetricTagOptions` sanitiser를 거친 뒤에만 high-cardinality 필드로
추가합니다. `includeExceptionDetails`도 opt-in이며 tag sanitiser를 거치지 않은
원본 backend 예외를 `Observation.error(...)`로 연결합니다. 기본값 `false`를
유지하고 downstream observation 또는 tracing backend가 raw exception message와
stack trace를 받아도 되는 경우에만 켜세요. `NOOP ObservationRegistry`에서는
Observation을 만들지 않습니다. 이 모듈은 OpenTelemetry SDK, tracing bridge,
exporter, collector를 추가하지 않습니다.

## Spring Boot lifecycle

`leader-micrometer`, non-NOOP `ObservationRegistry`, 기본값인
`bluetape4k.leader.observability.tracing.enabled=true`가 있으면
`LeaderObservationAutoConfiguration`이 ObservationRegistry post-processing 뒤에
core registration을 얻습니다. Top-level observability switch나 tracing property로
registration을 끌 수 있으며 NOOP registry는 건너뜁니다.

`LeaseExtensionObservationRegistrationManager`는 `ObservationRegistry` identity마다
Micrometer observer 하나를 공유합니다. 각 application context는 idempotent handle을
하나씩 받고 마지막 handle이 닫힐 때만 core registration을 제거합니다. 같은 registry에
서로 다른 `LeaderObservationOptions`를 요청하면 callback을 중복 등록하거나
redaction을 조용히 약화하지 않고 즉시 실패합니다.

Spring integration은 `@EnableAspectJAutoProxy`, 새 extension API, exporter를 추가하지
않습니다. Core observer의 lifecycle과 option wiring만 소유합니다.

## Privacy·종료·진단

기본 low-cardinality tag에는 `lockName`, `auditLeaderId`, backend exception detail을
넣지 마세요. 상관관계가 필요하면 작고 정적인 lock set 또는 명시적인
`HASH`/`TRUNCATE` 정책을 사용하고 data owner를 문서화하세요. tenant, user, URL,
credential처럼 동적인 값을 raw로 사용하지 않습니다.

소유한 ObservationRegistry나 lifecycle component를 버리기 전에 application-owned
registration을 닫으세요. Close는 이미 admission된 callback을 기다리지 않으므로
observer는 짧은 late-delivery window를 안전하게 처리해야 합니다.
`droppedCount()`는 process-local 누적 counter이므로 incident나 observation window
시작 시 baseline을 기록하고 delta를 확인하세요. Delta가 증가하면 extension
`Rejected`가 아니라 observer delivery 포화이므로 callback 작업량이나
registration/fan-out을 줄이고 필요하면 application-side sampling 또는 aggregation을
사용하세요. Prometheus dashboard example은 이 counter를 노출하지 않습니다. Core
admission 한도는 고정되어 있으므로 lease operation 자체에 대기 시간을 추가하지 않습니다.

Core는 `BackendError.cause` 원본 예외를 redaction하지 않습니다. Custom observer가
로그나 export 전에 민감한 message와 stack trace를 별도로 sanitise해야 합니다.

Observation은 core가 terminal 결과를 publish하려 했다는 증거일 뿐 remote exporter가
수락하거나 저장했다는 증거가 아닙니다. Core hook에는 durable queue, 프로세스 간
deduplication, replay 계약이 없습니다.

## Promotion gate

`docs/manual/manifest.yaml`이 `0.5.0`과
`721a9a3808f67489d2bdb8177734325981c24977`을 가리키는 동안 이 파일은 미배포
초안으로 남습니다. Promotion에는 새 release commit, EN/KO manual 동기화,
module inventory 재생성, core·Micrometer·Spring targeted test가 필요합니다.
README link는 이 초안을 가리킬 수 있지만 release gate를 통과하기 전에는 고정한
manual이 새 API를 주장해서는 안 됩니다.

## 다음 학습 단계

각 모듈 README 쌍에서 짧은 조합 예제를 확인한 뒤 lifecycle과 bounded-admission
test를 읽고 production에서 high-cardinality 진단을 켜기 전에 데이터 정책을
검토하세요.
