# Issue #741 Spring observation scope 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 서로 다른 Spring `ObservationRegistry` 사이에서 lease-extension event와 선택적 identity가 교차 기록되지 않도록 하면서 공개 event ABI와 process-global observer 계약을 유지한다.

**Architecture:** core가 registration과 함께 opaque `LeaderLeaseExtensionObservationScope` capability를 만들고 wildcard/capability bucket으로 dispatch한다. Spring은 registry identity별 manager entry에서 capability를 공유하고 context-fixed owner를 AOP에 연결한다. AOP, lease adapter, watchdog는 실행 시작 시 capability를 캡처하며 attribution이 없는 direct call은 automatic observer에서 제외한다.

**Tech Stack:** Kotlin/JVM, Kotlin Coroutines, Reactor/Flow bridge, Spring Boot auto-configuration, Micrometer Observation, JUnit 5, MockK, Kluent/bluetape assertions, kotlinx-benchmark/JMH, Gradle.

---

## 파일 구조와 책임

| 파일 | 책임 |
|---|---|
| `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObservationScope.kt` | opaque capability, `ThreadLocal` save/restore, cached coroutine context element, revoke 상태 |
| `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserver.kt` | wildcard/capability bucket registration, matching-only admission과 dispatch |
| `leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt` | 현재 capability로 USER event allocation/publish |
| `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt` | `start()` 시 capability capture 후 WATCHDOG event publish |
| `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaderElectorLeaseAdapter.kt` | virtual-thread 경계 scope capture/restore |
| `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/SuspendLeaderElectorLeaseAdapter.kt` | IO coroutine 경계 scope context 결합 |
| `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObservationScopeTest.kt` | nested/coroutine/revoke/capability identity 계약 |
| `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserversTest.kt` | wildcard/scoped matching, saturation/drop, close/reopen 회귀 |
| `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionApiContractTest.kt` | 기존 및 additive synthetic descriptor, 5-인자 event ABI |
| `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionBoundaryContractTest.kt` | USER/WATCHDOG blocking/suspend/virtual 경계 |
| `leader-core/src/test/kotlin/io/bluetape4k/leader/internal/LeaderElectorLeaseAdapterTest.kt` | adapter thread/coroutine scope 전파와 direct-call 제외 |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaseExtensionObservationScopeOwner.kt` | context-fixed, one-shot capability activation과 close 후 fail-closed |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaseExtensionObservationRegistrationManager.kt` | registry identity entry, canonical capability와 ref-count |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfiguration.kt` | owner bean 생성, late coordinator activation과 shutdown |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderBeanSelector.kt` | 현재 BeanFactory의 fixed owner bean 조회 |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt` | 기존 factory descriptor를 유지하며 aspect에 owner 연결 |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt` | single sync/suspend/Mono/Flux/Flow 실행 scope |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderGroupElectionAspect.kt` | group sync/suspend/Mono 실행 scope와 Flux/Flow 기존 거부 유지 |
| `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaseExtensionObservationRegistrationManagerTest.kt` | distinct/same registry, close order, option conflict, stale scope |
| `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfigurationTest.kt` | owner/coordinator 조건과 parent/child registry 선택 |
| `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderLeaseExtensionObservationScopeAspectTest.kt` | aspect별 execution model, fail-open/reentrant/cancellation/close race |
| `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/compatibility/PublicJvmAbiCompatibilityTest.kt` | 기존 aspect/auto-config JVM descriptor 보존 |
| `benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/SpringLeaderAdviceBenchmark.kt` | scope match/mismatch/global의 throughput, average time, allocation evidence |
| `docs/benchmarks/2026-08-29-issue-741-spring-observation-scope.md` | exact baseline/candidate SHA, JSON 경로, fork median과 allocation 판정 |
| root/Spring `README.md`, `README.ko.md` | global/automatic 경계, 지원 행렬, migration, rollout/rollback |
| `docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.{en,ko}.md` | 미출시 #741 delta와 운영 절차 |

## 수용 기준 추적

| Spec 기준 | 구현/테스트 task |
|---|---|
| global observer와 5-인자 event ABI | Task 1, 2 |
| A/B USER/WATCHDOG cross-delivery 0 | Task 3, 6, 7 |
| identity opt-in 상대 registry 0 | Task 7 |
| same-registry parent/child ref-count | Task 5, 7 |
| A→B/B→A close, revoke/reopen | Task 2, 5, 7 |
| sync/suspend/reactive/watchdog/adapter 전파 | Task 3, 4, 6 |
| direct call와 Reactor operator fail-closed | Task 6, 7, 9 |
| caller-owned scope 의미·수명·Java 제약 | Task 1, 6, 9 |
| indexed dispatch/admission/drop/performance | Task 2, 8 |
| rollout/rollback/shutdown/manual provenance | Task 9 |
| module tests, detekt, ABI, manual, diff | Task 10 |

### Task 1: Opaque scope capability와 JVM ABI를 RED/GREEN으로 고정

**Complexity:** Medium. Task 2~7의 선행 조건이다.

**Files:**
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObservationScope.kt`
- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObservationScopeTest.kt`
- Modify: `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionApiContractTest.kt`

- [ ] **Step 1: capability 생성·nested restore·coroutine 전파·revoke RED test를 작성한다.**

```kotlin
@Test
fun `scope is restored after nested blocking and coroutine boundaries`() = runTest {
    val outer = LeaderLeaseExtensionObservers.addScopedObserver { }
    val inner = LeaderLeaseExtensionObservers.addScopedObserver { }
    outer.use {
        inner.use {
            outer.withScope {
                LeaderLeaseExtensionObservationScope.currentOrNull() shouldBeSameInstanceAs outer
                inner.withScope {
                    LeaderLeaseExtensionObservationScope.currentOrNull() shouldBeSameInstanceAs inner
                }
                LeaderLeaseExtensionObservationScope.currentOrNull() shouldBeSameInstanceAs outer
            }
            withContext(outer.asContextElement() + Dispatchers.Default) {
                LeaderLeaseExtensionObservationScope.currentOrNull() shouldBeSameInstanceAs outer
            }
        }
    }
    LeaderLeaseExtensionObservationScope.currentOrNull().shouldBeNull()
}
```

- [ ] **Step 2: RED를 확인한다.**

Run: `./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtensionObservationScopeTest' --rerun-tasks`

