# `leader-core` backend diagnostics probe base Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `leader-core`에 framework-neutral backend connectivity probe 실행 경계를 추가하고, 지정한 내장 provider와 Ktor/Spring adapter가 동일한 예외·timeout·상태 계약을 사용하도록 정렬한다.

**Architecture:** 새 `LeaderBackendDiagnosticsProbe` Kotlin `object`가 timeout 선검증, 단일 `checkedAt`, provider callback 실행, 상태 매핑, 예외 경계를 담당한다. 기존 `LeaderBackendDiagnosticsProvider`와 `checkConnectivity(Duration)` public surface는 유지하고, 내장 provider만 helper callback으로 migration한다. legacy custom override는 결과·예외 정책을 그대로 유지하며 Ktor/Spring adapter 회귀 테스트로 경계를 고정한다.

**Tech Stack:** Kotlin 2.4/JVM 25, Gradle, JUnit 5, MockK, Kluent-style Bluetape assertions, Ktor testApplication, Spring Boot ApplicationContextRunner, `io.bluetape4k.support.requireGt`, `java.time.Clock`.

---

## 파일 책임과 변경 지도

| 책임 | 파일 |
|---|---|
| 공통 helper | Create `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbe.kt` |
| 기존 SPI/KDoc와 공통 timeout 검증 | Modify `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt` |
| Local 내장 provider | Modify `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LocalLeaderBackendDiagnostics.kt` |
| client-backed provider | Modify the MongoDB, Lettuce, Redisson, Hazelcast, and ZooKeeper diagnostics source files |
| core helper/provider 회귀 | Create `leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbeTest.kt`; modify existing core diagnostics tests |
| provider 회귀 | Modify existing diagnostics test files under MongoDB/Lettuce/Redisson/Hazelcast/ZooKeeper modules |
| adapter 회귀 | Modify `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderBackendDiagnosticsRouteTest.kt` and `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendHealthIndicatorTest.kt` |
| public documentation | Modify `README.md`, `README.ko.md`, `leader-ktor/README.md`, `leader-ktor/README.ko.md`, `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md` |
| lesson/receipt evidence | Create the Issue #766 lesson and update the Type A workflow receipt; do not modify versioned `docs/manual` or `manifest.yaml` |

## Task 1: Core helper contract RED tests

**Files:**

- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbeTest.kt`
- Modify: `leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsTest.kt`

- [ ] **Step 1: Add the failing test class and fixed clock fixture.**

Use the repository assertion contract; do not introduce JUnit `assertThrows`,
`kotlin.test.assertFailsWith`, or `invoking { } shouldThrow`.

```kotlin
package io.bluetape4k.leader.diagnostics

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LeaderBackendDiagnosticsProbeTest {

    private val checkedAt = Instant.parse("2026-08-24T00:00:00Z")
    private val clock = Clock.fixed(checkedAt, ZoneOffset.UTC)

    @Test
    fun `UP DOWN UNKNOWN은 동일한 checkedAt으로 매핑된다`() {
        LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
            LeaderBackendConnectivityStatus.UP
        }.shouldBeEqualTo(LeaderBackendConnectivity.up(checkedAt))

        LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
            LeaderBackendConnectivityStatus.DOWN
        }.shouldBeEqualTo(LeaderBackendConnectivity.down(checkedAt))

        LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
            LeaderBackendConnectivityStatus.UNKNOWN
        }.shouldBeEqualTo(LeaderBackendConnectivity.unknown(checkedAt))
    }

    @Test
    fun `양수 유한 timeout이 아니면 clock과 callback을 호출하지 않는다`() {
        var clockCalls = 0
        var callbackCalls = 0
        val countingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): Instant {
                clockCalls++
                return checkedAt
            }
        }

        listOf(Duration.ZERO, (-1).milliseconds, Duration.INFINITE).forEach { timeout ->
            assertFailsWith<IllegalArgumentException> {
                LeaderBackendDiagnosticsProbe.check(timeout, countingClock) {
                    callbackCalls++
                    LeaderBackendConnectivityStatus.UP
                }
            }
        }

        clockCalls shouldBeEqualTo 0
        callbackCalls shouldBeEqualTo 0
    }

    @Test
    fun `일반 Exception은 UNKNOWN으로 정규화한다`() {
        val connectivity = LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
            throw IllegalStateException("provider failure")
        }

        connectivity shouldBeEqualTo LeaderBackendConnectivity.unknown(checkedAt)
    }

    @Test
    fun `CancellationException은 동일 인스턴스로 재전파한다`() {
        val cancellation = java.util.concurrent.CancellationException("cancelled")

        val thrown = assertFailsWith<java.util.concurrent.CancellationException> {
            LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) { throw cancellation }
        }

        thrown shouldBeSameInstanceAs cancellation
    }

    @Test
    fun `InterruptedException은 flag를 복원하고 동일 인스턴스로 재전파한다`() {
        Thread.interrupted()
        val interrupted = InterruptedException("interrupted")
        try {
            val thrown = assertFailsWith<InterruptedException> {
                LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) { throw interrupted }
            }

            thrown shouldBeSameInstanceAs interrupted
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `Error는 동일 인스턴스로 재전파한다`() {
        val fatal = AssertionError("fatal")

        val thrown = assertFailsWith<AssertionError> {
            LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) { throw fatal }
        }

        thrown shouldBeSameInstanceAs fatal
    }

    @Test
    fun `NOT_CHECKED callback 결과는 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendDiagnosticsProbe.check(100.milliseconds, clock) {
                LeaderBackendConnectivityStatus.NOT_CHECKED
            }
        }
    }

    @Test
    fun `clock은 callback보다 먼저 한 번 읽고 callback은 같은 timeout으로 한 번 실행한다`() {
        val events = mutableListOf<String>()
        var capturedTimeout: Duration? = null
        val callerThread = Thread.currentThread()
        val orderedClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): Instant {
                events += "clock"
                return checkedAt
            }
        }

        LeaderBackendDiagnosticsProbe.check(275.milliseconds, orderedClock) { timeout ->
            Thread.currentThread() shouldBeSameInstanceAs callerThread
            capturedTimeout = timeout
            events += "callback:$timeout"
            LeaderBackendConnectivityStatus.UP
        }

        events.first() shouldBeEqualTo "clock"
        events.size shouldBeEqualTo 2
        capturedTimeout shouldBeEqualTo 275.milliseconds
    }

    @Test
    fun `동시 호출은 helper 공유 상태 없이 각자의 timestamp와 결과를 만든다`() {
        val sequence = java.util.concurrent.atomic.AtomicLong()
        val results = java.util.concurrent.ConcurrentLinkedQueue<LeaderBackendConnectivity>()

        io.bluetape4k.junit5.concurrency.MultithreadingTester()
            .workers(8)
            .rounds(4)
            .add {
                val index = sequence.getAndIncrement()
                val expectedAt = checkedAt.plusMillis(index)
                val expectedStatus = if (index % 2L == 0L) {
                    LeaderBackendConnectivityStatus.UP
                } else {
                    LeaderBackendConnectivityStatus.DOWN
                }
                val actual = LeaderBackendDiagnosticsProbe.check(
                    (index + 1L).milliseconds,
                    Clock.fixed(expectedAt, ZoneOffset.UTC),
                ) { expectedStatus }

                actual shouldBeEqualTo when (expectedStatus) {
                    LeaderBackendConnectivityStatus.UP -> LeaderBackendConnectivity.up(expectedAt)
                    LeaderBackendConnectivityStatus.DOWN -> LeaderBackendConnectivity.down(expectedAt)
                    else -> error("unexpected test status: $expectedStatus")
                }
                results += actual
            }
            .run()

        results.size shouldBeEqualTo 32
        results.toSet().size shouldBeEqualTo 32
    }

    @Test
    fun `clock 실패는 callback 없이 동일 인스턴스로 전파한다`() {
        val failure = IllegalStateException("clock failure")
        val failingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): Instant = throw failure
        }
        var callbackCalls = 0

        val thrown = assertFailsWith<IllegalStateException> {
            LeaderBackendDiagnosticsProbe.check(100.milliseconds, failingClock) {
                callbackCalls++
                LeaderBackendConnectivityStatus.UP
            }
        }

        thrown shouldBeSameInstanceAs failure
        callbackCalls shouldBeEqualTo 0
    }
}
```

The order test intentionally checks order and call count without depending on a
locale-specific `Duration.toString()` rendering. Add a separate timeout capture
assertion against `275.milliseconds` so the callback receives the exact input.

- [ ] **Step 2: Add provider-level invalid-timeout and legacy-override regression
  to `LeaderBackendDiagnosticsTest`.**

Keep the existing `RecordingProvider` test and assert that `Duration.ZERO`, a
negative duration, and `Duration.INFINITE` reject before `checkConnectivity` is
entered. Add two explicit legacy-provider fixtures and a direct-call matrix:

- a provider overriding only `checkConnectivity(timeout)` must still receive
  base `diagnostics(probe = true, timeout = invalid)` prevalidation before its
  override is entered; for valid timeout, its returned `NOT_CHECKED` and its
  ordinary `Exception`, `CancellationException`, `InterruptedException`, and
  same-instance `Error` are returned/rethrown unchanged because custom code is
  outside the built-in helper;
- a provider overriding `diagnostics(probe, timeout)` must retain its existing
  full escape-hatch behavior, including bypassing base timeout prevalidation,
  returning `NOT_CHECKED`, and preserving the same exception instances for
  ordinary/cancellation/interruption/`Error` cases. Verify the timeout received
  by the override and clear the interrupt flag in `finally` around interruption
  cases.

The consumer smoke in Task 6 must compile equivalent source-only legacy
implementations for both override shapes, in addition to the new helper call.
Run:

