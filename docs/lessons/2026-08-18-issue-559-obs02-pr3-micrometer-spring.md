# Issue #559 OBS-02 PR3 Micrometer/Spring wiring lesson

## Context

PR2가 `LeaderLeaseExtensionObserver` core hook을 제공한 뒤, PR3에서 `leader-micrometer`와 `leader-spring-boot`를 연결했다. 목표는 lease-extension attempt를 bounded Micrometer Observation으로 기록하고, 여러 Spring context가 하나의 `ObservationRegistry`를 공유할 때 core observer 등록을 중복하지 않는 것이다.

근거 문서는 다음과 같다.

- `docs/superpowers/specs/2026-08-17-issue-559-lease-extension-observation-design.md`
- `docs/superpowers/plans/2026-08-17-issue-559-lease-extension-observation-implementation.md`

## Decision

- `MicrometerObservationLeaderLeaseExtensionObserver`는 public 구현체로 두고 constructor를 `ObservationRegistry`와 `LeaderObservationOptions`로 고정한다.
- observation은 `source`, `execution`, `outcome`, `result` 네 가지 low-cardinality tag만 기록한다. lock name과 leader ID는 기존 redaction 정책을 거친 high-cardinality field로, exception은 opt-in일 때만 기록한다.
- `LeaseExtensionObservationRegistrationManager`는 `IdentityHashMap<ObservationRegistry, Entry>`와 하나의 `ReentrantLock`으로 registry별 core registration 하나와 reference count를 선형화한다. 마지막 handle만 core registration을 닫는다.
- Spring Boot의 `ObservationRegistryPostProcessor`보다 앞선 condition/BFPP에서 registry instance를 조회하지 않도록, condition은 현재 context와 parent의 bean definition 후보만 확인한다. 모든 singleton post-processing 뒤 `SmartInitializingSingleton` coordinator가 `registry.isNoop`를 판정하고 정상 registry에만 context 소유 handle singleton을 동적으로 추가하며, `DisposableBean` 수명주기에서 자기 handle을 닫는다. 이 lifecycle amendment는 early-instantiation과 NOOP bean 부재를 동시에 보장한다.

## Outcome

동일 registry를 공유하는 context는 callback을 한 번만 전달하고, 서로 다른 registry는 독립적으로 관찰한다. 옵션이 충돌하면 두 번째 observer를 추가하지 않고 `IllegalStateException`을 반환한다. context close는 자기 handle만 닫고 마지막 close에서만 core registration을 제거한다.

## Verification

- RED: observer test가 구현 전 unresolved reference로 실패함을 확인했다.
- `:bluetape4k-leader-micrometer:test --tests '*MicrometerObservationLeaderLeaseExtensionObserverTest'`: 5 tests PASS.
- `:bluetape4k-leader-spring-boot:test --tests '*LeaseExtensionObservationRegistrationManagerTest' --tests '*LeaderObservationAutoConfigurationTest'`: 21 tests PASS.
- 전체 `leader-micrometer:test`: 81 tests PASS.
- 전체 `leader-spring-boot:test`: 454 tests PASS.
- 두 모듈 `detekt`: PASS.
- 두 모듈 `check`: PASS.
- `javap`로 public constructor와 private tag constant surface를 확인했다.
- `git diff --check`와 `leader-core` Micrometer/Spring dependency/source scan: PASS.

## Surprise and miss

`ObservationRegistry.create()`는 handler가 없으면 `isNoop`인 경우가 있어, 처음 작성한 정상 registry fixture가 NOOP 경로를 타는 문제가 있었다. 실제 handler를 등록한 fixture로 수정했다. 또한 Kotlin `private companion object` 안의 기본 `const val`이 JVM public static field로 노출될 수 있어, tag constant에 명시적으로 `private`을 지정하고 `javap`로 재검증했다. Micrometer와 Spring test를 같은 Gradle invocation에서 병렬 요청하면 test-report 파일 경합으로 Gradle이 실패할 수 있어, 최종 증거는 모듈별 순차 실행으로 수집했다.

## Future guard

새 public observer를 추가할 때는 exact constructor와 함께 `javap` 결과에서 public constant가 새로 노출되지 않는지 확인한다. Spring conditional bean은 condition 평가 시점과 실제 registry 초기화 시점을 분리해서 검증하고, Boot observation post-processor, primary registry, parent/child context, NOOP 및 handler가 있는 registry를 각각 ApplicationContext fixture로 고정한다. PR4 문서 작업에서는 redaction, cancellation, watchdog context와 NOOP/disable 동작을 EN/KO manual에 반영한다.