Expected: compile FAIL because scope class/factory does not exist.

- [ ] **Step 3: 최소 capability를 구현한다.**

```kotlin
class LeaderLeaseExtensionObservationScope private constructor(
    internal val observer: LeaderLeaseExtensionObserver,
    private val closeAction: (LeaderLeaseExtensionObservationScope) -> Unit,
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val contextElement by lazy(LazyThreadSafetyMode.PUBLICATION) { scopes.asContextElement(this) }

    @JvmSynthetic
    fun <T> withScope(block: () -> T): T = withOptionalScope(if (active.get()) this else null, block)

    @JvmSynthetic
    fun asContextElement(): ThreadContextElement<LeaderLeaseExtensionObservationScope?> = contextElement

    override fun close() {
        if (active.compareAndSet(true, false)) closeAction(this)
    }

    internal fun isActive(): Boolean = active.get()

    companion object {
        private val scopes = ThreadLocal<LeaderLeaseExtensionObservationScope?>()
        internal fun currentOrNull(): LeaderLeaseExtensionObservationScope? = scopes.get()?.takeIf { it.isActive() }
        internal fun create(
            observer: LeaderLeaseExtensionObserver,
            closeAction: (LeaderLeaseExtensionObservationScope) -> Unit,
        ) = LeaderLeaseExtensionObservationScope(observer, closeAction)
    }
}
```

Implementation keeps constructor private, caches one context element, restores nested values, and makes a revoked current scope read as `null`. `LeaderLeaseExtensionObservationScope.currentOrNull()` is core `internal`; there is no public ambient-scope accessor that a callback can use to retain another registration's capability. Add Korean KDoc to the public Kotlin handle/factory: caller-owned scope reaches only its own observer, has no Spring registry affiliation, must be closed, and is hidden from Java source by `@JvmSynthetic`.

- [ ] **Step 4: API contract를 additive exact signatures로 갱신한다.**

Preserve non-synthetic methods `addObserver`, `removeObserver`, `droppedCount`; preserve old `hasObservers()` and `publish(event)` synthetic descriptors; assert new `addScopedObserver`, `hasObservers(scope)`, `publish(event, scope)`, scope private constructor and event 5-arg constructor. Also assert that no public `current()`/ambient capability accessor exists. Add a `JavaCompiler` negative fixture that attempts to call `addScopedObserver`, `withScope`, `asContextElement`, scoped `hasObservers` and `publish`; compilation must fail because the methods are `ACC_SYNTHETIC`. Confirm flags with `javap -v` in the ABI evidence.

- [ ] **Step 5: GREEN과 ABI check를 확인한다.**

Run: `./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtensionObservationScopeTest' --tests '*LeaderLeaseExtensionApiContractTest' --rerun-tasks`

