# Issue #829: Micrometer decorator 설정 경계 보존

## Context

`InstrumentedLeaderElector`, `InstrumentedLeaderGroupElector`,
`InstrumentedSuspendLeaderElector`는 delegate의
`LeaderBackendDiagnosticsProvider`를 계측한다. 중첩 decorator가 서로 다른
`MeterRegistry` 또는 tag policy를 받더라도 바깥 decorator의 관찰 설정을
적용해야 한다.

## Root Cause

`LeaderBackendDiagnosticsProvider.instrumented()`는 이미
`InstrumentedLeaderBackendDiagnosticsProvider`인 경우 registry와
`LeaderMetricTagSanitizer`를 비교하지 않고 기존 provider를 반환했다. 그
결과 내부 registry와 내부 sanitizer가 바깥 설정을 덮어썼다. sanitizer는
`fun interface`라서 새 lambda의 객체 identity를 비교해도 같은
`LeaderMetricTagOptions`를 안정적으로 판정할 수 없다.

## Decision

세 elector decorator가 `LeaderMetricTagOptions`를 private 상태로 보존하고,
diagnostics provider는 같은 `MeterRegistry` 인스턴스와 equal options일 때만
재사용한다. 설정이 다르면 모든 기존 instrumentation wrapper를 벗긴
underlying provider에 바깥 registry와 options를 적용한 새 provider를 만든다.
이렇게 하면 같은 설정은 중복 계수하지 않고, 다른 설정은 내부 metric
registry나 tag policy를 재사용하지 않는다.

## Verification

- RED: 기존 구현에서 outer registry count가 `0.0`이고 shared registry의
  outer policy count가 `0.0`, inner policy count가 `2.0`으로 관찰됐다.
- GREEN: `InstrumentedLeaderElectorsTest` targeted run에서 `31 passing`,
  `BUILD SUCCESSFUL`을 확인했다.
- 기존 exception identity, cancellation rethrow, interrupt restoration,
  blocking/group/suspend execution-model 테스트를 함께 유지했다.

## Miss or Surprise

첫 구현 시 private primary constructor와 public secondary constructor가 같은
4-인자 타입으로 겹쳐 Kotlin overload ambiguity가 발생했다. private
constructor에 sanitizer를 함께 유지하는 5-인자 구분을 두어 컴파일 경계를
복구했다.

## Future Guard

중첩 decorator를 추가하거나 수정할 때는 다음 두 조건을 각각 회귀 테스트로
고정한다.

1. 같은 registry identity와 equal `LeaderMetricTagOptions`는 같은 provider를
   재사용하고 한 번만 계수한다.
2. registry 또는 options가 다르면 underlying provider에서 새 계측을 만들고
   바깥 설정의 metric series만 증가시킨다.

`LeaderMetricTagSanitizer` lambda identity를 policy equality의 근거로 사용하지
않고, public API/metric 이름을 바꾸지 않은 상태에서 options를 비교한다.