```bash
./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbeTest' --tests 'io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsTest' --no-daemon --no-configuration-cache --max-workers=1 --console=plain
```

Expected before implementation: the new helper source fails to compile or the
tests fail because `LeaderBackendDiagnosticsProbe` does not exist. Do not write
production code until this RED result is recorded.

- [ ] **Step 3: Commit the RED tests.**

```bash
git add leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbeTest.kt leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsTest.kt
git commit -m "진단 probe 공통 경계의 실패 테스트를 먼저 고정한다"
```

## Task 2: Implement the stateless core helper

**Files:**

- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbe.kt`
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt`

- [ ] **Step 1: Implement the public Kotlin/JVM helper with the exact contract.**

Use `io.bluetape4k.support.requireGt` for the positive bound, then check
finiteness. Keep `Clock.instant()` outside the callback exception catch so clock
failures are not normalized. Catch `Exception` only after cancellation and
interruption; never use `runCatching`, which would hide `Error`.

```kotlin
public object LeaderBackendDiagnosticsProbe {
    public fun check(
        timeout: Duration,
        clock: Clock = Clock.systemUTC(),
        probe: (Duration) -> LeaderBackendConnectivityStatus,
    ): LeaderBackendConnectivity {
        val validTimeout = timeout.requirePositiveFiniteProbeTimeout()
        val checkedAt = clock.instant()
        val status = try {
            probe(validTimeout)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            return LeaderBackendConnectivity.unknown(checkedAt)
        }

        return when (status) {
            LeaderBackendConnectivityStatus.UP -> LeaderBackendConnectivity.up(checkedAt)
            LeaderBackendConnectivityStatus.DOWN -> LeaderBackendConnectivity.down(checkedAt)
            LeaderBackendConnectivityStatus.UNKNOWN -> LeaderBackendConnectivity.unknown(checkedAt)
            LeaderBackendConnectivityStatus.NOT_CHECKED ->
                throw IllegalArgumentException("probe callback must return a checked connectivity status")
        }
    }
}
```

Add KDoc stating that the helper is Kotlin/JVM-facing, stateless, synchronous,
provider-native-budget-only, and does not create I/O, locks, clients, threads,
or a wall-clock deadline. Document ordinary `Exception -> UNKNOWN`,
`CancellationException`/`InterruptedException` preservation, and fatal `Error`
rethrow.

- [ ] **Step 2: Move the shared timeout extension and update the SPI KDoc.**

Replace the current compound predicate in `LeaderBackendDiagnostics.kt` with:

```kotlin
internal fun Duration.requirePositiveFiniteProbeTimeout(): Duration {
    val validTimeout = requireGt(Duration.ZERO, "probe timeout")
    require(validTimeout.isFinite()) { "probe timeout must be finite: $validTimeout" }
    return validTimeout
}
```

Update “주어진 timeout 안에서” to “provider-native budget으로 전달된 timeout을
사용한 bounded read-only 검사”. Keep `diagnostics(probe = true)` prevalidation
before the virtual `checkConnectivity` call so legacy custom overrides retain the
existing invalid-timeout guard. Change the default `checkConnectivity` body to
call the helper with an `UNKNOWN` callback; preserve all public signatures.

- [ ] **Step 3: Run core tests GREEN and commit.**

```bash
./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbeTest' --tests 'io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsTest' --no-daemon --no-configuration-cache --max-workers=1 --console=plain
```

Expected: `BUILD SUCCESSFUL`; all selected tests pass.

```bash
git add leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbe.kt leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt
git commit -m "leader-core에 공통 diagnostics probe 경계를 추가한다"
```

## Task 3: Migrate Local and client-backed providers

**Files:**

- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LocalLeaderBackendDiagnostics.kt`
- Modify: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderBackendDiagnostics.kt`
- Modify: `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceLeaderBackendDiagnostics.kt`
- Modify: `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/RedissonLeaderBackendDiagnostics.kt`
- Modify: `leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderBackendDiagnostics.kt`
- Modify: `leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperLeaderBackendDiagnostics.kt`

- [ ] **Step 1: Replace each provider's timeout/clock wrapper with the helper callback.**

Use these callback mappings and remove each provider-private
`requirePositiveFiniteProbeTimeout` extension and unused `Clock` import:

```kotlin
// Local
LeaderBackendDiagnosticsProbe.check(timeout) { LeaderBackendConnectivityStatus.UP }

// MongoDB
LeaderBackendDiagnosticsProbe.check(timeout) { LeaderBackendConnectivityStatus.UNKNOWN }

// Lettuce
LeaderBackendDiagnosticsProbe.check(timeout) {
    if (connection.isOpen) LeaderBackendConnectivityStatus.UNKNOWN
    else LeaderBackendConnectivityStatus.DOWN
}

// Redisson
LeaderBackendDiagnosticsProbe.check(timeout) {
    if (redissonClient.isShutdown || redissonClient.isShuttingDown) {
        LeaderBackendConnectivityStatus.DOWN
    } else {
        LeaderBackendConnectivityStatus.UNKNOWN
    }
}

// Hazelcast
LeaderBackendDiagnosticsProbe.check(timeout) {
    if (hazelcast.lifecycleService.isRunning) LeaderBackendConnectivityStatus.UNKNOWN
    else LeaderBackendConnectivityStatus.DOWN
}

// ZooKeeper
LeaderBackendDiagnosticsProbe.check(timeout) {
    if (client.zookeeperClient.isConnected) LeaderBackendConnectivityStatus.UP
    else LeaderBackendConnectivityStatus.DOWN
}
```

Do not add backend I/O, lock/lease operations, scan, client creation, retry, or
executor. Removing Hazelcast/ZooKeeper `catch (Exception)` intentionally changes
direct cancellation/interruption to rethrow with flag restoration; ordinary
`Exception` remains `UNKNOWN` through the helper and `Error` remains fatal.

- [ ] **Step 2: Extend provider tests for state and exception parity.**

Keep the existing lifecycle/open/shutdown/connected status tests. Add ordinary
`Exception` normalization where Lettuce/Redisson previously propagated it, and
add cancellation, interruption, and same-instance `Error` tests to the
exception-injectable Lettuce, Redisson, Hazelcast, and ZooKeeper providers. Local
and MongoDB callbacks return constant statuses, so their regression scope is
status mapping plus timeout validation; the core helper tests own the full
exception matrix. For Hazelcast and ZooKeeper, preserve existing ordinary
`Exception -> UNKNOWN` and `Error` identity tests and add interruption cleanup.
Use `Thread.interrupted()` before and in `finally` around each interruption case;
if a dedicated thread is used, assert `join(1000)` returns and the thread is no
longer alive.

- [ ] **Step 3: Run provider-targeted tests and commit.**

```bash
./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-mongodb:test :bluetape4k-leader-redis-lettuce:test :bluetape4k-leader-redis-redisson:test :bluetape4k-leader-hazelcast:test :bluetape4k-leader-zookeeper:test --no-daemon --no-configuration-cache --max-workers=1 --console=plain
```

Expected: `BUILD SUCCESSFUL`; all existing and new provider tests pass without
adding container requirements beyond those already declared by the provider
modules.

```bash
git add leader-core leader-mongodb leader-redis-lettuce leader-redis-redisson leader-hazelcast leader-zookeeper
git commit -m "내장 leader backend를 공통 diagnostics probe로 정렬한다"
```

## Task 4: Lock adapter behavior with Ktor/Spring tests

**Files:**

- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderBackendDiagnosticsRouteTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendHealthIndicatorTest.kt`

- [ ] **Step 1: Add Ktor built-in/custom route cases.**

Use `testApplication` and provider fixtures that either delegate to
`LeaderBackendDiagnosticsProbe` or override `checkConnectivity`/`diagnostics`.
Assert the following exact boundaries:

- built-in ordinary `Exception` becomes HTTP `200` JSON with `UNKNOWN`;
- built-in `CancellationException`, `InterruptedException`, and `Error` are
  not converted into a response by the route: with no `StatusPages` plugin in
  the fixture, the `client.get` call is asserted with
  `assertFailsWith<Throwable>` and the thrown object (or its direct cause) is
  the same instance; the interruption flag is verified only in the direct
  provider/helper test so a Ktor `Dispatchers.IO` worker is never contaminated;
- built-in invalid `NOT_CHECKED` reaches the application pipeline as the same
  `IllegalArgumentException` (again captured from `client.get`), without adding
  a `StatusPages` dependency or changing route defaults;
- custom ordinary exception is delegated to the application pipeline rather
  than translated by the route; with the repository's current test
  dependencies, capture the no-plugin `client.get` failure and assert the same
  instance through its cause chain, without adding `StatusPages`;
- custom returned `NOT_CHECKED` is serialized as JSON `NOT_CHECKED`;
- configured timeout is passed once; the existing `Dispatchers.IO` route boundary
  remains, while the helper itself creates no thread.

Use `shouldHaveStatus`, `bodyAsText`, and existing exact JSON fixtures. Do not
change route serialization or plugin defaults.
For thrown-route cases, use a small test-only cause-chain matcher with an
identity-based visited set and assert that the expected throwable appears at
some wrapper depth; do not assume a single engine wrapper.

- [ ] **Step 2: Add Spring built-in/custom health cases.**

Extend `LeaderBackendHealthIndicatorTest` with this matrix:

| Provider result | Expected health | Details | Warning |
|---|---|---|---|
| built-in ordinary `Exception` normalized by helper | `UNKNOWN` | allow-listed backend/connectivity/checkedAt fields | none |
| built-in `CancellationException` | `UNKNOWN` | no raw exception fields | yes, existing catch log |
| built-in `InterruptedException` | `UNKNOWN` | no raw exception fields | yes; interrupt flag restored |
| built-in invalid `NOT_CHECKED` (`IllegalArgumentException`) | `UNKNOWN` | no raw exception fields | yes, existing catch log |
| built-in `Error` | rethrow same instance | no health result | no normalization |
| custom returned `NOT_CHECKED` | `UNKNOWN` | allow-listed connectivity detail | none |
| custom ordinary/cancellation/interruption exception | `UNKNOWN` | no raw exception fields | yes, existing catch log |
| custom `Error` | rethrow same instance | no health result | no normalization |

Retain the existing `show-details=always` raw-detail test. Verify interruption
flag cleanup and use `io.bluetape4k.assertions.assertFailsWith` plus
`shouldBeSameInstanceAs` for fatal identity.

Use Spring Boot's existing test support rather than a new logging dependency:
annotate `LeaderBackendHealthIndicatorTest` with
`@ExtendWith(OutputCaptureExtension::class)`, accept `CapturedOutput` in the
warning-matrix tests, and assert the exact existing warning string
`leader.spring.health backend probe failed; status=UNKNOWN`. The normalized
ordinary-`Exception` and custom returned `NOT_CHECKED` cases must not contain
that warning; cancellation, interruption, invalid built-in `NOT_CHECKED`, and
custom thrown exceptions must contain it. Keep `Error` cases free of a health
result and free of normalization logging.

- [ ] **Step 3: Run adapter tests and commit.**

```bash
./gradlew :bluetape4k-leader-ktor:test :bluetape4k-leader-spring-boot:test --no-daemon --no-configuration-cache --max-workers=1 --console=plain
```

Expected: `BUILD SUCCESSFUL`; route JSON field shape and Spring health allow-list
remain unchanged.

```bash
git add leader-ktor/src/test leader-spring-boot/src/test
git commit -m "Ktor와 Spring diagnostics adapter 경계를 회귀 테스트로 고정한다"
```

## Task 5: Update public README and KDoc documentation

**Files:**

- Modify: `README.md`, `README.ko.md`
- Modify: `leader-ktor/README.md`, `leader-ktor/README.ko.md`
- Modify: `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md`
- Modify: core helper and SPI KDoc files from Tasks 1–2

- [ ] **Step 1: Align English and Korean root diagnostics sections.**

Document the same facts in both locales: active probe is opt-in; timeout is a
positive finite provider-native budget and not a wall-clock deadline; built-in
ordinary `Exception` maps to `UNKNOWN`; cancellation/interruption/`Error` retain
their semantics; built-in `NOT_CHECKED` is passive-only; custom override is a
source-compatible escape hatch; `UNKNOWN` is not readiness or ownership proof;
the built-in helper and Spring allow-list do not serialize raw
exception/credential/endpoint values. Custom provider descriptors and custom
Ktor application-pipeline responses remain application-owned and must be
sanitized by the caller. Link [Issue #774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)
for future cause signals and readiness/runbook policy.

Before editing either locale, write and use this fact matrix as the read-back
oracle; record the EN/KO evidence in the lesson/review artifact instead of
claiming parity from terminology counts alone:

| Fact key | English wording to preserve | Korean meaning to preserve |
|---|---|---|
| timeout | positive finite provider-native budget; no wall-clock deadline | 양수 유한 provider-native budget; wall-clock deadline 아님 |
| direct-exception | built-in callback `Exception -> UNKNOWN`; clock/validation failures propagate | 내장 callback `Exception -> UNKNOWN`; clock/검증 실패는 전파 |
| adapter-exception | Ktor built-in ordinary `Exception -> HTTP 200 + UNKNOWN`, fatal/validation -> pipeline; Spring normalized ordinary `Exception -> UNKNOWN` without warning, caught cancellation/interruption/validation -> `UNKNOWN + warning`, `Error` rethrows | Ktor 내장 일반 `Exception -> HTTP 200 + UNKNOWN`, fatal/검증 실패 -> pipeline; Spring 정규화 일반 `Exception`은 warning 없이 `UNKNOWN`, 포착된 취소/중단/검증 실패는 `UNKNOWN + warning`, `Error`는 재전파 |
| custom | custom override/pipeline/descriptor is caller-owned and must be sanitized | custom override/pipeline/descriptor는 caller 소유이며 caller가 정제 |
| follow-up | link Issue #774 for cause, readiness, and runbook policy | 원인·readiness·runbook 정책은 Issue #774 링크 |

The matrix must distinguish callback `Exception`, clock failure, invalid
timeout, invalid `NOT_CHECKED`, cancellation, interruption, and `Error`; do not
compress those into one “exception semantics” sentence.

- [ ] **Step 2: Correct Ktor module documentation.**

State that the existing route calls the provider inside `withContext(Dispatchers.IO)`
but the helper does not hop threads or enforce a wall-clock deadline. Document
built-in ordinary exception `HTTP 200 + UNKNOWN`, custom exception delegation to
application pipeline, custom `NOT_CHECKED` JSON behavior, and the fact that
custom descriptor/pipeline payload sanitization remains the caller's
responsibility.

- [ ] **Step 3: Correct Spring module documentation.**

Document the allow-listed details and warning matrix from Task 4, including
interrupt restoration and fatal `Error` rethrow. State that `UNKNOWN` does not
automatically pass readiness and that the active probe still requires management
endpoint protection. Repeat that the built-in Spring allow-list is sanitized,
while custom provider descriptor/detail values remain caller-owned and must be
sanitized before exposure.

- [ ] **Step 4: Run documentation checks and commit.**

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs README.md README.ko.md leader-ktor/README.md leader-ktor/README.ko.md leader-spring-boot/README.md leader-spring-boot/README.ko.md
git add README.md README.ko.md leader-ktor/README.md leader-ktor/README.ko.md leader-spring-boot/README.md leader-spring-boot/README.ko.md leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbe.kt
git commit -m "diagnostics probe의 공개 예외와 timeout 계약을 문서화한다"
```