Expected: both test classes PASS, no scope value in `toString()` or reflection-visible constructor.

Rollback: remove the new class and restore the exact API test before Task 2 if descriptor shape is not implementable without breaking old methods.

### Task 2: Indexed scoped dispatch와 admission accounting 구현

**Complexity:** High. Concurrency/hot-path risk; `$bluetape-kotlin-patterns`, `$test-driven-development` and performance/stability scan required.

**Files:**
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserver.kt`
- Modify: `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserversTest.kt`

- [ ] **Step 1: wildcard/scoped matching과 saturation RED tests를 추가한다.**

```kotlin
@Test
fun `scoped observers receive only their capability and wildcard receives all`() {
    val globalEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
    val aEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
    val bEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
    val global = LeaderLeaseExtensionObservers.addObserver(globalEvents::add)
    val a = LeaderLeaseExtensionObservers.addScopedObserver(aEvents::add)
    val b = LeaderLeaseExtensionObservers.addScopedObserver(bEvents::add)
    global.use { a.use { b.use {
        LeaderLeaseExtensionObservers.publish(testEvent(), a)
        awaitCondition { globalEvents.size == 1 && aEvents.size == 1 }
        bEvents.shouldBeEmpty()
    } } }
}
```

Add a deterministic dispatcher/barrier test that fills 1024 global permits, publishes A and B concurrently, and asserts mismatch traffic does not change B callback count or matched-only drop delta. Add close/publish linearization, repeated-close idempotency, and weak-reference collection tests so a closed capability bucket cannot remain reachable from the dispatcher.

- [ ] **Step 2: RED를 확인한다.**

Run: `./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtensionObserversTest' --rerun-tasks`

Expected: new scoped tests fail before indexed registration exists.

- [ ] **Step 3: wildcard와 capability bucket을 최소 구현한다.**

```kotlin
private val wildcardRegistrations = CopyOnWriteArrayList<Registration>()
private val scopedRegistrations = ConcurrentHashMap<LeaderLeaseExtensionObservationScope, CopyOnWriteArrayList<Registration>>()

@JvmSynthetic
fun addScopedObserver(observer: LeaderLeaseExtensionObserver): LeaderLeaseExtensionObservationScope {
    lateinit var registration: Registration
    val scope = LeaderLeaseExtensionObservationScope.create(observer) { closedScope ->
        scopedRegistrations.remove(closedScope)?.clear()
        registration.closed.set(true)
    }
    registration = Registration(observer, scope)
    scopedRegistrations.computeIfAbsent(scope) { CopyOnWriteArrayList() }.add(registration)
    return scope
}

@JvmSynthetic
fun hasObservers(scope: LeaderLeaseExtensionObservationScope?): Boolean =
    wildcardRegistrations.isNotEmpty() || (scope?.takeIf { it.isActive() }?.let(scopedRegistrations::get)?.isNotEmpty() == true)
```

Dispatch wildcard bucket contents first and only `scopedRegistrations[scope]` when active. Saturation records drops only for the two selected bucket contents. Scope close linearizes once as `active=false` → identity bucket removal → `registration.closed=true`; callbacks already accepted before that point may finish, while no new scoped callback is admitted after revocation. Repeated close is a no-op.

`removeObserver(observer)`는 wildcard와 모든 capability bucket에서 동일 object identity registration을 제거하고 해당 scoped capability를 revoke한다. 마지막 registration이 제거된 빈 bucket은 map에서 제거해 lifecycle leak을 막는다.

- [ ] **Step 4: legacy remove/close/callback error/race tests를 유지하며 GREEN을 확인한다.**

Run: `./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtensionObserversTest' --tests '*LeaderLeaseExtensionApiContractTest' --rerun-tasks`

Expected: existing and new tests PASS, `droppedCount()` delta excludes mismatch registrations.

Rollback: revert Task 2 only; Task 1 capability can remain unused while the previous global facade is restored.

### Task 3: USER, WATCHDOG, lease adapter scope 전파

**Complexity:** High. Thread/coroutine/lifecycle ordering risk.

**Files:**
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt`
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt`
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaderElectorLeaseAdapter.kt`
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/SuspendLeaderElectorLeaseAdapter.kt`
- Modify: `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionBoundaryContractTest.kt`
- Modify: `leader-core/src/test/kotlin/io/bluetape4k/leader/internal/LeaderElectorLeaseAdapterTest.kt`

- [ ] **Step 1: USER/WATCHDOG/adapter RED matrix를 추가한다.**

