# Issue #774 diagnostics 원인 신호와 readiness 정책 설계

> 상태: Issue #774 구현 전 승인된 설계 초안
>
> 대상: `bluetape4k-leader`, Issue #774
>
> 작성일: 2026-08-28

## 1. 문제와 목표

Issue #766은 `LeaderBackendDiagnosticsProbe`를 추가해 bounded provider probe의
timeout 검증, `checkedAt` 캡처, 일반 `Exception` 정규화, cancellation/interruption과
fatal `Error` 경계를 고정했다. 현재 결과에는 네 가지 상태만 있어 운영자는
`UNKNOWN`이 provider가 active probe를 지원하지 않아서 나온 것인지, client 상태를
읽었지만 연결을 증명하지 못해서 나온 것인지, probe 예외가 정규화된 것인지
구분할 수 없다. Spring health와 Ktor route도 이 상태를 서로 다른 transport
경계에서 소비하지만, readiness와 alert 해석 규칙은 문서로 고정되어 있지 않다.

이번 설계의 목표는 다음과 같다.

1. `UNKNOWN`을 포함한 diagnostics 결과에 민감정보 없는 bounded 원인 코드를
   추가한다.
2. `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED`의 direct/Ktor/Spring 의미를 하나의
   표로 고정한다.
3. active diagnostics 호출을 Micrometer 저카디널리티 counter로 기록하되,
   자동 polling이나 lock/backend I/O를 새로 만들지 않는다.
4. provider-native timeout이 호출 thread의 wall-clock deadline을 보장하지
   않는다는 사실과 운영 우회·rollback 조건을 EN/KO 문서와 runbook에 기록한다.
5. #766의 built-in/custom provider 및 direct/Ktor/Spring 경계를 운영자가
   해석할 수 있게 연결한다.

## 2. 현재 구현 근거와 범위 경계

| 근거 | 현재 확인한 사실 | 이번 설계에서의 의미 |
|---|---|---|
| `leader-core/.../diagnostics/LeaderBackendDiagnostics.kt` | `LeaderBackendConnectivity`는 `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED`를 표현하고 passive `diagnostics()`는 `NOT_CHECKED`를 반환한다. | 상태 이름과 passive 의미는 유지하고, 선택적 원인 코드만 추가한다. |
| `leader-core/.../diagnostics/LeaderBackendDiagnosticsProbe.kt` | timeout은 양수·유한해야 하며 clock은 callback보다 먼저 읽는다. 일반 `Exception`만 `UNKNOWN`으로 정규화한다. | `PROVIDER_EXCEPTION`을 같은 경계에서 생성하고 cancellation/interruption/`Error`는 계속 재전파한다. |
| `leader-spring-boot/.../LeaderBackendHealthIndicator.kt` | `UP -> UP`, `DOWN -> DOWN`, `UNKNOWN`/`NOT_CHECKED -> UNKNOWN`; provider 예외는 warning과 함께 `UNKNOWN`으로 처리한다. | health의 기존 fail-closed 매핑을 유지하고 bounded `reason` detail을 추가한다. |
| `leader-spring-boot/.../LeaderElectionReadinessHealthIndicator.kt` | readiness는 등록된 lock의 JVM-local state와 lease expiry를 읽고 `UP`/`OUT_OF_SERVICE`/`DOWN`/`UNKNOWN`을 결정한다. | backend connectivity health와 lock-state readiness를 하나의 상태로 합치지 않는다. |
| `leader-ktor/.../LeaderBackendDiagnosticsRoute.kt` | route는 기본적으로 passive JSON을 반환하고, active probe도 transport 성공 시 HTTP 200 payload로 반환한다. pipeline 예외의 status는 애플리케이션이 소유한다. | HTTP 200은 기준 상태 생성 성공을 뜻하고 payload의 `status`가 diagnostics 의미를 갖도록 문서화한다. |
| `leader-micrometer/.../InstrumentedLeaderElectors.kt` | instrumented elector가 delegate의 diagnostics provider를 전달하지만 active diagnostics 호출을 계측하지 않는다. | provider decorator가 호출 결과를 보존하면서 active 호출마다 counter를 한 번 기록한다. |
| `examples/prometheus-dashboard` | AOP/history/lease-risk 예제 alert와 lock-name redaction이 이미 존재한다. | 새 backend counter와 `UNKNOWN` 경보 기준을 기존 예제에 추가하되 lease-extension metric을 발명하지 않는다. |
| `docs/manual/manifest.yaml` | versioned manual은 `releaseRef: 0.5.0`, `releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977`에 고정되어 있다. | 새 API가 포함된 release train 전에는 manifest를 변경하지 않고 draft만 추가한다. |