Expected: diff check passes and terminology audit reports findings 0.

Do not modify `docs/manual/manifest.yaml` or versioned `docs/manual` in Issue
#766; the 0.5.0 release pin does not contain this API. Issue #774 owns the
1.0.0 release-train manual update after its release commit is pinned.

## Task 6: Compile/API evidence and proportional verification

**Files:**

- Generated only: `build/issue-766-kotlin-consumer/ProbeConsumer.kt` and compiled class output; remove the generated fixture after verification.
- No source file changes beyond Tasks 1–5.

- [ ] **Step 1: Run all changed module tests and static checks.**

Clear only the affected modules' generated test reports before the run so stale
XML cannot be counted:

```bash
./gradlew \
  :bluetape4k-leader-core:cleanTest \
  :bluetape4k-leader-mongodb:cleanTest \
  :bluetape4k-leader-redis-lettuce:cleanTest \
  :bluetape4k-leader-redis-redisson:cleanTest \
  :bluetape4k-leader-hazelcast:cleanTest \
  :bluetape4k-leader-zookeeper:cleanTest \
  :bluetape4k-leader-ktor:cleanTest \
  :bluetape4k-leader-spring-boot:cleanTest \
  --no-daemon --no-configuration-cache --max-workers=1 --console=plain
```

```bash
./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-mongodb:test :bluetape4k-leader-redis-lettuce:test :bluetape4k-leader-redis-redisson:test :bluetape4k-leader-hazelcast:test :bluetape4k-leader-zookeeper:test :bluetape4k-leader-ktor:test :bluetape4k-leader-spring-boot:test detekt --no-daemon --no-configuration-cache --max-workers=1 --console=plain
```

Expected: `BUILD SUCCESSFUL`, no failed/skipped changed tests, and Detekt passes.
If the combined command exceeds the normal terminal window, run the same exact
tasks through the repository's long Gradle/context-mode runner and preserve the
hosted artifact or full console evidence; a skipped task is not a passing test.

Read the generated JUnit XML and fail the verification if any test was skipped:

```bash
set -eu
modules=(leader-core leader-mongodb leader-redis-lettuce leader-redis-redisson leader-hazelcast leader-zookeeper leader-ktor leader-spring-boot)
skipped=0
files=0
for module in "${modules[@]}"; do
    result_dir="$module/build/test-results/test"
    module_files=$(find "$result_dir" -type f -name 'TEST-*.xml' 2>/dev/null | wc -l | tr -d ' ')
    test "$module_files" -gt 0
    files=$((files + module_files))
    while IFS= read -r -d '' xml; do
        count=$(rg -o 'skipped="[0-9]+"' "$xml" | awk -F'"' '{sum += $2} END {print sum + 0}')
        skipped=$((skipped + count))
    done < <(find "$result_dir" -type f -name 'TEST-*.xml' -print0)
done
test "$files" -gt 0
test "$skipped" -eq 0
```

- [ ] **Step 2: Verify ABI compatibility using the current release version.**

```bash
ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=1.0.0 ./gradlew --no-daemon --console=plain --no-configuration-cache checkBinaryCompatibility
```

Expected: `BUILD SUCCESSFUL` and no binary compatibility violations.

- [ ] **Step 3: Verify Kotlin consumer compilation and JVM artifact symbols.**

After the core jar is built, create `build/issue-766-kotlin-consumer/ProbeConsumer.kt`
with this exact source:

```kotlin
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnostics
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbe
import kotlin.time.Duration.Companion.milliseconds

fun consumerProbe() = LeaderBackendDiagnosticsProbe.check(1.milliseconds) {
    LeaderBackendConnectivityStatus.UNKNOWN
}

fun legacyCheckProvider(descriptor: LeaderBackendDescriptor) =
    object : LeaderBackendDiagnosticsProvider {
        override val backendDescriptor = descriptor
        override fun checkConnectivity(timeout: kotlin.time.Duration): LeaderBackendConnectivity =
            LeaderBackendConnectivity.notChecked()
    }

fun legacyDiagnosticsProvider(descriptor: LeaderBackendDescriptor) =
    object : LeaderBackendDiagnosticsProvider {
        override val backendDescriptor = descriptor
        override fun diagnostics(
            probe: Boolean,
            timeout: kotlin.time.Duration,
        ): LeaderBackendDiagnostics =
            LeaderBackendDiagnostics(descriptor, LeaderBackendConnectivity.notChecked())
    }
```

Run:

```bash
./gradlew :bluetape4k-leader-core:jar --no-daemon --no-configuration-cache --max-workers=1 --console=plain
CORE_JAR=leader-core/build/libs/bluetape4k-leader-core-1.0.0.jar
test "$(find leader-core/build/libs -maxdepth 1 -type f -name 'bluetape4k-leader-core-1.0.0.jar' | wc -l | tr -d ' ')" -eq 1
test -f "$CORE_JAR"
CONSUMER_DIR=build/issue-766-kotlin-consumer
rm -rf "$CONSUMER_DIR"
trap 'rm -rf "$CONSUMER_DIR"' EXIT
mkdir -p "$CONSUMER_DIR/classes"
kotlinc -classpath "$CORE_JAR" -d "$CONSUMER_DIR/classes" "$CONSUMER_DIR/ProbeConsumer.kt"
jar tf "$CORE_JAR" | rg 'io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbe'
javap -classpath "$CORE_JAR" 'io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbe'
```

Expected: `kotlinc` exits 0; `jar tf` prints the helper class; `javap` prints
the singleton `INSTANCE` and a public mangled `check-...` method whose first
parameter is primitive `long` (Kotlin `Duration` value-class lowering), followed
by `java.time.Clock` and `kotlin.jvm.functions.Function1`; do not expect an
unmangled `kotlin.time.Duration` JVM signature. Use the exact bounded symbol
assertion below:

```bash
JAVAP_OUTPUT="$(javap -classpath leader-core/build/libs/bluetape4k-leader-core-1.0.0.jar \
  'io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbe' |
  tee build/issue-766-kotlin-consumer/ProbeConsumer.javap.txt)"
printf '%s\n' "$JAVAP_OUTPUT" | rg -q '^  public static final .* INSTANCE;$'
printf '%s\n' "$JAVAP_OUTPUT" | rg -q '^  public final .* check-[^ (]+\(long, java.time.Clock, kotlin.jvm.functions.Function1'
```

The bounded pre-clean and trap target only this generated directory, so a prior
failed run cannot supply stale source or classes and a later failure still
cleans the fixture after the evidence file has been captured.

- [ ] **Step 4: Verify diff and source scope.**

```bash
git diff --check
git status --short
git diff --stat b05b883943b2255378e13f20c4be343b42bcf449
```

Expected: no whitespace errors; only the Issue #766 implementation, tests,
README/KDoc, review/plan/lesson artifacts are changed; unrelated worktrees and
remote refs are untouched.

## Task 7: Record the Type A lesson and final evidence

**Files:**

- Create: `docs/lessons/2026-08-24-issue-766-diagnostics-probe-base.md`

- [ ] **Step 1: Write the Korean lesson artifact before PR authorization.**

Record the decision to establish a core helper before provider-specific KDoc,
the `Exception`/cancellation/interruption/`Error` boundary, why custom override
and versioned manual were kept outside the base migration, the Ktor/Spring
adapter behavior change, test commands/results, and the deferred #774 operating
policy. Include the exact release pin reason, the direct/Ktor/Spring observable
behavior change for opt-in users, and no claim that provider-native timeout
enforces a wall-clock deadline. If a post-release corrective rollback is needed,
the Korean release note must state the restored provider behavior and retain the
public helper ABI.

- [ ] **Step 2: Run lesson writer checks and commit.**

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/lessons/2026-08-24-issue-766-diagnostics-probe-base.md
git add docs/lessons/2026-08-24-issue-766-diagnostics-probe-base.md
git commit -m "diagnostics probe base 작업의 운영 경계를 기록한다"
```

Expected: terminology findings 0 and a Lore-compliant lesson commit.

- [ ] **Step 3: Attach fresh evidence to all Type A receipt components.**

Record `spec`, `plan`, `implementation`, `tests`, `docs`, `abi`,
`consumer-smoke`, `detekt`, and `git-diff-check` results as bounded evidence
refs. Each receipt mutation uses the latest receipt checksum as
`--expected-head`; do not reuse an older checksum. Complete only after every
component has a check result and component evidence.

Use the live receipt head for every mutation; the following commands are the
exact plan-stage pattern for this run (repeat the head refresh after each
component):

```bash
FLOW=/Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py
RUN_ID=20260824T134721Z-fbca5142
OWNER=/Users/debop/work/bluetape4k/bluetape4k-leader/.bluetape/handles/706788D3-CD7D-44E8-8CDC-C153B0880364-diagnostics-probe-base.owner
STATE_ROOT=/Users/debop/work/bluetape4k/bluetape4k-leader/.bluetape
EXPECTED_HEAD="$(python3 "$FLOW" --state-root "$STATE_ROOT" receipt-diagnose --run-id "$RUN_ID" | jq -r '.last_trusted_checksum')"
python3 "$FLOW" --state-root "$STATE_ROOT" check-result \
  --run-id "$RUN_ID" \
  --owner-file "$OWNER" \
  --expected-head "$EXPECTED_HEAD" \
  --evidence /tmp/issue766-plan-evidence.json \
  --input /tmp/issue766-plan-base-input.json