Cover blocking, suspend, async virtual watchdog, suspend watchdog, blocking adapter virtual thread and suspend adapter IO coroutine. Each case registers global/A/B, installs A, then asserts global=1, A=1, B=0. Direct adapter outside scope must produce global=1 and A/B=0.

- [ ] **Step 2: RED를 확인한다.**

Run: `./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtensionBoundaryContractTest' --tests '*LeaderElectorLeaseAdapterTest' --rerun-tasks`

Expected: scoped observers miss async boundaries or both registries receive before implementation.

- [ ] **Step 3: producer scope를 한 번 캡처해 전달한다.**

```kotlin
val observationScope = LeaderLeaseExtensionObservationScope.currentOrNull()
if (LeaderLeaseExtensionObservers.hasObservers(observationScope)) {
    // 이 분기 안에서만 timer/context/event를 만든다.
    LeaderLeaseExtensionObservers.publish(event, observationScope)
}
```

USER helper와 WATCHDOG tick은 `hasObservers(observationScope) == false`이면 timer/context/event를 만들기 전에 observation path를 반환한다. 실제 lease extension 결과/예외 흐름은 그대로 계속한다. `LeaderLeaseAutoExtender.start()` stores `observationScope` beside captured admission. Blocking adapter wraps the virtual-thread body with `observationScope?.withScope { ... }`; suspend adapter combines `observationScope?.asContextElement()` with admission context in the launched coroutine.

- [ ] **Step 4: close/reopen와 cancellation GREEN을 확인한다.**

Add tests that revoke A while an action/tick is blocked, open new A2, release the old action, and assert old automatic callbacks are 0 for A2 while global remains 1. Verify cancellation propagates and ThreadLocal is cleared.

Run: `./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtension*' --tests '*LeaderElectorLeaseAdapterTest' --rerun-tasks`

Expected: PASS with no skipped tests.

Rollback: revert producer wiring together; do not leave only USER or only WATCHDOG scoped.

### Task 4: Spring context owner와 registry manager canonical capability

**Complexity:** High. Same-registry ref-count and startup lifecycle risk.

**Files:**
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaseExtensionObservationScopeOwner.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaseExtensionObservationRegistrationManager.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaseExtensionObservationRegistrationManagerTest.kt`

- [ ] **Step 1: manager RED tests를 수정한다.**

Change the defect expectation so distinct registry B receives zero A event. Add same-registry shared scope identity, A→B/B→A close, conflict message, last-close revoke, and close/reopen new capability tests.

- [ ] **Step 2: RED를 확인한다.**

Run: `./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaseExtensionObservationRegistrationManagerTest' --rerun-tasks`

Expected: current manager broadcasts to both registries and has no scope handle.

- [ ] **Step 3: canonical entry와 context handle을 구현한다.**

```kotlin
internal data class ManagedRegistration(
    val scope: LeaderLeaseExtensionObservationScope,
    val closeHandle: AutoCloseable,
) : AutoCloseable by closeHandle

private class Entry(
    val options: LeaderObservationOptions,
    val scope: LeaderLeaseExtensionObservationScope,
    var referenceCount: Int = 1,
)
```

First acquire creates Micrometer observer and `addScopedObserver`; later same-identity acquire returns the same scope with a new idempotent context handle. Last release closes/revokes the core scope. Conflict message instructs option alignment or `bluetape4k.leader.observability.tracing.enabled=false` without registry `toString()`.

- [ ] **Step 4: one-shot owner를 구현한다.**

```kotlin
internal class LeaseExtensionObservationScopeOwner(
    val registry: ObservationRegistry,
) {
    private val scope = AtomicReference<LeaderLeaseExtensionObservationScope?>()
    fun activate(value: LeaderLeaseExtensionObservationScope) {
        check(scope.compareAndSet(null, value)) { "Lease extension observation scope is already active" }
    }
    fun current(): LeaderLeaseExtensionObservationScope? = scope.get()?.takeIf { it.isActive() }
    fun clear(expected: LeaderLeaseExtensionObservationScope) { scope.compareAndSet(expected, null) }
}
```

- [ ] **Step 5: GREEN을 확인한다.**

Run: `./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaseExtensionObservationRegistrationManagerTest' --rerun-tasks`

Expected: distinct registry isolation, same-registry single callback/ref-count, both close orders and reopen PASS.

Rollback: restore manager global registration and remove owner; do not proceed to AOP wiring if lifecycle tests fail.

### Task 5: Observation auto-configuration과 fixed owner wiring

**Complexity:** Medium. Auto-configuration condition/order and JVM factory compatibility risk.

