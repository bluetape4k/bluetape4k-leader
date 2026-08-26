# `SuspendBeanInitialization` timeout 뒤 bounded cleanup 계약

**관련 이슈**: [#794](https://github.com/bluetape4k/bluetape4k-leader/issues/794)
**관련 PR**: [#797](https://github.com/bluetape4k/bluetape4k-leader/pull/797)
**영향 모듈**: `leader-spring-boot`

## 맥락

Spring의 동기 `@Bean` 경계에서 suspend backend 초기화를 실행하는
`createSuspendBackendBean`은 호출 thread를 무기한 붙잡지 않도록 별도
`CoroutineScope`와 초기화 `timeout`을 사용한다. 기존 계약은 timeout이 지나면
`scope.cancel()`을 호출하고 `TimeoutCancellationException`을 반환하는 데
그쳤다. 이미 시작된 block이 취소에 협조하지 않으면 `scope.cancel()`은 그
작업이 끝났는지 기다리지 않으므로, Spring startup 실패 뒤에도 초기화가
실행될 수 있었다.

## 원인

`CoroutineScope.cancel()`은 scope에 취소를 전파하지만 non-cooperative 작업을
강제 종료하거나 join하지 않는다. 따라서 timeout 경계에서 다음 두 결과를
구분해야 했다.

- cooperative 작업: 취소 후 종료를 확인하고 bean 초기화 오류를 반환한다.
- non-cooperative 작업: 제한된 시간만 종료를 기다린 뒤 잔여 실행 가능성을
  관측하고 bean 초기화 오류를 반환한다.

## 결정

`SuspendBeanInitialization.kt`의 `finally`에서 `NonCancellable` context를
사용해 `task.cancelAndJoin()`을 `withTimeoutOrNull(cleanupTimeout)`으로 감싼다.
cleanup이 grace window 안에 끝나면 잔여 작업을 정리하고, 끝나지 않으면
`io.bluetape4k.logging` warning을 남긴 뒤 `scope.cancel()`을 호출한다. 기본
cleanup grace는 `100.milliseconds`이며, 강제 thread 종료는 사용하지 않는다.

cleanup 경로가 원래 오류를 가리지 않도록 별도의 예외를 던지지 않는다.
따라서 초기화 timeout의 `TimeoutCancellationException`, 초기화 block의
`CancellationException`, Spring bean 생성 실패 원인은 기존 호출자에게
그대로 전파된다.

## 결과

- timeout 이후 cooperative cleanup은 `cancelAndJoin()`으로 완료를 확인한다.
- non-cooperative cleanup은 `cleanupTimeout`을 넘겨 Spring startup 실패를
  다시 무기한 지연시키지 않는다.
- grace window를 넘긴 잔여 실행은 warning으로 관측할 수 있고, 원래
  `TimeoutCancellationException`은 유지된다.
- dispatcher queue에 작업이 대기 중인 경우에도 초기화 timeout은 queue 대기를
  포함한다. 호출자 thread가 queue 작업의 실행 완료를 무기한 기다리지 않는다.

## 검증

`SuspendBeanInitializationTest`에 다음 회귀 계약을 추가하고
`io.bluetape4k.assertions.assertFailsWith`로 예외 타입을 검증했다.

- timeout 뒤 non-cooperative cleanup이 release될 때 bridge가 cleanup 완료까지
  기다린다.
- cleanup이 release되지 않으면 `50.milliseconds` grace 뒤 bridge가 반환하고
  원래 `TimeoutCancellationException`을 유지한다.
- dispatcher queue 대기, 일반 timeout, cancellation, Spring startup failure와
  `cleanupTimeout`의 positive finite 검증을 유지한다.

현재 변경 기준 검증 결과는 다음과 같다.

- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.backend.SuspendBeanInitializationTest' --no-build-cache --rerun-tasks`: 11/11 PASS
- `./gradlew :bluetape4k-leader-spring-boot:detekt --no-build-cache --rerun-tasks`: PASS
- `./gradlew :bluetape4k-leader-spring-boot:compileKotlin :bluetape4k-leader-spring-boot:compileTestKotlin --no-build-cache --rerun-tasks`: PASS
- `python3 scripts/ci/validate_test_assertion_contract.py --root .`: PASS
- 전체 `leader-spring-boot:test`의 `LeaderRouteLeaseDiagnosticsTest` 실패 1건은
  수정 전 clean `develop`에서도 재현된 `NoSuchFileException` baseline으로,
  이번 bounded cleanup 변경의 범위 밖이다.

## 놓친 점과 예상 밖의 결과

처음에는 `scope.cancel()`만으로 timeout 뒤 작업의 생명주기가 끝난다고
가정했다. RED 회귀 테스트에서 이미 시작된 non-cooperative cleanup이
release될 때까지 bridge가 반환되기 전에 이 가정이 틀렸음을 확인했다.
반대로 join을 무제한으로 기다리면 Spring startup failure가 다시 무제한으로
지연되므로, 취소 협조 여부를 호출자가 바꿀 수 없는 작업에 대해서는 잔여
실행 가능성을 인정하는 bounded 계약이 필요하다.

독립 7-Tier 검토에서는 P0/P1이 없었지만, warning에 backend/bean 식별자가
없고 warning 발생 자체를 고정한 테스트가 없다는 `WATCH` 관찰사항이 남았다.
이번 PR에서는 bounded lifecycle 계약을 우선 고정하고, 운영 식별자와 warning
capture 테스트는 별도 후속 범위로 남긴다.

## 향후 guard

새로운 suspend bean bridge를 추가하거나 수정할 때 다음 규칙을 유지한다.

1. 초기화 timeout과 cleanup grace를 각각 유한하게 검증한다.
2. timeout 경로는 `NonCancellable` 안에서 `cancelAndJoin()`을 bounded wait로
   실행하고, cleanup 예외로 원래 `CancellationException` 계열을 덮지 않는다.
3. 테스트는 cooperative 작업과 non-cooperative 작업을 모두 명시적으로
   시작시킨 뒤, cleanup 완료 대기와 grace 초과 후 반환을 각각 검증한다.
4. non-cooperative 잔여 실행을 강제 thread 종료로 해결하지 않는다. warning에
   low-cardinality backend/bean context를 포함하는 후속 변경을 검토한다.