이번 이슈는 #766의 helper API, provider migration, adapter regression, ABI
consumer compile, `jar tf`, `javap` 검증을 다시 설계하지 않는다. 새 reason field와
Micrometer adapter는 additive API이므로 해당 검증은 새 API가 실제로 포함되는
각 child PR에서 다시 실행한다. versioned manual의 release pin 갱신은 publish
train이 끝난 뒤의 별도 gate로 남긴다.

## 3. 대안과 선택

### 대안 A — 상태 해석을 문서로만 추가

기존 네 상태를 그대로 두고 `UNKNOWN`의 가능한 원인을 문서에서 설명한다.
변경량은 작지만 direct/Ktor/Spring 결과에서 원인을 기계적으로 집계할 수
없고, `PROVIDER_EXCEPTION`과 의도적인 unsupported 결과가 같은 series에
섞인다. Issue #774의 저카디널리티 원인 신호 목표를 충족하지 못하므로
채택하지 않는다.

### 대안 B — `LeaderBackendConnectivity`에 bounded reason을 추가하고 기존
Micrometer decorator에서 active 호출을 계측한다 (권장)

`LeaderBackendConnectivityReason` enum을 `LeaderBackendConnectivity`에
추가한다. reason은 `NOT_CHECKED`, `CONNECTED`, `DISCONNECTED`,
`PROVIDER_UNSUPPORTED`, `PROVIDER_EXCEPTION`, `CLIENT_STATE_UNCONFIRMED`로
제한한다. raw exception class/message, endpoint, credential, lock name은
저장하지 않는다. `LeaderBackendDiagnosticsProbe.check`는
`unknownReason` 선택 인자를 받아 provider가 의도적인 unsupported와
상태 미확정을 구분하게 하고, callback 예외에는 항상
`PROVIDER_EXCEPTION`을 사용한다.

Micrometer의 `InstrumentedLeaderElector`, `InstrumentedLeaderGroupElector`,
`InstrumentedSuspendLeaderElector`는 delegate provider를 private decorator로
감싼다. decorator는 active `checkConnectivity`와
`diagnostics(probe = true)` 호출을 각각 한 번 기록하며, passive
`diagnostics()`는 기록하지 않는다. 기존 결과와 예외를 그대로 반환·전파하므로
관측이 leader ownership이나 probe semantics를 바꾸지 않는다.

이 선택은 core에 framework dependency를 넣지 않고, 기존 decorator가 이미
보유한 `MeterRegistry`와 backend-name sanitizer를 재사용한다. JSON에는 bounded
`reason`을 추가하되 기존 field를 제거하지 않는다.

### 대안 C — core 전역 diagnostics observer registry 도입

lease-extension observer와 비슷한 bounded asynchronous event registry를 새로
도입하고 Micrometer/Spring이 이를 구독한다. 호출 경로를 모두 포괄할 수 있지만
전역 lifecycle, dispatcher, drop counter, registration ABI가 추가된다. 현재
Issue #774는 active diagnostics를 기존 decorator에서 계측하는 것으로 충분하며,
새 전역 dispatch가 probe latency와 운영 surface를 넓히므로 채택하지 않는다.

## 4. 권장 계약

### 4.1 Reason 모델

```kotlin
enum class LeaderBackendConnectivityReason {
    NOT_CHECKED,
    CONNECTED,
    DISCONNECTED,
    PROVIDER_UNSUPPORTED,
    PROVIDER_EXCEPTION,
    CLIENT_STATE_UNCONFIRMED,
}
```