**Files:**
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfiguration.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderBeanSelector.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfigurationTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderBeanSelectorTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/compatibility/PublicJvmAbiCompatibilityTest.kt`

- [ ] **Step 1: auto-config RED tests를 추가한다.**

Assert owner bean exists only when normal non-NOOP registry and tracing are enabled, selected `@Primary` registry activates the owner, parent/child same registry shares core scope, distinct registries get distinct owners, disabled/NOOP has no active owner, and manual aspect construction remains no-op. Build real parent/child application contexts, assert selector object identity is distinct and each selector holds its own context `BeanFactory`, invoke annotated methods concurrently in both contexts, and verify that a child with only a parent owner remains automatic-observation no-op.

- [ ] **Step 2: RED를 확인한다.**

Run: `./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderObservationAutoConfigurationTest' --tests '*LeaderBeanSelectorTest' --tests '*PublicJvmAbiCompatibilityTest' --rerun-tasks`

Expected: owner bean/lookup assertions fail; old aspect/coordinator descriptor assertions continue PASS.

- [ ] **Step 3: fixed bean-name lookup과 coordinator activation을 구현한다.**

Define one internal bean name constant. Declare the `leaderBeanSelector`, owner, and aspect bean methods with `@ConditionalOnMissingBean(search = SearchStrategy.CURRENT)` so each application context owns its local selector/wiring. `LeaderBeanSelector` casts to `HierarchicalBeanFactory`, checks `containsLocalBean(fixedName)`, then calls `getBean(fixedName, LeaseExtensionObservationScopeOwner::class.java)` only after that local check; it never falls back to a parent owner. Preserve the existing public three-argument `leaseExtensionObserverRegistrationCoordinator(ConfigurableListableBeanFactory, LeaderProperties, LeaderAopProperties)` descriptor exactly; its body/coordinator resolves the fixed-name local owner without adding a factory parameter, calls `manager.acquire(owner.registry, options)`, activates with `managed.scope`, registers close handle, and clears owner before releasing the context handle during destroy.

- [ ] **Step 4: existing factory signatures를 유지하며 aspect property를 연결한다.**

Both annotated and legacy factory methods construct aspects with the same existing arguments, then apply an internal nullable owner property. Do not add constructor or factory parameters. Add ABI assertions for the existing coordinator three-argument descriptor as well as the existing single/group aspect factory descriptors.

- [ ] **Step 5: GREEN과 ABI를 확인한다.**

Run: `./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderObservationAutoConfigurationTest' --tests '*LeaderBeanSelectorTest' --tests '*PublicJvmAbiCompatibilityTest' --rerun-tasks`

Expected: all tests PASS and previous 5/6-arg aspect plus 3-arg coordinator descriptors remain present.

Rollback: remove owner bean/lookup and restore coordinator signature; manager/core changes remain isolated but unused by Spring.

### Task 6: Single/group AOP execution scope TDD

**Complexity:** High. Sync, coroutine, Reactor, Flow, reentrant, fail-open and cancellation paths.

**Files:**
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderGroupElectionAspect.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderLeaseExtensionObservationScopeAspectTest.kt`
- Reuse: existing aspect tests named in the file map

- [ ] **Step 1: execution-model RED matrix를 작성한다.**

For `LeaderElectionAspect`: sync, suspend, Mono, Flux, Flow, fail-open, reentrant. For group: sync, suspend, Mono; assert Flux/Flow existing validation error happens before scope/event. Each supported case publishes a USER event and asserts current context registry 1, other registry 0, global 1.

- [ ] **Step 2: cancellation/exception/Reactor negative RED cases를 추가한다.**

Cancel each suspend/reactive path and throw from action, then reuse the thread and assert current scope null. A Reactor `map` direct extender call outside coroutine continuation must automatic=0/global=1. Close context during active action and assert post-revoke automatic=0/global=1. From an A callback, verify there is no public ambient accessor to retain A's canonical capability; retaining and later reinstalling a separately registered caller-owned capability must notify only that caller-owned observer, never A or B automatic observers.

- [ ] **Step 3: RED를 확인한다.**

Run: `./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderLeaseExtensionObservationScopeAspectTest' --rerun-tasks`

Expected: current aspect does not install owner scope.

- [ ] **Step 4: 최소 scope wrapper를 모든 supported action 경계에 적용한다.**

```kotlin
private fun <T> withObservationScope(block: () -> T): T =
    observationScopeOwner?.current()?.withScope(block) ?: block()

private suspend fun <T> withObservationScopeSuspend(block: suspend () -> T): T {
    val scope = observationScopeOwner?.current() ?: return block()
    return withContext(scope.asContextElement()) { block() }
}
```