```

The evidence file is a JSON array of bounded refs (`kind`, `summary`, and
optional `path`, `checksum`, `exit_status`); each input file is one object with
`component_id`, `check_id: "plan"`, `passed: true`, and an optional `reason`.
Run the same command sequentially for `provider-migration`, `docs`, and
`verification`, changing only the input file and refreshing `EXPECTED_HEAD`
from `receipt-diagnose` each time. Do not call `component-evidence` while the
`main-diagnostics-probe-base` lane is active; attach component evidence only
after `lane-complete` and all implementation checks have passed. The owner,
run ID, flow path, and state root above are fixed for this worktree.

## Task 8: Final local verification and stop gate

- [ ] **Step 1: Read back source, tests, docs, plan, lesson, receipt, and live Issue #766/#774.**

Confirm the exact helper signature, provider mappings, Ktor/Spring matrices,
README EN/KO parity, release pin exclusion, and issue ownership. Confirm branch
HEAD is the tested commit and the worktree is clean.

- [ ] **Step 2: Run the completion checklist.**

The implementation is `DONE` only when all changed tests, Detekt, ABI, Kotlin
consumer smoke, `jar tf`, `javap`, diff check, terminology audit, receipt
component evidence, and lesson artifact pass. If any command is unavailable or
fails, record the exact output and continue with the narrowest safe diagnosis;
do not claim completion from a partial or skipped suite.

- [ ] **Step 3: Stop before PR creation.**

PR creation requires a separate explicit target authorization naming repository,
base, and head. Merge requires a fresh exact-head review of metadata, checks,
threads, mergeability, linked Issue #766, and DoD followed by explicit merge
approval. This plan authorizes neither PR creation nor merge.

## Commit sequence and rollback points

1. RED helper tests.
2. Core helper and timeout/KDoc implementation.
3. Provider migrations and provider tests.
4. Ktor/Spring adapter tests.
5. Root/module README and KDoc documentation.
6. Lesson and final evidence.

Before release, reverting commits 6 through 1 in reverse order restores the
previous implementation, including the RED tests and lesson/evidence files.
After release, preserve `LeaderBackendDiagnosticsProbe` ABI and core helper
tests, then revert the provider implementation together with the dependent
Ktor/Spring adapter expectations, root/module README wording, and Korean release
note in the same corrective release. Re-run the direct/Ktor/Spring exception
matrix and consumer/ABI checks against that corrective release; do not leave
tests or documentation asserting the removed provider behavior. Record the
observable behavior change for opt-in users, and never delete the public helper
from an already published artifact.

## Plan self-review

| Spec requirement | Plan coverage |
|---|---|
| Core public helper, `requireGt`, finite timeout, one clock read | Tasks 1–2 |
| Exception/cancellation/interruption/Error/`NOT_CHECKED` behavior | Tasks 1–4 |
| Local, MongoDB, Lettuce, Redisson, Hazelcast, ZooKeeper migration | Task 3 |
| No new I/O, lock, lease, scan, client, retry, executor, or deadline | Tasks 2–3 and 6 |
| Built-in/custom direct/Ktor/Spring adapter contract | Task 4 |
| Legacy `checkConnectivity`/`diagnostics` override behavior and source compatibility | Tasks 1–2 and 6 |
| Root/module EN/KO README and public KDoc | Task 5 |
| EN/KO fact matrix and custom payload sanitization ownership | Task 5 |
| Versioned manual release-pin boundary and #774 ownership | Tasks 5 and 7 |
| ABI, Kotlin consumer smoke, `jar tf`, `javap`, Detekt, tests | Task 6 |
| Korean lesson, receipt, DoD, PR/merge gates | Tasks 7–8 |

Placeholder scan found no unfinished markers or vague error-handling language.
The final review pass additionally locks adapter exception capture, warning-log
assertions, value-class ABI inspection, same-thread behavior, zero-skipped XML
verification, and receipt-head sequencing.
Later code snippets use the same `LeaderBackendDiagnosticsProbe.check`
signature and `LeaderBackendConnectivityStatus` mapping defined in Task 2.
The rollback section keeps the helper ABI/core tests while restoring dependent
provider, adapter, documentation, and release-note expectations together.