`LeaderBackendConnectivity`는 기존 세 field 뒤에
`reason: LeaderBackendConnectivityReason`를 추가한다. 기본값은 status에
따라 결정해 기존 Kotlin 호출을 보존한다.

| status | 기본 reason | 의미 |
|---|---|---|
| `NOT_CHECKED` | `NOT_CHECKED` | probe를 요청하지 않은 passive 기준 상태다. 정상 상태를 증명하지 않는다. |
| `UP` | `CONNECTED` | provider가 연결 가능 상태를 확인했다. ownership이나 lease 획득을 증명하지 않는다. |
| `DOWN` | `DISCONNECTED` | provider가 client/backend 연결 불가 상태를 확인했다. |
| `UNKNOWN` | `CLIENT_STATE_UNCONFIRMED` | bounded read-only 검사만으로 연결을 확정하지 못했다. |
| `UNKNOWN` (callback `Exception`) | `PROVIDER_EXCEPTION` | provider callback의 일반 예외를 안전하게 정규화했다. 예외 원문은 저장하지 않는다. |

`LeaderBackendDiagnosticsProbe.check`는 다음 optional parameter를 제공한다.

```kotlin
public fun check(
    timeout: Duration,
    clock: Clock = Clock.systemUTC(),
    unknownReason: LeaderBackendConnectivityReason =
        LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED,
    probe: (Duration) -> LeaderBackendConnectivityStatus,
): LeaderBackendConnectivity
```

`unknownReason`은 `UNKNOWN` callback에만 적용하며 `NOT_CHECKED`일 수 없다.
일반 `Exception`은 항상 `PROVIDER_EXCEPTION`으로 덮어쓴다. 기존
`check(timeout, clock) { ... }` 호출은 default로 같은 동작을 얻는다.
`LeaderBackendDiagnosticsProvider` 기본 구현은
`unknownReason = PROVIDER_UNSUPPORTED`를 사용한다. built-in provider가
client 상태를 읽지만 증명을 제공하지 못하는 경우에는
`CLIENT_STATE_UNCONFIRMED`를 사용한다. OBS-01은 이 reason 계약을 기존
provider 경계에 연결하되, #766에서 이미 확정한 helper 도입·timeout·취소
전파 로직을 다시 마이그레이션하는 범위를 포함하지 않는다. 아직 legacy
수동 경계를 가진 provider는 이번 child에서 reason 기본값만 유지하고,
별도 provider migration 이슈로 추적한다.

### 4.2 Micrometer metric 계약

새 counter 이름은 `leader.backend.connectivity`로 고정한다. 한 번의 active
probe 호출은 성공·실패·정규화 결과와 관계없이 하나의 counter 증가를 만든다.

| tag | 허용 값 | cardinality/보호 규칙 |
|---|---|---|
| `backend.name` | descriptor의 backend ID | 기존 `LeaderMetricTagOptions.backendName` sanitizer를 사용한다. backend ID에 endpoint·credential·tenant를 넣지 않는다. |
| `status` | `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED` | enum 이름만 사용한다. passive 호출은 metric을 만들지 않는다. |
| `reason` | 위 enum의 6개 reason | enum 이름만 사용한다. exception class/message와 raw payload는 금지한다. |

tag 값은 Prometheus export에서 lowercase 또는 registry가 제공하는 이름
변환 결과를 따르며, 소스 계약에서는 enum vocabulary를 기준으로 한다. 기본
sampling은 active 호출 1회당 1회이며, background polling은 추가하지 않는다.
고빈도 readiness polling이 필요한 애플리케이션은 외부 scheduler에서 호출
주기를 제한해야 한다. decorator가 custom provider 예외를 관측할 때도
`PROVIDER_EXCEPTION`만 기록하고 원래 예외를 다시 던진다. `Error`는 metric
기록을 시도하지 않고 재전파하여 fatal 상태를 숨기지 않는다.

### 4.3 Adapter 상태 매핑