Wrap actual elector/action execution, including reentrant/fail-open action. Do not wrap metadata-resolution bypass. For Flux/Flow, install scope at subscription/collection coroutine bridge, not per signal.

- [ ] **Step 5: GREEN과 existing aspect suite를 확인한다.**

Run: `./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderElectionAspect*' --tests '*LeaderGroupElectionAspect*' --tests '*LeaderLeaseExtensionObservationScopeAspectTest' --rerun-tasks`

Expected: all supported model, cleanup, negative and existing tests PASS with no skips.

Rollback: revert both aspect files together so single/group parity is never partial.

Spring partial rollback is not a supported steady state:

| Failed slice | Required rollback unit | Smoke evidence |
|---|---|---|
| Task 4 manager/owner | restore global manager registration and remove owner together | explicit global observer still receives one event; automatic observation matches pre-change behavior |
| Task 5 auto-configuration | revert owner bean, coordinator activation, and manager scoped registration together, or set `bluetape4k.leader.observability.tracing.enabled=false` | disabled context starts cleanly and explicit global observer remains functional |
| Task 6 AOP scope | revert both single/group aspect wiring plus Spring scoped registration, or disable tracing for the affected context | no owner-only/scoped-registration-only state; automatic telemetry is either fully old-path or intentionally off |

The rollback smoke test must cover application startup, one explicit wildcard callback, and zero silent automatic observations from a partially wired owner.

### Task 7: Cross-context integration과 raw-error boundary 검증

**Complexity:** High. Security/lifecycle integration gate.

**Files:**
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaseExtensionObservationRegistrationManagerTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfigurationTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderLeaseExtensionObservationScopeAspectTest.kt`

- [ ] **Step 1: two-context integration fixture를 완성한다.**

Create registry A/B with capturing observation handlers and `includeLockName=true`, `includeLeaderId=true`, `includeExceptionDetails=true`. Run A and B events in both directions and assert own identity once, other identity zero.

- [ ] **Step 2: lifecycle matrix를 실행한다.**

Test A→B and B→A close order, same-registry parent/child single callback/ref-count, distinct parent/child concurrent calls, conflict startup recovery message, close/reopen stale action/watchdog and accepted-late callback semantics. Add shutdown smoke with a blocked accepted callback: context registration close returns without an internal drain wait, new automatic admission is zero, and releasing the callback while the exporter is still alive may complete under the existing in-flight contract.

- [ ] **Step 3: raw-error security boundary를 고정한다.**

With default/`includeExceptionDetails=false`, publish `IllegalStateException("jdbc:password=secret-token")` from A and assert no raw throwable/error payload is created or exported. Then opt in with `includeExceptionDetails=true`: assert A handler gets the existing raw throwable, B gets zero observation/error, and capability plus secret payload are absent from tags, logs, and non-error representations. The opted-in source handler may receive the raw throwable by existing contract.

- [ ] **Step 4: targeted integration GREEN을 확인한다.**

Run: `./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaseExtensionObservation*' --tests '*LeaderLeaseExtensionObservationScopeAspectTest' --rerun-tasks`

Expected: A↔B cross-delivery and cross-identity counts are all zero; no skipped tests.

Rollback: return to Task 4/6 depending on whether manager delivery or AOP attribution fails; do not weaken expected zero counts.

### Task 8: Performance와 stability evidence

**Complexity:** Medium. Benchmark is non-CI evidence; no new dependency/module.

**Files:**
- Modify: `benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/SpringLeaderAdviceBenchmark.kt`
- Create: `docs/benchmarks/2026-08-29-issue-741-spring-observation-scope.md`

- [ ] **Step 1: benchmark states를 추가한다.**

Add no-observer, global, scoped-match and scoped-mismatch states for sync/suspend/Mono/Flux/Flow plus watchdog publish. Reuse one scope/context element per trial; do not allocate registration per invocation. Use the generated JMH jar with an explicit `-f 3`; leave the repository-wide `main`/`averageTime` fork settings unchanged.

- [ ] **Step 2: benchmark compile을 확인한다.**

Run: `./gradlew :benchmark:compileBenchmarkKotlin --no-configuration-cache`

Expected: PASS with no benchmark API/type errors.

- [ ] **Step 3: before/after comparable benchmark를 실행하고 JSON을 기록한다.**

Run the comparable existing advice rows at detached baseline `f44b7c69440f8ce5156185ce63209f523b2051fd`, then run the same exact filter at the final candidate head. Use the generated JMH jar with `-f 3 -prof gc` and write artifacts to:

- `benchmark/build/reports/benchmarks/issue741/baseline-f44b7c6-throughput.json`
- `benchmark/build/reports/benchmarks/issue741/baseline-f44b7c6-average-time.json`
- `benchmark/build/reports/benchmarks/issue741/candidate-<exact-head>-throughput.json`
- `benchmark/build/reports/benchmarks/issue741/candidate-<exact-head>-average-time.json`

The command shape is `java -jar <benchmark-jmh-jar> '<exact SpringLeaderAdviceBenchmark row filter>' -f 3 -wi 2 -i 3 -r 1s -prof gc -rf json -rff <artifact>`, with `-bm thrpt -tu s` and `-bm avgt -tu us` runs. Record host/JDK/load, exact SHA, row filter, and all four paths in the result document. For each comparable row, calculate the median of the three fork-level `rawData` values; throughput regression is `(baselineMedian - candidateMedian) / baselineMedian`, average-time regression is `(candidateMedian - baselineMedian) / baselineMedian`.

Expected: all four JSON reports exist; the candidate-only no-observer USER/WATCHDOG rows have `gc.alloc.rate.norm` equal to their no-instrumentation control within JMH measurement resolution and show no event/context/timer allocation, while comparable throughput/average-time fork medians regress by no more than 15%. If environment noise exceeds 15%, rerun once with the same JVM/load and keep both result sets; persistent regression returns to Task 2/6.

- [ ] **Step 4: performance/stability scan을 기록한다.**

Confirm matching-only bucket traversal, no per-signal context creation, no blocking call added to reactive paths, capability buckets removed on close, scheduled/coroutine resources unchanged.

Rollback: remove benchmark cases only if they cannot represent the real path; implementation rollback is required for persistent measured regression.

### Task 9: EN/KO docs, migration, rollout/rollback and manual draft

**Complexity:** Medium. Public behavior and operations documentation.

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `leader-spring-boot/README.md`
- Modify: `leader-spring-boot/README.ko.md`
- Modify: `docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.en.md`
- Modify: `docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.ko.md`

- [ ] **Step 1: source behavior가 green인 뒤 문서를 갱신한다.**

Document global explicit vs registry-scoped automatic observer, same-registry parent/child, distinct registry ownership, direct-call and Reactor non-suspend fail-closed behavior, single/group support matrix, manual+automatic duplicate warning and migration example. Add an EN/KO migration matrix: AOP automatic is registry-scoped; direct/Reactor non-suspend is automatic 0 and explicit global 1; caller-owned scope reaches only its own observer, cannot impersonate a Spring registry, must be closed, and is unavailable to Java source because the bridge is `@JvmSynthetic`.

- [ ] **Step 2: 운영 절차를 EN/KO에 맞춘다.**

Include canary A/B zero-cross-count, `bluetape4k.leader.observability.tracing.enabled=false` as an `ApplicationContext` startup-only setting that requires context/process restart, post-restart local owner absent/automatic 0/global 1 smoke, binary rollback semantics, explicit observer close, graceful shutdown order, no internal drain wait, accepted-callback delivery caveat, scope-excluded/no-observer/admission-drop diagnosis and raw exception exporter responsibility.

- [ ] **Step 3: manual provenance를 유지한다.**

Update only #559 drafts for the unshipped #741 delta. Do not change `docs/manual/manifest.yaml` releaseRef `0.5.0` or releaseCommit `721a9a3808f67489d2bdb8177734325981c24977` and do not claim the change in versioned manual pages.

- [ ] **Step 4: locale/naturalness and manual validation을 실행한다.**

Run:

```bash
node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs README.ko.md leader-spring-boot/README.ko.md docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.ko.md
./gradlew exportManualModuleInventory
ruby scripts/manual/release_inventory.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977 build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977
ruby scripts/manual/export_manifest.rb --check
ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
```

Expected: terminology findings fixed or explicitly classified, all manual commands PASS, manifest unchanged.

Rollback: revert all six locale files together if source behavior changes; never leave EN/KO contracts divergent.

### Task 10: Full verification, review, lesson and delivery readiness

**Complexity:** High verification breadth, no new architecture.

**Files:**
- Create later: `docs/review/2026-08-29-issue-741-spring-observation-scope-review.md`
- Create later: `docs/lessons/2026-08-29-issue-741-spring-observation-scope.md`
- Review: all branch changes against `origin/develop`

- [ ] **Step 1: targeted then module validation을 실행한다.**

```bash
./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtension*' --tests '*LeaderElectorLeaseAdapterTest' --rerun-tasks
./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaseExtensionObservation*' --tests '*LeaderElectionAspect*' --tests '*LeaderGroupElectionAspect*' --rerun-tasks
./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-spring-boot:test
```

