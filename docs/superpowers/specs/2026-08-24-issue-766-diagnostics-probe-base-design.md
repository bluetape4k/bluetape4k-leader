# `leader-core` backend diagnostics probe base contract 설계

> 상태: 승인된 설계
>
> 대상: Issue #766
>
> 작성일: 2026-08-24

## 1. 문제와 목표

`LeaderBackendDiagnosticsProvider`는 이미 정적 capability와 선택적인
connectivity 결과를 분리하지만, bounded probe의 공통 동작은 provider마다
반복된다. 현재 코드에는 다음 중복이 남아 있다.

- `Duration`의 양수·유한성 검증이 core, MongoDB, Lettuce, Redisson,
  Hazelcast, ZooKeeper에 흩어져 있다.
- probe 시각을 provider가 직접 만들고, `Exception`을 `UNKNOWN`으로
  정규화하는 경계도 provider에 따라 다시 작성된다.
- Issue #738 / PR #765에서 Hazelcast와 ZooKeeper의 `Error` 재전파는
  보정했지만, 같은 예외 경계를 새 provider가 일관되게 따를 공통 base가
  없다.

목표는 Issue #559처럼 framework-neutral한 `leader-core` base 기능을 먼저
추가하는 것이다. 새 base는 probe의 안전한 실행 경계를 단일화하고, 기존
provider는 backend별 상태 판정만 담당하게 한다.

## 2. 근거와 현재 계약

다음 자료를 기준으로 설계했다.