| source status | core/direct | Ktor diagnostics route | Spring backend health | readiness/alert 해석 |
|---|---|---|---|---|
| `UP` | checked connectivity | HTTP 200 JSON `status=UP` | `Status.UP` | backend connectivity는 정상으로 보되 ownership은 별도 확인한다. |
| `DOWN` | checked failure | HTTP 200 JSON `status=DOWN` | `Status.DOWN` | 지속되면 backend 장애 경보다. 작업 재실행이나 강제 정리는 fencing 확인 뒤에 한다. |
| `UNKNOWN` | 상태 미확정 또는 `PROVIDER_EXCEPTION` reason | HTTP 200 JSON `status=UNKNOWN` (pipeline 예외가 아닌 경우) | `Status.UNKNOWN`; provider 호출 예외는 기존 warning을 남긴다. | 자동으로 `UP`이나 `DOWN`으로 승격하지 않는다. reason별로 관찰하되 단독 page는 금지한다. |
| `NOT_CHECKED` | passive 기준 상태 | HTTP 200 JSON `status=NOT_CHECKED` | active backend health에서는 `Status.UNKNOWN`; 정적 endpoint는 passive 결과를 그대로 반환한다. | readiness 통과나 정상 증거로 사용하지 않는다. active probe를 별도로 요청한다. |

Ktor route의 HTTP 200은 diagnostics 기준 상태를 직렬화했다는 transport 결과다.
본문의 `status`가 backend 의미를 담으며, custom provider가 예외를 전파하면
`StatusPages` 등 application-owned pipeline이 HTTP status를 결정한다. library는
custom provider의 HTTP 500을 강제로 바꾸지 않는다.

`LeaderElectionReadinessHealthIndicator`는 lock state와 lease expiry를
검사하는 별도 signal이다. backend health의 `DOWN`/`UNKNOWN`을 자동으로
readiness에 합치지 않는다. 애플리케이션이 두 signal을 결합할 때는
`NOT_CHECKED`를 정상으로 취급하지 않고, `UNKNOWN`은 보수적으로 유지한다.

## 5. 운영 runbook과 실패 모드

### 5.1 Provider-native timeout과 wall-clock deadline

`timeout`은 provider callback에 전달하는 native budget이다. helper는 양수·유한
값을 검증하고 같은 값을 전달하지만, callback이 이를 무시하거나 backend client가
취소를 지원하지 않으면 호출 thread가 wall-clock deadline 안에 반환된다고
보장하지 않는다. 따라서 다음을 운영 계약으로 둔다.

1. `UNKNOWN + CLIENT_STATE_UNCONFIRMED`가 반복되면 provider client lifecycle과
   native timeout 설정을 먼저 확인한다.
2. `UNKNOWN + PROVIDER_EXCEPTION`이 증가하면 raw exception을 metric에 넣지
   말고 애플리케이션의 보호된 구조화 로그와 provider-native 진단을 확인한다.
3. 실제 wall-clock 상한이 필요하면 caller-owned executor/future timeout 또는
   backend client의 cancellation API를 애플리케이션이 별도 소유한다. library
   helper에 thread interrupt나 강제 `Future.cancel`을 추가하지 않는다.
4. probe가 요청 처리 경로를 지연시키면 active probe를 끄고 passive diagnostics와
   기존 readiness/state signal로 우회한다. 외부 route는 인증·network policy로
   보호한다.

### 5.2 Bypass와 rollback trigger

다음 중 하나면 active probe를 bypass한다.

- provider가 native timeout을 무시해 요청 latency budget을 초과한다.
- `PROVIDER_EXCEPTION`이 지속 증가하지만 backend ownership은 정상이고 probe가
  장애를 증폭한다.
- custom provider가 민감정보를 포함한 descriptor/reason을 반환해 sanitizer
  경계를 통과하지 못한다.

