---
title: "백엔드 connectivity observability와 readiness 운영 런북"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 백엔드 connectivity observability와 readiness 운영 런북

> Issue #774와 OBS-01–04 stacked train을 위한 unreleased 초안입니다. 현재
> `develop` source에는 이 additive observability 계약이 포함되어 있지만,
> versioned manual은 `721a9a3808f67489d2bdb8177734325981c24977`의 pinned
> `0.5.0`에 고정되어 있습니다. 전체 train을 포함한 release commit이 나올
> 때까지 이 초안을 승격하거나 `docs/manual/manifest.yaml`을 변경하지 마세요.

이 문서는 backend connectivity diagnostics를 해석하는 방법을 설명합니다.
`runIfLeader`가 수행하는 원자적 ownership 결정을 대체하지 않으며, best-effort
health signal을 fencing이나 force-release 연산으로 바꾸지도 않습니다.

## 상태와 reason 모델

모든 connectivity 결과는 하나의 status와 제한된 하나의 reason을 가집니다.
reason은 enum이며 exception message나 provider payload가 아닙니다.

| 상태 | Reason | 의미 |
|---|---|---|
| `UP` | `CONNECTED` | 기존 client가 probe 시점에 연결 가능 상태를 확인했습니다. |
| `DOWN` | `DISCONNECTED` | 기존 client가 backend를 사용할 수 없는 상태를 확인했습니다. |
| `UNKNOWN` | `CLIENT_STATE_UNCONFIRMED` | bounded read-only 검사만으로 connectivity를 증명하지 못했습니다. |
| `UNKNOWN` | `PROVIDER_UNSUPPORTED` | provider가 지원하는 active probe를 제공하지 않습니다. |
| `UNKNOWN` | `PROVIDER_EXCEPTION` | 일반 provider 예외를 원문 없이 정규화했습니다. |
| `NOT_CHECKED` | `NOT_CHECKED` | active probe를 요청하지 않았으며 health signal이 아닙니다. |

`UP`과 `DOWN`은 active check의 결과이며 lock ownership을 뜻하지 않습니다.
`UNKNOWN`은 둘과 구분해야 합니다. `NOT_CHECKED`는 passive diagnostics의
정상적인 결과이며 `UP`으로 해석하면 안 됩니다.

공개 결과는 다음처럼 additive로 확장되었습니다.

```kotlin
data class LeaderBackendConnectivity(
    val status: LeaderBackendConnectivityStatus,
    val checkedAt: Instant?,
    val latencyMillis: Long?,
    val reason: LeaderBackendConnectivityReason,
)
```

기존 Kotlin 생성 방식과 JSON field는 유지됩니다. strict JSON deserializer를
사용하는 소비자는 새 `reason` field를 허용하도록 설정하세요.

## source와 adapter 매핑

| Source 또는 adapter | 계약 |
|---|---|
| Direct/core diagnostics | `diagnostics()`는 passive이며 `NOT_CHECKED`를 반환합니다. active `checkConnectivity`는 provider 결과와 제한된 reason을 반환합니다. |
| Ktor `/management/leaderElection/diagnostics` | pipeline이 결과를 만들면 diagnostics payload와 함께 HTTP 200을 반환합니다. `connectivity.status`와 `connectivity.reason`이 backend 의미를 전달합니다. |
| Spring `leaderBackendDiagnostics` | 정적 diagnostics는 계속 opt-in passive입니다. backend health에 제한된 `reason` detail을 추가하고 기존 `UP`/`DOWN`/`UNKNOWN` mapping을 유지합니다. |
| Spring readiness | readiness indicator는 local lock state와 lease expiry를 평가합니다. 별도 signal이며 backend `DOWN`이나 `UNKNOWN`을 자동으로 합치지 않습니다. |
| Application pipeline | route 밖으로 전파된 custom provider 예외의 HTTP status는 Ktor `StatusPages` 또는 application의 Spring/web pipeline이 소유합니다. library가 강제로 바꾸지 않습니다. |

Ktor의 HTTP 200은 diagnostics 결과를 직렬화했다는 transport 결과일 뿐 backend가
정상이라는 뜻이 아닙니다. `status = UNKNOWN` payload와 pipeline exception은
서로 다른 경우입니다.

## Micrometer와 Prometheus

instrumented elector는 active `checkConnectivity` 또는
`diagnostics(probe = true)` 호출마다 `leader.backend.connectivity` counter를
정확히 한 번 기록합니다. background poller를 추가하지 않으며 passive
`diagnostics()` 호출은 series를 만들지 않습니다.

| Tag | 허용 값과 보호 규칙 |
|---|---|
| `backend.name` | 정제된 descriptor backend ID입니다. endpoint, credential, tenant, lock name은 금지합니다. |
| `status` | `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED`만 허용합니다. |
| `reason` | `LeaderBackendConnectivityReason`의 6개 enum 이름만 허용합니다. |

Registry naming은 source meter를 Prometheus에서
`leader_backend_connectivity_total`로 변환합니다. exception class, message,
endpoint, credential, raw provider payload, lock name을 export하지 않습니다.
Active probe 주기는 caller의 scheduler에서 제한하세요. counter event는
관측이며 retry 지시가 아닙니다.