| 근거 | 확인한 사실 |
|---|---|
| [`leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt`](../../../leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt) | `LeaderBackendConnectivity`는 `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED` 불변식을 가진다. 기본 provider는 bounded 검사 미지원 시 `UNKNOWN`을 반환한다. |
| [Issue #533](https://github.com/bluetape4k/bluetape4k-leader/issues/533) | diagnostics는 framework-neutral하고, 읽기 전용·bounded·비파괴 경계를 지켜야 한다. |
| [Issue #559](https://github.com/bluetape4k/bluetape4k-leader/issues/559) 및 `docs/superpowers/specs/2026-08-17-issue-559-lease-extension-observation-design.md` | provider별 wiring보다 core 계약을 먼저 고정하고 후속 integration을 얹는 패턴을 사용한다. |
| [PR #765](https://github.com/bluetape4k/bluetape4k-leader/pull/765) / [Issue #738](https://github.com/bluetape4k/bluetape4k-leader/issues/738) | probe 중 일반 `Exception`은 `UNKNOWN`으로 정규화하되 fatal `Error`는 삼키지 않아야 한다. |
| [Issue #766](https://github.com/bluetape4k/bluetape4k-leader/issues/766) | 이번 범위는 core base contract와 기존 provider migration이며, framework endpoint나 새 backend 추가는 제외한다. |
| 격리 worktree baseline | `./gradlew :bluetape4k-leader-core:test --no-daemon --no-configuration-cache --max-workers=1 --console=plain`이 840 tests 실행 후 `BUILD SUCCESSFUL`이다. |

현재 공개 모델과 내장 helper 경로의 의미는 다음과 같이 보존한다.

- `NOT_CHECKED`는 probe를 실행하지 않은 `diagnostics(probe = false)` 전용
  결과로 내장 helper가 생성하지 않는다. 기존 public interface를 직접
  override하는 사용자 provider는 helper 적용 대상이 아니며, source
  호환성을 위해 기존 반환 동작을 유지한다.
- `UNKNOWN`은 backend가 정상이라는 뜻이 아니라 bounded 검사로 상태를
  확정하지 못했다는 뜻이다.
- 기존 client의 lifecycle 또는 connection 상태를 읽는 것만 허용하며,
  lock 획득·lease 변경·backend scan·client/background executor 생성은
  수행하지 않는다.

## 3. 대안과 선택

### 대안 A — provider별 KDoc와 helper만 정리

변경량은 가장 작지만 timeout 검증, timestamp 생성, 예외 경계가 다시
복사된다. 새 provider가 같은 규칙을 놓칠 가능성도 남는다. 이번 이슈의
base 기능 목표를 충족하지 못하므로 채택하지 않는다.

### 대안 B — `leader-core` 공개 probe helper 추가 (권장)

`LeaderBackendDiagnosticsProbe`가 timeout 검증, 단일 `checkedAt` 생성,
일반 `Exception -> UNKNOWN`, cancellation/interruption 보존, `Error` 재전파,
상태 불변식 검증을 담당한다.
provider는 기존 client에서 `LeaderBackendConnectivityStatus`만 판정하고
helper를 호출한다. 상속 구조를 바꾸지 않으면서 모든 provider가 같은
contract를 사용한다.

### 대안 C — 추상 base provider class 도입

중복은 더 줄일 수 있지만, 현재 provider가 interface delegation을 사용하는
구조와 충돌하고 공개 상속 계층을 추가한다. 사용자 provider의 상속 선택을
제한하므로 채택하지 않는다.

## 4. 권장 API와 실행 흐름

새 파일은
`leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbe.kt`
로 추가한다.

```kotlin
object LeaderBackendDiagnosticsProbe {
    fun check(
        timeout: Duration,
        clock: Clock = Clock.systemUTC(),
        probe: (Duration) -> LeaderBackendConnectivityStatus,
    ): LeaderBackendConnectivity
}
```

### 실행 규칙

1. `timeout.requireGt(Duration.ZERO, "probe timeout")`로 양수를 검증한다.
2. `timeout.isFinite()`를 별도로 검증해 `Duration.INFINITE`를 거부한다.
3. `clock.instant()`를 callback 실행 전에 한 번만 호출한다.
4. `probe(timeout)`를 호출해 backend 상태를 얻는다.
5. callback이 `CancellationException`을 던지면 동일 인스턴스를 재전파한다.
6. callback이 `InterruptedException`을 던지면 현재 thread의 interrupt flag를
   복원한 뒤 동일 인스턴스를 재전파한다.
7. 그 밖의 일반 `Exception`을 던지면 동일한 `checkedAt`으로
   `LeaderBackendConnectivity.unknown(checkedAt)`을 반환한다.
8. callback이 `Error`를 던지면 catch하지 않고 동일 인스턴스를 재전파한다.
9. callback이 `UP`, `DOWN`, `UNKNOWN`을 반환하면 동일한 `checkedAt`으로
   대응하는 `LeaderBackendConnectivity` factory를 호출한다.
10. callback이 `NOT_CHECKED`를 반환하면 내장 probe helper의 계약 위반이므로
   `IllegalArgumentException`을 던진다. 이 예외는 callback `Exception` 정규화
   범위 밖에서 발생해야 한다.

`Clock` 자체의 `instant()` 실패는 probe callback 실패와 다른 구성 오류다.
따라서 helper가 정규화하지 않고 호출자에게 전파한다. helper는 latency를
측정하지 않으며 결과의 `latencyMillis`는 `null`로 유지한다. 기존 사용자
provider가 직접 latency를 제공하는 `LeaderBackendConnectivity` 구현은
계속 허용한다.

### Provider 기본 경로

`LeaderBackendDiagnosticsProvider.checkConnectivity`의 기본 구현은 다음
의미를 유지하면서 helper를 사용한다.

```kotlin
return LeaderBackendDiagnosticsProbe.check(timeout) {
    LeaderBackendConnectivityStatus.UNKNOWN
}
```

`diagnostics(probe = true)`의 기존 timeout 사전 검증은 유지한다. 따라서
사용자 정의 provider가 자체 `checkConnectivity`를 구현하더라도 invalid
timeout은 callback 전에 거부된다. 다만 기존 override가 helper를 우회하는
경우에는 예외 정규화, `NOT_CHECKED` postcondition, checkedAt 단일화가
강제되지 않는다. 이는 public interface의 source 호환성을 보존하기 위한
경계이며 사용자 provider가 helper를 호출해야 공통 contract를 얻는다.
`probe = false`일 때는 helper를 호출하지 않고 계속
`LeaderBackendConnectivity.notChecked()`를 반환한다.

## 5. Provider migration 범위

| 모듈 | 변경 |
|---|---|
| `leader-core` | 새 helper 추가, 기본 provider와 Local provider를 helper 기반으로 변경, 공통 timeout 검증에 `requireGt` 사용 |
| `leader-mongodb` | 수동 timeout 검증·clock 생성을 제거하고 helper로 `UNKNOWN` 반환 |
| `leader-redis-lettuce` | 중복 helper 제거, `connection.isOpen` 상태 판정을 helper callback으로 이동 |
| `leader-redis-redisson` | 중복 helper 제거, shutdown 상태 판정을 helper callback으로 이동 |
| `leader-hazelcast` | 수동 `try/catch`와 검증을 helper로 이동하고 lifecycle 상태 의미 유지 |
| `leader-zookeeper` | 수동 `try/catch`와 검증을 helper로 이동하고 Curator 연결 상태 의미 유지 |
| 기타 정적 provider | 현재처럼 기본 `UNKNOWN` 경로를 상속하므로 source 변경 없음 |
| `README.md`, `README.ko.md`, `leader-ktor/README*`, `leader-spring-boot/README*` 및 새 helper KDoc | bounded passive probe, `Exception`/`Error`, cancellation/interruption, `NOT_CHECKED`, adapter 경계를 문서화 |
| `leader-spring-boot`, `leader-ktor`, `leader-micrometer` | endpoint·route·metric JSON shape와 opt-in 정책은 유지하되, 내장 provider migration으로 달라지는 예외·HTTP·health 경계를 검증하고 문서화 |

provider별 상태 의미는 바꾸지 않는다.

- Local: 항상 `UP`.
- MongoDB: bounded 연결 증명을 하지 않으므로 `UNKNOWN`.
- Lettuce: open이면 `UNKNOWN`, 닫혔으면 `DOWN`.
- Redisson: shutdown 또는 shutting down이면 `DOWN`, 그 외 `UNKNOWN`.
- Hazelcast: running이면 `UNKNOWN`, 아니면 `DOWN`.
- ZooKeeper: connected면 `UP`, 아니면 `DOWN`.

내장 helper를 사용하는 provider에서 callback 내부의 일반 `Exception`은
`UNKNOWN`으로 정규화하고, `CancellationException`과
`InterruptedException`은 각각 재전파하며 `InterruptedException`의 flag를
복원한다. `Error`는 기존 인스턴스로 재전파한다. helper를 우회하는 기존
사용자 override의 예외 정책은 변경하지 않는다.

### Adapter 경계와 custom override 호환성

`checkConnectivity()`를 직접 override한 legacy custom provider는 helper를
호출하지 않아도 된다. `diagnostics()`를 override한 provider도 금지하지 않으며,
그 경우 이 이슈의 내장 helper contract가 결과를 재작성하지 않는다. 따라서
custom provider의 반환값·예외 정책을 adapter가 소비하는 경계를 다음처럼
명시한다.

| 호출 표면 | 내장 helper provider | legacy custom provider |
|---|---|---|
| 직접 `diagnostics(probe = true)` | 일반 `Exception`은 `UNKNOWN`, cancellation/interruption/Error는 재전파, `NOT_CHECKED`는 `IllegalArgumentException` | provider가 반환·전파한 값과 예외를 그대로 노출 |
| Ktor diagnostics route | 일반 `Exception`이 helper에서 정규화되면 HTTP 200 JSON의 `UNKNOWN`; cancellation/interruption/Error는 route의 application pipeline으로 재전파 | 일반 `Exception`·cancellation/interruption/Error는 route가 변환하지 않고 application pipeline 정책을 따름; 반환 `NOT_CHECKED`는 JSON에 그대로 표시 |
| Spring health indicator | 일반 `Exception`이 helper에서 정규화되면 `UNKNOWN` detail을 반환하고 warning은 남기지 않음; helper가 재전파한 cancellation/interruption은 `UNKNOWN` + warning(중단 시 flag 복원), helper가 거부한 `NOT_CHECKED`는 `UNKNOWN` + warning, Error는 재전파 | indicator의 기존 catch 경계가 일반 `Exception`/cancellation/interruption을 `UNKNOWN` + warning으로 만들고(중단 시 flag 복원), Error는 재전파; 반환 `NOT_CHECKED`는 `UNKNOWN` detail이며 warning은 없음 |

Ktor의 일반 예외 HTTP status는 library가 고정하지 않고 애플리케이션의
`StatusPages`/pipeline 정책을 따른다. 위 표의 내장 provider `HTTP 200 + UNKNOWN`
변화와 Spring의 detail·warning 변화는 provider migration의 의도된 계약이며,
payload field shape는 유지한다. 직접 호출·Ktor·Spring에 대해 built-in/custom과
일반 예외·cancellation·interruption·Error·`NOT_CHECKED` 조합을 회귀 테스트로
고정한다. 이 표는 `diagnostics()` override를 새로 금지하지 않고 기존 source
호환성을 보존하면서 호출자가 adapter별 결과를 추측하지 않도록 하는 경계다.

Spring의 `UNKNOWN + warning`은 provider 호출이 예외로 끝난 경우에만 적용하고,
helper가 반환한 `UNKNOWN` 또는 custom provider가 반환한 `NOT_CHECKED`에는
allow-listed detail을 사용한다. Ktor의 built-in `NOT_CHECKED`는 helper가
`IllegalArgumentException`으로 거부하므로 application pipeline으로 전파되고,
custom provider가 반환한 `NOT_CHECKED`만 JSON에 표시된다.

## 6. 호환성과 위험 경계

- 새 `LeaderBackendDiagnosticsProbe`는 Kotlin 중심의 공개 Kotlin/JVM API다. 기존
  interface, enum, data class, endpoint JSON shape는 변경하지 않는다.
- Kotlin `object`의 단일 `check` 함수와 `Clock` 기본값을 사용한다. Java
  source ergonomics를 위한 별도 static overload나 `java.time.Duration`
  bridge, 새 dependency는 추가하지 않는다. 이 helper의 Java source 호출은
  지원하지 않으며 Java facade는 후속 범위로 남긴다. Kotlin/JVM ABI와
  `checkBinaryCompatibility` 결과만 이 이슈의 호환성 기준으로 삼는다.
- helper는 순수한 상태 매핑과 callback 경계만 담당하므로 실제 backend I/O의
  timeout을 wall-clock으로 강제하거나 executor/thread hop을 수행하지 않는다.
  provider callback이 bounded·read-only 계약을 지켜야 하며, helper는 전달받은
  timeout을 provider-native budget으로만 제공한다. 실제 deadline 강제는
  별도 이슈에서 다룬다.
- 일반 `Exception` 정규화는 운영 진단의 fail-closed 동작을 위한 것이며,
  cancellation/interruption은 제어 흐름이므로 보존한다.
  `Error`를 복구 가능한 backend 상태로 오인하지 않도록 반드시 재전파한다.
- 내장 provider의 callback은 호출 thread에서 동기 실행되며 helper는 공유
  mutable state를 만들지 않는다. 동시성 안전성은 주입된 `Clock`과 callback의
  책임이다.
- 출시 전 rollback은 helper 파일과 provider import/호출 변경을 함께 revert할 수
  있다. 출시 후에는 새 public helper ABI를 삭제하지 않고 유지한 채 provider
  migration만 corrective release에서 되돌린다. 공개 API 삭제가 필요한 경우는
  별도 major compatibility 결정으로 분리한다. `checkBinaryCompatibility`와
  별도로 Kotlin consumer compile smoke, `jar tf`, `javap`로 helper signature와
  artifact 포함을 확인한다. 기존 `checkConnectivity(Duration)` 공개 메서드는
  유지되므로 사용자 provider의 source 호환성을 깨지 않는다.

## 7. 테스트와 검증 계획

### Core RED/GREEN

`LeaderBackendDiagnosticsProbeTest`에 다음 사례를 먼저 실패하도록 작성하고
구현으로 통과시킨다.

- `UP`, `DOWN`, `UNKNOWN` 상태가 고정 clock의 동일 `checkedAt`으로 매핑된다.
- 양수 미만과 `Duration.INFINITE`는 callback을 호출하지 않고 거부된다.
- callback의 일반 `Exception`은 `UNKNOWN`으로 정규화된다.
- callback의 `CancellationException`은 동일 인스턴스로 재전파된다.
- callback의 `InterruptedException`은 interrupt flag를 복원하고 동일
  인스턴스로 재전파된다.
- callback의 `Error`는 동일 인스턴스로 재전파된다.
- `NOT_CHECKED` 반환은 `IllegalArgumentException`으로 거부된다.
- callback에 동일 timeout이 전달되고 clock은 한 번만 읽힌다.
- clock read가 callback보다 먼저 일어나고 callback은 호출 thread에서 한 번만
  실행되며, 여러 동시 호출 사이에 helper 공유 상태가 없음을 확인한다.
- clock의 `instant()` 예외는 동일 인스턴스로 전파되고 callback은 호출되지
  않는다.
- invalid timeout에서는 clock과 callback을 모두 호출하지 않는다.

`InterruptedException` 회귀 테스트는 전용 thread에서 실행하거나 테스트의
`finally`에서 `Thread.interrupted()`로 flag를 확실히 제거해 JUnit worker의
후속 테스트에 interrupt 상태를 남기지 않는다. provider별 interruption
테스트도 같은 cleanup 규칙을 사용한다.

기존 `LeaderBackendDiagnosticsTest`와 `LocalLeaderBackendDiagnosticsTest`는
기본·Local 동작이 그대로 유지되는지 확인한다.

### Provider 회귀

Lettuce, Redisson, Hazelcast, ZooKeeper 테스트에 기존 상태 판정과 일반
`Exception`/`Error` 경계를 확인하고, helper migration으로
`CancellationException`/`InterruptedException`을 보존하는 사례를 둔다.
Lettuce/Redisson뿐 아니라 현재 `Exception`을 `UNKNOWN`으로 정규화하던
Hazelcast/ZooKeeper의 direct `checkConnectivity()` 호출도 cancellation/
interruption에 대해서는 의도적으로 재전파·interrupt 복원 동작으로 바뀐다.
각 provider direct 호출과 Spring health 경계를 별도로 검증한다. 실제
네트워크나 Testcontainers를 새로 요구하지 않고 현재 MockK 기반 fixture를
재사용한다. provider별 passive-probe contract fixture로 lock·lease·scan·
client factory 호출이 없음을 확인한다.

내장 provider와 legacy custom provider를 각각 사용해 direct call, Ktor route,
Spring health의 결과표를 검증한다. Ktor는 built-in ordinary `Exception`의
`200 + UNKNOWN` JSON과 custom exception의 application pipeline 위임을,
Spring은 built-in ordinary `Exception`의 normalized detail/no-warning,
built-in cancellation/interruption/invalid `NOT_CHECKED`의 `UNKNOWN + warning`,
custom catch warning과 returned `NOT_CHECKED` detail/no-warning을 검증하며,
Error/interrupt flag도
포함한다. interruption 테스트는 시작 전 `Thread.interrupted()`로 상태를
비우고, 전용 thread를 사용하면 `join(timeout)`과 종료 상태를 확인하며,
현재 thread를 사용하면 `finally`에서 다시 flag를 제거한다.

### 정적·통합 검증

- `:bluetape4k-leader-core:test`
- 변경 provider 모듈의 targeted test 및 module test
- `detekt`
- `ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=1.0.0 ./gradlew --no-daemon --console=plain --no-configuration-cache checkBinaryCompatibility`
- `git diff --check`

Spring Boot/Ktor route payload와 Micrometer metric field shape와 opt-in 정책은
유지하되, 내장 provider exception normalization으로 달라지는 HTTP status,
health detail·warning, custom override 위임을 adapter regression test로
검증한다. 새 probe I/O를 추가하지 않는다.
route/health 호출이 callback의 wall-clock timeout을 강제하지 않는다는 점과
provider-native bounded 책임은 운영 문서와 KDoc에 명시한다. 현재
`LeaderBackendDiagnosticsProvider`의 “주어진 timeout 안에서” 표현도
“provider-native budget을 전달한다”는 의미로 정정한다. Ktor/Spring route와
health는 custom provider가 반환한 상태·예외 정책을 그대로 소비할 수 있으므로
helper 적용은 내장 provider 경계라는 점을 문서화한다. raw exception을
응답에 넣지 않는 대신 원인별 저카디널리티 로그·metric hook은 이번 범위에서
추가하지 않는다. `UNKNOWN`은 readiness 통과나 backend 정상의 증거가 아니며,
`DOWN`과 `NOT_CHECKED`를 readiness·경보로 해석하는 애플리케이션 정책도
자동으로 정하지 않는다. 이 운영 계약과 원인 신호·runbook은 후속 Issue #774
([diagnostics UNKNOWN 원인 신호와 readiness 정책 정립](https://github.com/bluetape4k/bluetape4k-leader/issues/774))에서 다룬다.

## 8. Acceptance criteria와 DoD

### Acceptance criteria

1. `leader-core`에 문서화된 공개 `LeaderBackendDiagnosticsProbe`가 존재한다.
2. timeout 검증은 `requireGt`와 유한성 검사를 사용하고 callback 전에 끝난다.
3. 내장 helper callback의 일반 `Exception`은 `UNKNOWN`,
   `CancellationException`/`InterruptedException`/`Error`는 제어 흐름과
   fatal 상태를 보존한다.
4. 내장 helper는 `NOT_CHECKED`를 허용하지 않으며, 사용자 override의 기존
   source 호환성 경계를 문서화한다.
5. 지정한 여섯 provider가 helper를 사용하고 상태 의미를 유지한다.
6. lock, lease, scan, client 생성, background executor 생성이 추가되지 않는다.
7. helper가 wall-clock timeout을 강제한다고 주장하지 않으며, provider-native
   bounded 책임과 실제 deadline 후속 범위를 명시한다.
8. 기존 diagnostics endpoint/route/metric JSON field shape와 opt-in 기본값이
   변하지 않는다. 내장 provider 일반 예외의 Ktor `200 + UNKNOWN`, Spring
   detail·warning 경계와 custom override의 application-owned 예외 정책은
   명시된 adapter 표와 회귀 테스트를 따른다.
9. core/provider/adapter 테스트, detekt, ABI, Kotlin consumer compile smoke,
   `jar tf`/`javap`, diff check가 통과한다.

### DoD

- [ ] 설계·계획·리뷰 artifact에 한국어 기술 문체와 source traceability를
  적용했다.
- [ ] RED/GREEN 테스트가 helper contract와 provider 회귀를 증명한다.
- [ ] 공개 API KDoc과 root README 쌍 및 `leader-ktor`/`leader-spring-boot` README
  EN/KO diagnostics 문구가 현재 동작과
  일치한다. versioned `docs/manual` EN/KO 갱신은 manifest의 release pin을
  변경하는 별도 1.0.0 release-train 작업으로 이 이슈에서 수행하지 않는다.
- [ ] 독립 6-perspective 리뷰에서 P0/P1이 0건이다.
- [ ] 검증 명령의 fresh 결과와 known gap을 기록했다.
- [ ] PR 생성과 merge는 별도 exact target 승인 게이트로 남긴다.

## 9. 범위 제외

- 실제 network connectivity probe나 backend command 추가
- callback을 위한 wall-clock deadline enforcement, executor, thread hop 추가
- raw exception의 새 log/metric/hook surface 추가
- Spring Boot Actuator, Ktor route, Micrometer payload/metric field shape 변경
- versioned `docs/manual` EN/KO 갱신 또는 `manifest.yaml` release pin 변경
- capability enum 또는 endpoint schema 변경
- 추상 base class, 새 module, 새 dependency 도입
- Issue #766과 직접 연결되지 않은 provider 기능 개선

## 10. 설계 self-review 및 Writer DoD

- 미완성 요구사항과 빈 섹션: 없음
- 대안·권장안·실행 규칙·provider 범위·실패 모드·호환성·검증·DoD: 모두
  명시함
- source-to-claim 확인: Issue #533/#559/#738/#766, PR #765, 현재 core/provider
  source와 baseline 결과에 연결함
- 공개 API와 내부 helper의 경계: 새 공개 object와 기존 interface 기본 경로를
  분리함
- 독립 review 상태: 6-perspective 통합 review PASS(P0/P1/P2=0).
- SPW-01~SPW-05: 보완 반영 후 read-back PASS; `git diff --check`와
  `audit-korean-terms.mjs` findings 0건.
- KO-01~KO-07: 기술 토큰·수치·URL을 보존했고, 번역투·추상적 효율성 주장·용어
  혼용을 제거했으며 `audit-korean-terms.mjs` 결과는 findings 0건이다.