bypass 뒤에는 `backend-health.enabled=false` 또는
`backendConnectivityCheckEnabled=false`로 active 호출을 중지하고, Ktor/Spring
route 자체를 외부에 노출하지 않는다. metric series가 사라지는 것은 probe가
실행되지 않는다는 뜻이므로 `NOT_CHECKED`와 혼동하지 않는다. 다음 release에서
rollback할 때는 reason field와 counter consumer가 additive schema를 무시하는지
먼저 확인하고, helper/adapter 코드를 한 child PR 단위로 되돌린다. public field나
metric 이름을 즉시 삭제하지 않고 deprecation/마이그레이션 기간을 둔다.

### 5.3 주요 실패 모드

| 실패 모드 | 잘못된 해석 | 방어책 |
|---|---|---|
| passive `NOT_CHECKED`를 `UP`으로 해석 | probe를 끈 상태가 backend 정상으로 보인다. | Actuator/Ktor payload의 `status`와 active probe 여부를 함께 확인하고 readiness 근거로 사용하지 않는다. |
| provider-native timeout이 wall-clock을 보장한다고 가정 | request thread가 backend client에서 계속 대기한다. | native budget과 caller deadline을 분리하고, 지연 시 active probe를 bypass한다. |
| `UNKNOWN`을 즉시 `DOWN`으로 승격 | 일시적인 client 상태 미확정이 장애 page를 만든다. | `reason`별 counter 추세와 기존 AOP `BACKEND_ERROR`를 함께 보고 지속 조건을 둔다. |
| raw exception/endpoint/lock name을 tag·detail에 복사 | credential과 tenant 식별자가 observability backend에 남는다. | reason enum과 sanitized backend ID만 저장하고 원문은 보호된 로그 정책에 맡긴다. |
| custom override가 helper를 우회 | built-in과 custom 결과의 예외·reason semantics가 달라진다. | custom escape hatch를 문서에 명시하고 adapter 회귀 테스트에서 그대로 보존한다. |

## 6. Stacked PR train

모든 child는 직전 child의 merge commit을 base로 rebase하고 독립적으로
검증한다. PR merge는 squash가 아닌 rebase merge를 사용하며, 각 child의
정확한 head와 CI를 다시 읽은 뒤 별도 승인한다.