Expected: all tasks SUCCESS, JUnit executed count recorded, skipped=0.

- [ ] **Step 2: static/ABI/docs checks를 실행한다.**

```bash
./gradlew detekt
./gradlew checkBinaryCompatibility
git diff --check
git status --short
```

Expected: PASS; status contains only approved files.

- [ ] **Step 2a: benchmark evidence와 exact-head CI 기록을 고정한다.**

Verify `docs/benchmarks/2026-08-29-issue-741-spring-observation-scope.md` exists and records baseline SHA `f44b7c69440f8ce5156185ce63209f523b2051fd`, candidate exact head, all four JSON paths, three-fork medians, `gc.alloc.rate.norm`, and the 15% decision. Before final DoD, record the exact remote head and the URL/status of every exact-head CI job; path-filtered skips must be identified separately from executed success.

- [ ] **Step 3: spec/plan verifier와 six-lens pre-PR review를 완료한다.**

Map every spec acceptance row to source/tests/docs/commands. Run Performance, Stability, Security, Operator/Ops, Developer/API, User/caller independent read-only reviews, integrate at P0=0/P1=0, fix blockers and rerun affected lanes.

- [ ] **Step 4: durable lesson을 작성·검증·커밋한다.**

Lesson records why global facade stays wildcard, why capability is registration-owned, Reactor/direct-call fail-closed boundary, admission indexing, late callback/revoke behavior, validation evidence and future guard. Complete SPW-01~05 before commit.

- [ ] **Step 5: Lore commits and PR delivery를 완료한다.**

Commit spec/plan before implementation, then RED/GREEN implementation slices, docs/review/lesson. Commit messages are Korean intent lines with `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailers. Push `fix/issue-741-spring-observation-scope`, create Korean PR to `develop`, assign `debop`, mirror `bug`, `integration`, `security`, `spring`, milestone `1.0.0`, and end body with `## DoD Status`.

- [ ] **Step 6: exact-head CI와 live review gate를 확인한다.**

Verify remote head equals local head, required checks terminal `SUCCESS` or evidence-backed path N/A, reviews/threads 0 blockers, mergeability. Stop at CG-16 with merge unchecked; do not enable auto-merge or merge without fresh approval.

## Risk prediction

| Risk | Signal | Mitigation | Rollback/rerun |
|---|---|---|---|
| capability bucket close/publish race | stale callback reaches reopened registry | active flag + bucket removal + new capability per entry; close-race tests | Task 2/4 rerun |
| global cap still couples wildcard observer | scoped latency/drop rises under global callback load | document intentional wildcard sharing; matched-only accounting stress | Task 2 performance rerun |
| Reactor operator loses ThreadLocal | direct extension automatic count 0 | explicit unsupported boundary and negative test | Task 6; do not add lifter without design approval |
| aspect descriptor regression | reflection/ABI test fails | internal property wiring, no new factory args | Task 5 rollback |
| same registry parent/child duplicate | event count 2 or early close | manager identity entry/ref-count canonical scope | Task 4/7 rerun |
| benchmark noise | >15% inconsistent regression | same-JVM same-load one retry, record caveat | persistent result blocks review |
| raw Throwable exposes secret | source exporter sees raw message | opt-in warning/exporter redaction; assert other registry 0 | disable option; redaction remains separate issue |
| manual release drift | versioned manual claims unreleased behavior | draft-only edit; manifest exact check | revert docs task |

## Writer gate

- SPW-01: PASS — 독자는 구현자와 검증자이며 승인된 spec, current source/test, repo commands를 근거로 했다.
- SPW-02: PASS — dependency order, exact files, RED/GREEN, validation, docs, rollback, PR stop gate를 포함했다.
- SPW-03: PASS — KO-01~KO-06을 적용해 기술 식별자를 보존하고 모호한 지시를 제거했다. KO-07은 plan 파일에 대해 Task 9 실행 전에 수행한다.
- SPW-04: PASS — spec의 11개 acceptance 영역을 Task 1~10에 매핑하고 모든 P1 repair를 task/command로 연결했다.
- SPW-05: PASS — Markdown 구조, 코드 fence, task ordering, exact commands, `releaseRef`/commit, CG-16 stop 조건을 최종 read-back했다.

## Step DoD

- A-04 implementation plan draft: PASS
- Plan review 6-lane + integration: PASS — P0=0, P1=0
- Spec/plan commit: READY
- Implementation authorization: YES — approved design and converged plan