번들 dashboard의 query도 warning 목적과 낮은 cardinality를 유지합니다.

```promql
sum by (backend_name, status, reason) (rate(leader_backend_connectivity_total[5m]))
sum by (backend_name) (
  rate(leader_backend_connectivity_total{status="DOWN",reason="DISCONNECTED"}[5m])
)
sum by (backend_name, reason) (
  rate(leader_backend_connectivity_total{status="UNKNOWN"}[5m])
)
```

예제의 `LeaderBackendConnectivityDown` rule은 5분 지속을 요구하고
`notification: no-page`입니다. `UNKNOWN`과 `PROVIDER_EXCEPTION` rule도
지속 관측을 요구하며 `UNKNOWN`을 자동으로 `DOWN`으로 승격하지 않습니다.

## Provider와 #766 호환성 경계

내장 provider는 공개 `LeaderBackendDiagnosticsProbe.check` helper를 사용합니다.
helper는 양수이면서 유한한 provider-native timeout을 검증하고 callback 전에
clock을 한 번 읽습니다. 일반 `Exception`은
`UNKNOWN + PROVIDER_EXCEPTION`으로 매핑하며 cancellation, interrupt 복원,
치명적인 `Error`는 다시 전파합니다. active probe callback이 `NOT_CHECKED`를
반환하면 잘못된 결과입니다.

helper의 `unknownReason`으로 unsupported provider와 client 상태 미확정을
구분합니다. 기존 `checkConnectivity` 또는 `diagnostics` custom override는
escape hatch로 유지되고 provider 예외 동작을 계속 소유합니다. Micrometer는
그 결과를 바꾸지 않고 관측만 추가합니다. 아직 manual diagnostics 경계를
사용하는 legacy provider는 호환 가능한 기본 reason을 유지하고 별도 provider
migration issue에서 갱신합니다. 이 초안은 그 경계를 기록할 뿐 #766 구현
범위를 조용히 넓히지 않습니다.

## Timeout과 wall-clock 운영 런북

`LeaderBackendDiagnosticsProbe.check`의 `timeout`은 provider-native budget입니다.
helper가 값을 검증해 callback에 전달하지만 caller thread의 wall-clock deadline은
보장하지 않습니다. Budget을 무시하거나 cancellation을 지원하지 않는 client는
호출 thread를 계속 대기시킬 수 있습니다.

Probe가 느리거나 상태가 불확실하면 다음 순서를 따르세요.

1. `UNKNOWN + CLIENT_STATE_UNCONFIRMED`가 반복되면 기존 client lifecycle과
   native timeout 설정을 먼저 확인합니다.
2. `UNKNOWN + PROVIDER_EXCEPTION`이 증가하면 보호된 structured application
   log와 provider-native diagnostics를 확인합니다. exception 원문을 metric이나
   route detail에 복사하지 마세요.
3. hard request deadline이 필요하면 application이 executor/future timeout 또는
   backend cancellation API를 소유합니다. library helper에 interrupt나 강제
   `Future.cancel`을 추가하지 않습니다.
4. active probe가 request budget을 넘으면 active probe를 끄고 passive
   diagnostics와 기존 state/readiness signal로 우회합니다.

### Bypass와 rollback

`bluetape4k.leader.observability.backend-health` 또는
`backendConnectivityCheckEnabled`를 비활성화하면 active 호출을 중지할 수
있습니다. 필요하면 Ktor/Spring management route를 제거하거나 인증된 내부
네트워크로 제한하세요. Counter series가 사라지는 것은 active probe가 실행되지
않았다는 뜻이며 synthetic `NOT_CHECKED` health sample이 아닙니다.

Train child 하나를 rollback하기 전 JSON consumer가 additive `reason` field를
무시하는지, dashboard가 counter 부재를 견디는지 확인하세요. 한 번에 한 child씩
되돌리며 public field나 meter name을 deprecation과 consumer migration 기간
없이 삭제하지 않습니다. Connectivity 결과만으로 lease를 force-release하지
마세요. backend의 conditional semantics로 ownership을 다시 확인하고 실행은
계속 `runIfLeader`로 수행하세요.

## 운영 체크리스트

- [ ] route가 인증되고 network policy로 제한되어 있습니다.
- [ ] passive diagnostics를 readiness 증거로 사용하지 않습니다.
- [ ] active probe 주기와 caller wall-clock deadline이 명시되어 있습니다.
- [ ] export tag가 `backend.name`, `status`, `reason`으로만 제한되어 있습니다.
- [ ] `UNKNOWN`은 application policy가 없는 한 warning/no-page로 처리합니다.
- [ ] 선택한 backend의 provider-native timeout과 cancellation 동작을 문서화했습니다.
- [ ] built-in과 custom provider를 direct, Ktor, Spring 경계에서 테스트했습니다.
- [ ] `releaseRef`와 `releaseCommit`이 이 계약을 포함한 commit을 가리킨 뒤
      versioned manual을 승격합니다.

개발 line source 설명은 [root diagnostics
section](../../../README.ko.md#runtime-backend-diagnostics),
[Prometheus dashboard 런북](../../../examples/prometheus-dashboard/README.ko.md#alert-runbooks),
[backend 선택 가이드](../ko/guides/backend-selection.md)에서 확인할 수 있습니다.