| 순서 | Child | 책임 | 독립 DoD |
|---:|---|---|---|
| 1 | `OBS-01` | `leader-core` reason enum/field, helper reason mapping, 기존 provider 경계의 reason 연결(단, #766 migration 재수행 제외), core/provider 회귀 | direct 결과의 reason 불변식, exception 경계, ABI/Kotlin consumer 검증 |
| 2 | `OBS-02` | `leader-micrometer` active diagnostics counter와 세 instrumented wrapper 연결, Spring health reason detail | passive 미계측, active 1회 계측, sanitized low-cardinality tags, Spring mapping 회귀 |
| 3 | `OBS-03` | Ktor JSON reason field와 HTTP/pipeline 경계 문서·테스트, adapter parity | built-in/custom direct/Ktor/Spring matrix, HTTP 200 payload와 pipeline exception 보존 |
| 4 | `OBS-04` | EN/KO root/module README, manual drafts, Prometheus example alert/runbook, release pin checklist | 문서 locale parity, PromQL/alert semantics, `releaseRef`/`releaseCommit` 미변경 증거 |

OBS-01과 OBS-02는 production API를 변경하므로 Type A common gates와
`checkBinaryCompatibility`, Kotlin consumer compile, `jar tf`, `javap`를
proportional하게 실행한다. OBS-03은 route payload additive change를 포함하고,
OBS-04는 문서/예제 변경만 수행한다. 1인 개발자 환경이므로 독립 리뷰 lane은
N/A이며, 각 child에서 7-Tier self-review, source-to-claim read-back, targeted
test와 exact-head CI로 대체한다.

## 7. 호환성·마이그레이션

- 기존 `LeaderBackendConnectivity` 생성 호출은 trailing default와 JVM
  compatibility constructor/copy overload로 유지한다. 새 `reason` field는
  additive이며, `checkBinaryCompatibility`에서 실제 descriptor를 확인한다.
- custom provider가 이전 factory를 사용하면 status에 맞는 기본 reason을
  얻는다. `diagnostics()` 또는 `checkConnectivity()`를 직접 override한 provider는
  기존 반환값·예외 정책을 계속 소유하며, Micrometer decorator는 이를 바꾸지
  않고 관측만 추가한다.
- Ktor JSON에 `reason` field가 추가되지만 기존 `descriptor`와
  `connectivity.status/checkedAt/latencyMillis`는 제거하지 않는다. strict JSON
  consumer는 additive field를 허용하도록 조정해야 한다.
- 새 dependency, 새로운 background thread, backend I/O, lock/lease mutation은
  도입하지 않는다. 기존 `bluetape4k-assertions`, `bluetape4k.support`,
  `bluetape4k.logging`, `MultithreadingTester` 패턴을 사용한다.
- versioned manual은 새 API가 release train에 포함되고 release commit이
  확정된 뒤에만 `manifest.yaml`을 갱신한다. 그 전에는 `docs/manual/drafts/`
  EN/KO 문서만 유지한다.

## 8. 수용 기준과 DoD

1. reason enum과 status/reason 불변식이 core에서 검증된다.
2. helper callback의 일반 `Exception`은 `PROVIDER_EXCEPTION`으로 정규화되고,
   cancellation/interruption/`Error`는 #766과 같은 인스턴스·interrupt 경계를
   유지한다.
3. helper-backed built-in provider와 기본 provider가 unsupported와 client
   state unconfirmed를 구분하며, legacy 수동 provider는 기존 status 계약과
   기본 reason을 유지한다. custom override는 source-compatible escape hatch로
   남고, #766 migration 재수행은 범위 밖으로 명시된다.
4. 세 `InstrumentedLeader*` wrapper의 active diagnostics가
   `leader.backend.connectivity`에 `backend.name/status/reason`만 사용해 한 번
   기록되고, passive 기준 상태는 기록하지 않는다.
5. Spring health detail과 Ktor JSON에 bounded reason이 포함되며 기존 status,
   warning, application-owned HTTP exception 경계를 보존한다.
6. EN/KO README와 manual draft가 상태 표, alert 기준, timeout/bypass/rollback
   runbook, #766 built-in/custom 해석을 같은 내용으로 설명한다.
7. Prometheus example은 `UNKNOWN`을 자동 page하지 않고
   `DOWN`/`PROVIDER_EXCEPTION` 지속 조건과 기존 AOP backend error를 연결한다.
8. 변경 코드와 테스트는 `$bluetape-kotlin-patterns` 및
   `bluetape4k-assertions` 규칙을 지키며 raw `assertThrows`를 추가하지 않는다.
9. 각 child에서 targeted test, module test, detekt, ABI/consumer/packaging
   검사, `git diff --check` 결과를 기록한다. CI와 merge는 exact head 기준으로
   확인한다.
10. release train 전에는 versioned `releaseRef`/`releaseCommit`을 바꾸지 않고,
    release 후 갱신할 별도 gate를 남긴다.

## 9. 설계 self-review와 Writer DoD

- 현재 source, Issue #766/#774, existing Spring/Ktor/Micrometer/example 계약을
  읽고 source ledger에 연결했다.
- 대안 세 가지와 선택 이유, status/reason/adapter 매핑, timeout·rollback,
  실패 모드 다섯 가지, compatibility, stacked order, acceptance/DoD를 포함했다.
- `UNKNOWN`을 단일 장애 상태로 단정하지 않았고, readiness와 backend health를
  서로 다른 signal로 유지했다.
- 공개 API additive change와 ABI/strict JSON 소비자 위험을 명시했다.
- EN/KO manual의 release pin을 현재 값으로 고정하고, 새 API 포함 release 전
  갱신하지 않는다는 범위를 분명히 했다.
- 한국어 technical register를 적용하고 identifier, command, URL, enum 이름을
  보존했다. 다음 단계에서 `audit-korean-terms.mjs`, `git diff --check`, rendered
  read-back으로 SPW-01~SPW-05와 KO-01~KO-07을 최종 확인한다.
- 1인 개발자 환경에 따라 독립 리뷰는 N/A이며, 구현 child마다 7-Tier
  self-review와 fresh exact-head verification을 필수로 한다.
