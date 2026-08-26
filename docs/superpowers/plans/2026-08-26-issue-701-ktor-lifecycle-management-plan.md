# Epic #701 Ktor lifecycle 및 management surface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Epic #701의 승인된 설계를 KTOR-01 lifecycle, KTOR-02 오류, KTOR-03 route guard, KTOR-04 event stream의 exact-head stacked PR train으로 구현하고 기존 `bluetape4k-leader-ktor` 계약을 보존한다.

**Architecture:** `LeaderElectionPlugin`마다 application-owned `LeaderElectionResourceRegistry`를 만들고 scheduler·collector만 등록하여 `ApplicationStopped`에서 역순·idempotent로 정리한다. 오류와 passive route guard는 core elector의 기존 state/lease capability를 얇게 감싸며, streaming은 core의 hot `LeaderElectionEventPublisher.events`를 Ktor-owned bounded hub로 한 번 수집한다. SSE/WebSocket/StatusPages 타입은 optional adapter 경계에 격리하고 기본 flag는 꺼 둔다.

**Tech Stack:** Kotlin/JVM, Ktor 3.x (`io.ktor:*:3.5.2` BOM), kotlinx-coroutines, JUnit 5, `runSuspendIO`, Ktor `testApplication`, `io.bluetape4k.assertions`, Gradle version catalog, Redisson/Testcontainers 회귀 테스트, `javap` public descriptor 확인.

---

## 0. 실행 전제와 train 계약

이 계획은 사용자 승인으로 고정된 설계 문서
`docs/superpowers/specs/2026-08-26-issue-701-ktor-lifecycle-management-design.md`의
구현 순서를 그대로 따른다. KTOR-01 worktree를 만들 때 고정한 immutable train
base는 `37bcff6b41f166769dd5d851f90fc28c1f8e92bd`이며, 그 기준선에서
`./gradlew :bluetape4k-leader-ktor:test --no-daemon --no-build-cache`가
`23 passing`으로 통과했다. 계획 검토 시점의 mutable `develop`과
`origin/develop`은 `9334c9df85a73ff32ee4897c50769d165c9bacff`까지 전진했으므로,
이후 child/PR 생성 직전에 live base와 exact parent SHA를 다시 읽는다. 기존
KTOR-01 branch를 조용히 rebase하지 않으며, base가 전진했으면 PR 전 descendant
rebase와 전체 재검증을 별도 evidence로 남긴다. 이 문서 작성 단계에서는
production source, child branch, PR, issue metadata, merge를 변경하지 않는다.

모든 구현자는 다음 규칙을 지킨다.

- 한 child는 앞 child의 exact head를 base로 삼고, child 내부 변경은 표의 소유 파일에만 쓴다.
- 테스트는 RED → 가장 작은 구현 → GREEN → 리팩터 순서로 작성한다.
- 예외 테스트는 `io.bluetape4k.assertions.assertFailsWith`만 사용하고 JUnit `assertThrows`, `kotlin.test.assertFailsWith`, `shouldThrow`는 추가하지 않는다.
- coroutine 테스트는 `runSuspendIO` 또는 `runTest`를 사용하며 cancellation은 `CancellationException`으로 재전파되는지 확인한다.
- public KDoc, README, manual, PR body와 commit message의 독자 문장은 한국어로 작성하고 Kotlin API·명령·URL·identifier는 그대로 보존한다.
- 각 commit은 Lore trailer를 포함한다. intent line은 결정 이유로 시작하고 다음 trailer를 채운다.

  ```text
  Constraint: 승인된 Epic #701 설계와 exact-head train
  Rejected: Application scope 단독 소유권은 exactly-once cleanup 증거가 부족해 제외
  Confidence: high
  Scope-risk: broad
  Directive: optional artifact 타입을 always-loaded class에 import하지 않음
  Tested: 해당 commit에서 실행한 명령과 핵심 결과를 기록
  Not-tested: 다음 child 또는 hosted CI에서 아직 실행하지 않은 항목을 기록
  ```

- test-only fake와 optional dependency smoke는 실제 backend client를 닫지 않도록 caller-owned 경계를 명시한다. Redisson/Testcontainers 테스트는 병렬 실행하지 않는다.
- 기능 flag가 꺼진 앱의 class loading이 optional artifact 없이도 성공하는지 확인한다. flag를 켰는데 artifact가 없으면 no-op이 아니라 configuration error여야 한다.

## 1. 파일 소유권과 의존성 순서

| 순서 | child/issue | branch와 base | 소유 변경 | 선행 계약 |
|---:|---|---|---|---|
| 1 | KTOR-01 / #541 | `feat/epic-ktor-01-lifecycle` / `develop` (작업 시 기록한 train base `37bc...`, PR 전 live 재확인) | registry, plugin stop hook, scheduler job ownership, lifecycle 테스트·문서 | 없음 |
| 2 | KTOR-02 / #540 | `feat/epic-ktor-02-errors` / KTOR-01 exact head | stable error code/payload, fallback responder, optional StatusPages adapter, management error mapping | KTOR-01 registry |
| 3 | KTOR-03 / #542 | `feat/epic-ktor-03-route-guard` / KTOR-02 exact head | `STATE`/명시적 `LEASE` route guard, auth 순서, capability/security 테스트·문서 | KTOR-02 error contract |
| 4 | KTOR-04 / #539 | `feat/epic-ktor-04-event-stream` / KTOR-03 exact head | bounded event hub, SSE/WebSocket adapter, replay/cursor/heartbeat/filter/cancellation | KTOR-01 registry + KTOR-02 error contract |

KTOR-01부터 KTOR-04까지는 순차 작업이다. 같은 `gradle/libs.versions.toml`,
`leader-ktor/build.gradle.kts`, README와 manual을 여러 child가 수정할 수 있으나
앞 child가 commit한 exact head 이후에만 다음 child가 해당 파일을 수정한다. child를
병렬로 작성하지 않아 catalog와 문서의 merge conflict를 원천적으로 줄인다.

### 1.1 변경 파일 지도

KTOR-01이 최초 소유하는 파일:

- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionResourceRegistry.kt` — application-owned resource entry, LIFO close, bounded job join, shutdown report.
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt` — registry attribute와 `ApplicationStopped` close hook.
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/ApplicationExt.kt` — `leaderScheduled`가 반환한 `Job` 등록.
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionResourceRegistryTest.kt` — registry race/close/job contract.
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginTest.kt` — application stop와 caller-owned elector 회귀.
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/ApplicationExtTest.kt` — scheduler registration/cancellation 회귀.
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderKtorTestDoubles.kt` — caller-owned fake와 AutoCloseable decorator.
- Modify: `leader-ktor/README.md`, `leader-ktor/README.ko.md`, `docs/manual/en/modules/bluetape4k-leader-ktor.md`, `docs/manual/ko/modules/bluetape4k-leader-ktor.md` — lifecycle ownership 설명.

KTOR-02가 KTOR-01 exact head에서 소유하는 파일:

- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionError.kt` — public error code/context와 internal safe mapping.
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/statuspages/LeaderElectionStatusPagesAdapter.kt` — compileOnly `StatusPages` adapter.
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementRoute.kt` — invalid lock/backend failure mapping.
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginConfig.kt` — typed responder policy만 추가하고 optional Ktor class는 참조하지 않음.
- Modify: `gradle/libs.versions.toml` — `ktor-server-status-pages` alias.
- Modify: `leader-ktor/build.gradle.kts` — status-pages compileOnly/testImplementation.
- Create/Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionErrorTest.kt`, `LeaderElectionStatusPagesAdapterTest.kt`, `LeaderElectionManagementRouteTest.kt`.
- Modify: `leader-ktor/README.md`, `leader-ktor/README.ko.md`, `docs/manual/en/modules/bluetape4k-leader-ktor.md`, `docs/manual/ko/modules/bluetape4k-leader-ktor.md` — 오류 payload와 StatusPages 선택 규칙.

KTOR-03이 KTOR-02 exact head에서 소유하는 파일:

- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuard.kt` — public enum/config/DSL와 route-scoped plugin.
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuardTest.kt` — state/lease/auth/security 회귀.
- Modify: `gradle/libs.versions.toml`, `leader-ktor/build.gradle.kts` — auth는 test-only alias/dependency로만 추가하고 runtime surface에는 넣지 않음.
- Modify: `leader-ktor/README.md`, `leader-ktor/README.ko.md`, `docs/manual/en/modules/bluetape4k-leader-ktor.md`, `docs/manual/ko/modules/bluetape4k-leader-ktor.md` — passive limitation, capability와 auth nesting.

KTOR-04가 KTOR-03 exact head에서 소유하는 파일:

- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamHub.kt` — sequence, ring buffer, replay/live handoff, bounded subscriber.
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamPayload.kt` — safe JSON/control event, core event shape adapter.
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamConfig.kt` — flag/path/capacity/heartbeat validation; optional type 없음.
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamBootstrap.kt` — plugin-owned hub 생성·registry 등록과 reflection-based optional adapter loading.
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/sse/LeaderEventSseAdapter.kt` — compileOnly SSE route/session cleanup.
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/websocket/LeaderEventWebSocketAdapter.kt` — compileOnly WebSockets route/session cleanup.
- Modify: `LeaderElectionPlugin.kt`, `LeaderElectionPluginConfig.kt`, `gradle/libs.versions.toml`, `leader-ktor/build.gradle.kts` — stream flag와 SSE/WebSocket aliases.
- Create: `LeaderEventStreamHubTest.kt`, `LeaderEventStreamRouteTest.kt`, `LeaderOptionalClasspathSmokeTest.kt`; modify `LeaderElectionPluginTest.kt` and `LeaderKtorTestDoubles.kt` as needed.
- Modify: 위 README/manual 4개와 `docs/manual/en/frameworks/ktor.md`, `docs/manual/ko/frameworks/ktor.md` — stream API와 운영 제한.

항상 로드되는 plugin/config/bootstrap 파일은 `io.ktor.server.sse.*`,
`io.ktor.server.websocket.*`, `io.ktor.server.plugins.statuspages.*` 타입을 import하지
않는다. 해당 타입은 adapter 파일에서만 compileOnly로 참조하고, bootstrap은 고정된
adapter class name과 `Class.forName`/반사 호출로 연결한다. 이 경계를 깨는 import가
생기면 KTOR-04 classpath smoke를 통과할 때까지 진행하지 않는다.

## 2. 공통 검증과 완료 증거

각 child의 마지막에는 다음 순서로 실행하고 로그의 성공 줄을 PR body와 DoD에 기록한다.

```bash
./gradlew :bluetape4k-leader-ktor:test --no-daemon --no-build-cache
./gradlew :bluetape4k-leader-ktor:compileKotlin :bluetape4k-leader-ktor:compileTestKotlin --no-daemon --no-build-cache
./gradlew :bluetape4k-leader-ktor:jar --no-daemon --no-build-cache
./gradlew detekt --no-daemon --no-build-cache
./gradlew exportManualModuleInventory
git diff --check
```

`LeaderElectionPlugin`/management/route guard의 test-host 테스트는 fake elector와
fake publisher로 deterministic하게 실행한다. 기존 Redisson/Testcontainers 테스트는
별도로 순차 실행한다. 저장소에 전용 binary compatibility task가 없으므로 각 child의
public JVM API는 `jar` 산출물에 대해 다음처럼 확인한다.

```bash
jar tf leader-ktor/build/libs/*.jar | rg 'Leader(Election|Route|Event)|ktor'
KTOR_JAR=$(printf '%s\n' leader-ktor/build/libs/*.jar | head -n 1)
for TYPE in \
  io.bluetape4k.leader.ktor.LeaderElectionPluginConfig \
  io.bluetape4k.leader.ktor.LeaderElectionErrorCode \
  io.bluetape4k.leader.ktor.LeaderElectionErrorContext \
  io.bluetape4k.leader.ktor.LeaderRouteAuthorityMode \
  io.bluetape4k.leader.ktor.LeaderRouteGuardConfig; do
  javap -classpath "$KTOR_JAR" "$TYPE"
done
# jar tf 결과에서 확인한 JVM owner로 top-level Route extension descriptor를 검사한다.
javap -classpath "$KTOR_JAR" io.bluetape4k.leader.ktor.LeaderRouteGuardKt
javap -classpath "$KTOR_JAR" io.bluetape4k.leader.ktor.stream.LeaderEventStreamBootstrapKt
```

`jar tf`에 실제로 나타나는 public type만 descriptor 목록에 넣고, Kotlin
`internal` item/report와 optional adapter는 public ABI 목록에서 제외한다. 각 child는
대표 downstream caller의 `compileTestKotlin`도 실행하여 source 호출과 JVM descriptor가
함께 유지되는지 확인한다.

manual 변경이 포함된 마지막 child에서는 `docs/manual/manifest.yaml`의 현재
pinned `releaseRef`/`releaseCommit`을 읽어 명령을 구성한다. manifest 값이
바뀌면 이전 값을 복사하지 않고 그 live 값과 tag/SHA 일치를 먼저 검증한다.

```bash
ruby scripts/manual/export_manifest.rb --check
read -r MANUAL_RELEASE_REF MANUAL_RELEASE_COMMIT < <(ruby -ryaml -e 'manifest = YAML.load_file("docs/manual/manifest.yaml"); puts [manifest.fetch("releaseRef"), manifest.fetch("releaseCommit")].join(" ")')
ruby scripts/manual/release_inventory.rb "$MANUAL_RELEASE_REF" "$MANUAL_RELEASE_COMMIT" build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb "$MANUAL_RELEASE_REF" "$MANUAL_RELEASE_COMMIT"
ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
```

## 3. KTOR-01 — lifecycle resource registry (#541)

### Task 1: registry의 실패 테스트와 자료구조 고정

**Files:**
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionResourceRegistryTest.kt`
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionResourceRegistry.kt`

- [x] **Step 1: 테스트 fixture와 실패 assertion을 먼저 작성한다.**

  테스트 파일에는 `runSuspendIO`, `assertFailsWith`가 필요한 경우의 Bluetape
  assertion, `AtomicInteger`, `CompletableDeferred`, `Job`, `launch`, `delay`,
  `awaitCancellation`, `withContext`, `NonCancellable`, `cancelAndJoin`,
  `withTimeout`, `CountDownLatch`, `Executors`, `milliseconds`를 import하고
  다음 네 계약을 테스트 이름으로 고정한다.
  registry 전용 `TrackingCloseable`은 테스트 파일 안에 다음처럼 둔다.

  ```kotlin
  private class TrackingCloseable(
      private val label: String,
      private val closed: MutableList<String>,
  ) : AutoCloseable {
      private val once = AtomicBoolean()
      override fun close() {
          if (once.compareAndSet(false, true)) closed += label
      }
  }
  ```

  ```kotlin
  @Test
  fun `close는 등록 역순으로 각 resource를 한 번만 닫는다`() = runSuspendIO {
      val closed = mutableListOf<String>()
      val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 50.milliseconds)
      registry.register(TrackingCloseable("first", closed))
      registry.register(TrackingCloseable("second", closed))

      registry.close()
      registry.close()
      registry.awaitClosed()

      closed shouldBeEqualTo listOf("second", "first")
      registry.lastShutdownReport shouldBeEqualTo
          LeaderElectionShutdownReport(
              attempted = 2,
              closed = 2,
              failures = 0,
              timedOutJobs = 0,
              failureKinds = emptyMap(),
              timeoutKinds = emptyMap(),
          )
  }

  @Test
  fun `닫힌 registry에 등록하면 resource가 즉시 닫힌다`() = runSuspendIO {
      val closed = AtomicInteger(0)
      val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 50.milliseconds)
      registry.close()

      registry.register(AutoCloseable { closed.incrementAndGet() }).close()

      closed.get() shouldBeEqualTo 1
  }

  @Test
  fun `job resource는 cancel 후 bounded join하고 timeout을 집계한다`() = runSuspendIO {
      val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 25.milliseconds)
      val job = launch {
          try {
              awaitCancellation()
          } finally {
              withContext(NonCancellable) { delay(250.milliseconds) }
          }
      }
      registry.register(job)

      registry.close()
      registry.awaitClosed()

      job.isCancelled.shouldBeTrue()
      registry.lastShutdownReport?.timedOutJobs shouldBeEqualTo 1
      job.cancelAndJoin()
  }

  @Test
  fun `register와 close 경합은 resource를 누락하거나 두 번 닫지 않는다`() = runSuspendIO {
      val gate = CountDownLatch(1)
      val closeCount = AtomicInteger(0)
      val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 50.milliseconds)
      val pool = Executors.newFixedThreadPool(3)
      val registerJob = pool.submit {
          gate.await()
          registry.register(AutoCloseable { closeCount.incrementAndGet() })
      }
      val closeJob = pool.submit {
          gate.await()
          registry.close()
      }
      val tokenJob = pool.submit {
          gate.await()
          registry.register(AutoCloseable { closeCount.incrementAndGet() }).close()
      }
      gate.countDown()
      registerJob.get()
      closeJob.get()
      tokenJob.get()
      pool.shutdownNow()
      registry.awaitClosed()

      closeCount.get() shouldBeEqualTo 2
  }
  ```

  `TrackingCloseable`은 label과 `MutableList<String>`에 한 번만 기록하고,
  assertion에는 `shouldBeEqualTo`/`shouldBeTrue`를 사용한다. 아직
  `LeaderElectionResourceRegistry`가 없으므로 테스트는 컴파일 단계에서 실패한다.

- [x] **Step 2: KTOR-01 테스트만 실행하여 RED를 확인한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderElectionResourceRegistryTest' --no-daemon --no-build-cache`

  Expected: `Unresolved reference: LeaderElectionResourceRegistry` 또는 동일한
  symbol 부재 컴파일 실패. 이 시점에 production 구현을 먼저 추가하지 않는다.

- [x] **Step 3: 최소 registry 계약을 구현한다.**

  `LeaderElectionResourceRegistry.kt`에 다음 이름과 책임을 구현한다.

  ```kotlin
  internal data class LeaderElectionShutdownReport(
      val attempted: Int,
      val closed: Int,
      val failures: Int,
      val timedOutJobs: Int,
      val failureKinds: Map<String, Int> = emptyMap(),
      val timeoutKinds: Map<String, Int> = emptyMap(),
  )

  internal interface LeaderElectionResourceRegistry : AutoCloseable {
      fun register(resource: AutoCloseable): AutoCloseable
      fun register(job: Job): AutoCloseable
      val lastShutdownReport: LeaderElectionShutdownReport?
      suspend fun awaitClosed(): LeaderElectionShutdownReport
  }

  internal class LeaderElectionResourceRegistryImpl(
      private val jobJoinTimeout: Duration = 2.seconds,
  ) : LeaderElectionResourceRegistry {
      // ReentrantLock 하나가 register/close/registration-token close의
      // linearization 경계다. lock 안에서는 entry의 상태 전환과 reverse
      // snapshot만 수행하고, user resource의 close/cancel/join은 lock 밖에서
      // 실행해 재진입과 shutdown deadlock을 막는다.
  }
  ```

  `LeaderElectionResourceRegistry`는 plugin이 참조하는 internal 계약으로 유지하고,
  테스트와 plugin은 `LeaderElectionResourceRegistryImpl(...)` 구현을 생성한다.
  `register(resource)`는 registry가 열려 있으면 token을 entries에 추가하고,
  닫힌 뒤에는 lock을 놓은 다음 resource를 즉시 닫는다. 반환 token의 `close()`는
  lock 안에서 entry를 제거·closed 전환한 뒤 lock 밖에서 해당 resource를 정확히
  한 번 닫는다. registry `close()`는 lock 안에서 atomic closed 전환과 reverse
  entry drain을 수행한다. lock을 놓은 뒤 `Job.cancel()`만 즉시 실행하고,
  user resource close/cancel/join은 모두 lock 밖에서 수행한 뒤
  `SupervisorJob + Dispatchers.IO.limitedParallelism(1)`로 만든 registry-owned
  cleanup scope에 실제 close/join 작업을 예약한다. cleanup task는 report를
  완료한 뒤 scope를 닫아 stop 이후 dispatcher가 남지 않게 한다. `close()`는
  `ApplicationStopped` callback dispatcher에서 `runBlocking`/`join`을 호출하지
  않으며, `awaitClosed()`가 그 completion을 기다린다. 각 `Job`은 cleanup scope에서
  `withTimeoutOrNull(jobJoinTimeout) { join() }`으로 bounded join하고 timeout이면
  `timedOutJobs`와 `timeoutKinds["job"]`을 증가시킨다. close 예외는 다음 entry를
  건너뛰지 않고 `failures`/`failureKinds`에 resource kind별로 집계한다.
  `CancellationException`을 cleanup failure로 변환하지 않고 job에 전달한다.

- [x] **Step 4: registry 테스트를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderElectionResourceRegistryTest' --no-daemon --no-build-cache`

  Expected: 모든 registry test PASS, report의 `failures`와 `timedOutJobs`가
  테스트 fixture와 일치한다. 실패하면 lock 경계 또는 job timeout만 수정한다.

- [x] **Step 5: resource race와 failure 집계를 보강하고 commit한다.**

  close가 resource close 예외를 삼키고 다음 resource를 계속 닫는 fixture를 한 개
  추가하고, registration token을 두 번 닫아도 기록이 한 번인지를 확인한다. 이후
  `git diff --check`와 해당 test를 다시 실행한다.

  ```bash
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionResourceRegistry.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionResourceRegistryTest.kt
  git commit -m "Epic #701 resource registry의 exactly-once shutdown 경계를 고정한다"
  ```

  commit body에는 Lore trailer와 `Tested: LeaderElectionResourceRegistryTest PASS`를
  기록한다.

### Task 2: plugin stop hook과 attribute ownership 연결

**Files:**
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginTest.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionResourceRegistryTest.kt`
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderKtorTestDoubles.kt`

- [x] **Step 1: application stop 회귀 테스트를 RED로 추가한다.**

  `LeaderElectionPluginTest`에 plugin 설치 후
  `application.leaderElectionResourceRegistryOrNull()`로 얻은 registry에
  tracking resource를 등록하고 `testApplication` 블록을 빠져나온 뒤 close count가
  1인지 확인하는 테스트를 추가한다. `FakeSuspendLeaderElector`는 caller-owned
  contract를 설명하기 위해 `AutoCloseable`을 구현하지 않고,
  `LeaderKtorTestDoubles.kt`의 별도 test-local
  `AutoCloseableFakeSuspendLeaderElector` wrapper를 config에 전달해 elector close
  count가 0인 별도 테스트를 둔다. application attribute가
  없는 plugin 미설치 경로는 기존 `leaderElectionPluginConfig()` 예외 계약을 유지한다.

  ```kotlin
  @Test
  fun `ApplicationStopped에서 plugin-owned resource만 한 번 닫힌다`() = runSuspendIO {
      val closeCount = AtomicInteger(0)
      lateinit var resource: AutoCloseable
      lateinit var registry: LeaderElectionResourceRegistry

      testApplication {
          application {
              install(LeaderElectionPlugin) {
                  leaderElection = FakeSuspendLeaderElector()
              }
              resource = AutoCloseable { closeCount.incrementAndGet() }
              registry = leaderElectionResourceRegistryOrNull()!!
              registry.register(resource)
          }
          startApplication()
      }
      registry.awaitClosed()

      closeCount.get() shouldBeEqualTo 1
  }
  ```

  RED는 새 attribute/extension이 없어 컴파일되지 않아야 한다.

  ```kotlin
  internal class FakeSuspendLeaderElector(
      private val stateValue: LeaderState = LeaderState.empty("job"),
      private val stateReads: AtomicInteger? = null,
      override val supportsAuditLeaderState: Boolean = true,
  ) : SuspendLeaderElector {
      override fun state(lockName: String): LeaderState {
          stateReads?.incrementAndGet()
          return stateValue.copy(lockName = lockName)
      }
      override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
  }

  internal class AutoCloseableFakeSuspendLeaderElector(
      private val delegate: FakeSuspendLeaderElector = FakeSuspendLeaderElector(),
  ) : SuspendLeaderElector, AutoCloseable {
      val closeCount = AtomicInteger()
      override val supportsAuditLeaderState: Boolean get() = delegate.supportsAuditLeaderState
      override fun state(lockName: String): LeaderState = delegate.state(lockName)
      override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
          delegate.runIfLeader(lockName, action)
      override fun close() { closeCount.incrementAndGet() }
  }

  @Test
  fun `caller-owned AutoCloseable elector는 ApplicationStopped에서 닫지 않는다`() = runSuspendIO {
      val elector = AutoCloseableFakeSuspendLeaderElector()
      testApplication {
          application { install(LeaderElectionPlugin) { leaderElection = elector } }
          startApplication()
      }
      elector.closeCount shouldBeEqualTo 0
  }
  ```

- [x] **Step 2: plugin attribute와 stop hook을 구현한다.**

  `LeaderElectionPlugin.kt`에 `internal val LeaderElectionResourceRegistryKey`
  (`AttributeKey<LeaderElectionResourceRegistry>`)와
  `internal fun Application.leaderElectionResourceRegistryOrNull()`을 추가한다.
  plugin body에서 기존 config attribute를 저장하는 시점에 registry를 한 번 만들고
  함께 저장한다. `on(MonitoringEvent(ApplicationStopped))`에서는 registry의
  synchronous `close()`만 호출해 linearization/cancel을 시작하고, 실제
  close/join은 registry-owned cleanup dispatcher에서 수행한다. callback dispatcher에서
  `runBlocking`/`join`을 호출하지 않는다. registry-owned completion observer가
  `awaitClosed()`와 같은 completion signal을 읽어 `attempted/closed/failures/timedOutJobs`와
  `failureKinds/timeoutKinds`를 structured log로 남긴다. caller가 전달한
  `leaderElection`, backend client, database pool은
  registry에 자동 등록하지 않는다. stop hook은 registry의 idempotency에 의존하여
  Ktor가 stop event를 중복 전달해도 두 번째 본문이 실행되지 않게 한다.

- [x] **Step 3: plugin 테스트와 기존 test suite를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderElectionPluginTest' --tests '*LeaderElectionResourceRegistryTest' --no-daemon --no-build-cache`

  Expected: 새 stop/caller-owned 테스트와 기존 3개 plugin 테스트가 PASS한다.

- [x] **Step 4: plugin source의 optional import와 shutdown log를 검토하고 commit한다.**

  `rg -n 'ktor.server.(sse|websocket|plugins.statuspages)' leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt`가
  결과를 내지 않아야 한다. `git diff --check` 후 다음 Lore commit을 만든다.

  ```bash
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginTest.kt
  git commit -m "Epic #701 plugin stop에서 application-owned resource를 정리한다"
  ```

### Task 3: `leaderScheduled` Job을 registry에 귀속

**Files:**
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/ApplicationExt.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/ApplicationExtTest.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderKtorTestDoubles.kt`

  Task 2에서 만든 `LeaderKtorTestDoubles.kt`를 이후 child도 재사용할
  deterministic fixture로 확장한다. `FakeSuspendLeaderElector`는 주어진 `LeaderState`를 반환하고
  `runIfLeader`에서는 action을 실행한다. `CountingLeaseAcquirer`는
  `tryAcquire` 횟수와 반환할 `TrackingLeaseHandle`을 노출하며, handle은
  `ExtendOutcome.Rejected`, `LeaseOwnershipStatus.HELD`, `isStillHeld=true`와
  atomic `releaseCount`를 반환한다. fake는 실제 Redisson client나 backend를
  생성하지 않는다.

  `LeaderKtorTestDoubles.kt`에 이미 있는 `FakeSuspendLeaderElector`와
  `AutoCloseableFakeSuspendLeaderElector`는 그대로 재사용하고, 아래 lease fixture를
  추가한다. Task 2에서 만든 `FakeSuspendLeaderElector`의 `stateReads` 주입값으로
  route guard가 state를 읽은 횟수도 셀 수 있게 한다.

  ```kotlin
  internal class TrackingLeaseHandle(
      override val lockName: String = "job",
      private val released: AtomicInteger = AtomicInteger(),
      private val releaseAction: suspend () -> Unit = {},
  ) : SuspendLeaderLeaseHandle {
      override val auditLeaderId: String = "test-node"
      override val acquiredAt: Instant = Instant.now()
      val releaseCount: Int get() = released.get()
      override suspend fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.Rejected
      override suspend fun ownershipStatus(): LeaseOwnershipStatus = LeaseOwnershipStatus.HELD
      override suspend fun isStillHeld(): Boolean = true
      override suspend fun release() {
          released.incrementAndGet()
          releaseAction()
      }
  }

  internal class CountingLeaseAcquirer(
      private val handle: SuspendLeaderLeaseHandle?,
  ) : SuspendLeaderLeaseAcquirer {
      override val configuredOptions: LeaderElectionOptions = LeaderElectionOptions()
      val acquireCount = AtomicInteger()
      override suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle? {
          acquireCount.incrementAndGet()
          return handle
      }
      override suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle? =
          tryAcquire(slot.lockName)
  }
  ```

- [x] **Step 1: scheduler ownership과 immediate cancellation 테스트를 RED로 추가한다.**

  기존 `Application 종료 시 leaderScheduled job 이 자동 취소된다` 테스트를 먼저
  읽고, 임의의 시간/카운트 polling 대신 `CompletableDeferred` start/stop barrier와
  반환 `Job`의 `isCancelled`/`isCompleted`를 stop 뒤 확인하도록 회귀 assertion을
  고친다. 기존 Redisson/Testcontainers 검증은 별도 순차 integration smoke로
  유지하며 scheduler ownership assertion에는 backend 지연을 섞지 않는다. 임의의
  `+2` 허용치나 고정 sleep은 추가하지 않는다.

  ```kotlin
  @Test
  fun `plugin 설치 후 leaderScheduled job은 resource registry에 등록된다`() = runSuspendIO {
      lateinit var scheduledJob: Job
      testApplication {
          application {
              install(LeaderElectionPlugin) { leaderElection = FakeSuspendLeaderElector() }
              scheduledJob = leaderScheduled("scheduled-job", 10.milliseconds) { }
          }
          startApplication()
      }
      scheduledJob.isCancelled.shouldBeTrue()
      scheduledJob.isCompleted.shouldBeTrue()
  }

  @Test
  fun `plugin 미설치 명시 elector 경로는 registry 없이 Application scope를 사용한다`() = runSuspendIO {
      lateinit var scheduledJob: Job
      testApplication {
          application {
              scheduledJob = leaderScheduled(
                  lockName = "explicit-job",
                  period = 10.milliseconds,
                  leaderElection = FakeSuspendLeaderElector(),
              ) { }
          }
          startApplication()
          scheduledJob.cancel()
          scheduledJob.join()
      }
      scheduledJob.isCancelled.shouldBeTrue()
  }
  ```

  `LeaderKtorTestDoubles.kt`에는 wrapper를 이후 scheduler test도 재사용할 수
  있도록 정의한다. wrapper는 delegate의 모든 elector 동작을 위임하고
  `closeCount`만 세며, plugin이 caller-owned elector에 `close()`를 호출하지
  않는지 확인하는 데만 사용한다. test fake의 `runIfLeader`는
  `delay` 중 cancellation을 그대로 재전파하고, `state`는
  `LeaderState.empty(lockName)`을 반환한다. RED는 scheduler가 registry extension을
  호출하기 전에는 새 ownership assertion을 만족하지 않아야 한다.

- [x] **Step 2: Job 생성 직후 registry에 등록하도록 구현한다.**

  `ApplicationExt.leaderScheduled`의 lock name/period validation과 기존
  `managementRegistry.register(lockName)` 호출을 보존한다. `launch { ... }` 결과를
  local `job`으로 받은 다음 plugin attribute가 있으면
  `leaderElectionResourceRegistryOrNull()?.register(job)`을 호출하고 그 `job`을
  반환한다. registry가 이미 stop 상태이면 등록 호출이 즉시 job을 cancel하므로
  register/stop race가 scheduler를 부활시키지 않는다. plugin이 설치되지 않은
  explicit elector overload는 기존 Application scope만 사용한다. `CancellationException`
  재전파와 일반 exception의 log-and-continue, cycle delay는 변경하지 않는다.

- [x] **Step 3: scheduler와 plugin 기존 회귀를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*ApplicationExtTest' --tests '*LeaderElectionManagementRouteTest' --tests '*LeaderElectionPluginTest' --no-daemon --no-build-cache`

  Expected: 기존 period/blank/exception/cancellation 테스트와 새 registry ownership
  테스트가 PASS한다. action count race가 있으면 polling timeout을 늘리지 말고 Job
  completion과 registry report를 assertion에 사용한다.

- [x] **Step 4: KTOR-01 lifecycle 문서를 갱신하고 child commit을 만든다.**

  `leader-ktor/README.md`와 `.ko.md`에는 `leaderScheduled`가 plugin 설치 시
  application-owned Job으로 취소되며 caller-owned elector/backend는 닫지 않는다는
  예제를 추가한다. EN/KO manual module에는 `ApplicationStopped`, explicit elector
  경계, 정상 contention-null 보존을 각각 설명한다. 문서 명령 토큰과 Kotlin API
  이름은 번역하지 않는다. 다음 검증 후 KTOR-01 대표 commit을 만든다.

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json leader-ktor/README.ko.md
  ./gradlew :bluetape4k-leader-ktor:test --no-daemon --no-build-cache
  ./gradlew :bluetape4k-leader-ktor:compileKotlin :bluetape4k-leader-ktor:compileTestKotlin --no-daemon --no-build-cache
  ./gradlew :bluetape4k-leader-ktor:jar --no-daemon --no-build-cache
  git diff --check
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/ApplicationExt.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/ApplicationExtTest.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderKtorTestDoubles.kt leader-ktor/README.md leader-ktor/README.ko.md docs/manual/en/modules/bluetape4k-leader-ktor.md docs/manual/ko/modules/bluetape4k-leader-ktor.md
  git commit -m "Epic #701 KTOR-01 scheduler와 plugin lifecycle 소유권을 연결한다"
  ```

  commit trailer의 `Tested`에는 테스트 통과 수와 `jar`/`git diff --check` 결과를
  기록하고, 아직 전체 detekt/manual release validator를 실행하지 않았다면
  `Not-tested`에 명시한다.

### Task 4: KTOR-01 exact-head 증거와 child handoff

**KTOR-01 실행 증거 (2026-08-26):** registry/plugin/scheduler 구현과 문서 갱신을
`660fc83b52dfb18a39b80f911da1c017ca3b845f`에 고정했다. `leader-ktor` 전체 test는
45개 PASS했고, `compileKotlin`, `compileTestKotlin`, `jar` 재시도, Korean term audit
(`findings=[]`), `git diff --check`를 통과했다. 최초 `jar` 실행은 Kotlin Gradle plugin
cache의 `KotlinPluginWrapper` 탐색 실패였으나 동일 명령 재시도는 PASS했다.

**Files:**
- Modify: `docs/superpowers/plans/2026-08-26-issue-701-ktor-lifecycle-management-plan.md` (실행 시 체크박스와 evidence만 갱신)
- Read-only: GitHub issue #541, local branch/worktree metadata

- [x] **Step 1: KTOR-01 검증 명령 전체를 순서대로 실행한다.**

  위 공통 명령 6개를 KTOR-01 worktree에서 실행하고, Redisson/Testcontainers
  scheduler 회귀가 있으면 별도 순차 실행한다. 실패한 명령은 통과한 것으로 표시하지
  않고 로그 원인과 재실행 결과를 기록한다.

- [x] **Step 2: public descriptor와 docs inventory를 확인한다.**

  `jar tf`와 `javap`로 `LeaderElectionPluginConfig`, error context/code,
  `LeaderRouteGuardConfig`/authority enum, `LeaderRouteGuardKt`의
  `leaderGuard`/`leaderOnlyRoute`, event-stream registrar JVM owner의 public
  descriptor를 비교한다. 전용 `checkBinaryCompatibility` Gradle task를 만들거나
  호출하지 않는다. manual inventory의 pinned `releaseRef`/`releaseCommit`이
  tag/SHA와 불일치하면 validator 결과를 PASS로 표시하지 않고 manual DoD와
  PR-ready handoff를 보류한다. 코드 반복 작업은 별도로 진행할 수 있지만,
  mismatch를 고치거나 명시적으로 기록하기 전에는 PR body에 완료로 주장하지
  않는다.

  KTOR-01 scope에서 `leader-ktor/build/libs/bluetape4k-leader-ktor-1.0.0.jar`의
  `LeaderElectionPluginConfig`, `ApplicationExtKt`, plugin class와 resource registry
  entries를 `jar tf`/`javap`로 확인했다. `docs/manual/manifest.yaml`은 현재
  `releaseRef=0.5.0`, `releaseCommit=721a9a3808f67489d2bdb8177734325981c24977`로
  pinned 상태이며 이 train에서 임의로 승격하지 않는다. KTOR-02~04가 추가하는
  error/guard/event-stream descriptor와 최종 release inventory는 후속 full-train
  Task 18에서 다시 검증한다.

- [x] **Step 3: exact head를 기록하고 다음 child base를 준비한다.**

  ```bash
  git status --short
  git log -1 --format='%H%n%s'
  git show --stat --oneline HEAD
  gh issue view 541 --json number,state,title,body,comments,labels,milestone
  ```

  clean worktree와 KTOR-01 commit SHA를 기록한 뒤에만 별도 worktree
  `.worktrees/feat/epic-ktor-02-errors`를 다음처럼 만든다. child PR은 아직 만들지 않는다.

  ```bash
  REPO_ROOT=/Users/debop/work/bluetape4k/bluetape4k-leader
  KTOR_01_SHA=$(git rev-parse feat/epic-ktor-01-lifecycle)
  git -C "$REPO_ROOT" worktree add -b feat/epic-ktor-02-errors "$REPO_ROOT/.worktrees/feat/epic-ktor-02-errors" "$KTOR_01_SHA"
  ```

## 4. KTOR-02 — stable error contract (#540)

### Task 5: machine-readable error model과 safe JSON responder

**Files:**
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionError.kt`
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionErrorTest.kt`
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginConfig.kt`

- [ ] **Step 1: code/status/payload parity 테스트를 작성한다.**

  `LeaderElectionErrorTest`는 `LeaderElectionErrorCode`의 다음 순서와 기본
  status를 표로 고정한다: `INVALID_LOCK_NAME=400`, `NOT_LEADER=503`,
  `LEADER_LOCKED=423`, `BACKEND_UNAVAILABLE=503`, `CONFIGURATION=500`,
  `INTERNAL=500`, `INVALID_CURSOR=400`. `ApplicationCall`을 만들지 않는 unit
  test에서는
  `LeaderElectionErrorContext.toJson(exposeLockName = false)`를 호출하여
  backend exception의 class/message가 JSON에 들어가지 않는지 확인한다.

  ```kotlin
  @Test
  fun `기본 payload는 allow-list 필드와 stable status만 포함한다`() {
      val context = LeaderElectionErrorContext(
          code = LeaderElectionErrorCode.BACKEND_UNAVAILABLE,
          message = "leader state is temporarily unavailable",
          status = HttpStatusCode.ServiceUnavailable,
          lockName = "internal-job",
      )

      context.toJson(exposeLockName = false) shouldBeEqualTo
          """{"code":"BACKEND_UNAVAILABLE","message":"leader state is temporarily unavailable","status":503}"""
  }

  @Test
  fun `typed override는 허용된 status와 lockName만 바꿀 수 있다`() {
      val override = LeaderElectionErrorOverride(
          status = HttpStatusCode.Locked,
          exposeLockName = true,
      )
      val context = contextFor(LeaderElectionErrorCode.LEADER_LOCKED)

      context.withOverride(override).toJson(exposeLockName = true) shouldContain "\"lockName\""
      context.withOverride(override).status shouldBeEqualTo HttpStatusCode.Locked
  }

  @Test
  fun `public context와 override는 오류 status allow-list를 강제한다`() {
      assertFailsWith<IllegalArgumentException> {
          LeaderElectionErrorContext(
              code = LeaderElectionErrorCode.INTERNAL,
              message = "hidden",
              status = HttpStatusCode.OK,
          )
      }
      assertFailsWith<IllegalArgumentException> {
          LeaderElectionErrorOverride(status = HttpStatusCode.TooManyRequests)
      }
  }
  ```

  모든 테스트는 Bluetape assertion을 사용한다. 원래 `Throwable`은 public
  context에 보관하지 않고 internal log context에만 전달한다는 assertion도 둔다.
  custom responder는 call/body를 직접 쓰지 않고 typed override만 반환하므로
  allow-list payload 경계를 우회할 수 없음을 테스트한다.

- [ ] **Step 2: error model이 없어 RED가 되는지 확인한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderElectionErrorTest' --no-daemon --no-build-cache`

  Expected: `Unresolved reference: LeaderElectionErrorCode` 또는 동일한 symbol
  부재 컴파일 실패.

- [ ] **Step 3: public context와 internal mapping을 구현한다.**

  `LeaderElectionError.kt`에 다음 API와 기본 문구를 구현한다.

  ```kotlin
  import java.io.Serializable

  enum class LeaderElectionErrorCode {
      INVALID_LOCK_NAME,
      NOT_LEADER,
      LEADER_LOCKED,
      BACKEND_UNAVAILABLE,
      CONFIGURATION,
      INTERNAL,
      INVALID_CURSOR,
  }

  data class LeaderElectionErrorContext(
      val code: LeaderElectionErrorCode,
      val message: String,
      val status: HttpStatusCode,
      val lockName: String? = null,
  ) : Serializable {
      init {
          require(status in LEADER_ELECTION_ERROR_STATUSES) {
              "오류 응답 status는 allow-list에 있어야 합니다: $status"
          }
      }

      fun toJson(exposeLockName: Boolean = false): String = buildStableJson(this, exposeLockName)
      fun withOverride(override: LeaderElectionErrorOverride): LeaderElectionErrorContext =
          copy(status = override.status ?: status, lockName = if (override.exposeLockName) lockName else null)

      private companion object {
          const val serialVersionUID: Long = 1L
      }
  }

  data class LeaderElectionErrorOverride(
      val status: HttpStatusCode? = null,
      val exposeLockName: Boolean = false,
  ) : Serializable {
      init {
          require(status == null || status in LEADER_ELECTION_ERROR_STATUSES) {
              "오류 override status는 allow-list에 있어야 합니다: $status"
          }
      }

      private companion object {
          const val serialVersionUID: Long = 1L
      }
  }

  internal class LeaderElectionHttpException(
      val context: LeaderElectionErrorContext,
      cause: Throwable? = null,
  ) : RuntimeException(context.message, cause)

  fun interface LeaderElectionErrorResponder {
      fun customize(context: LeaderElectionErrorContext): LeaderElectionErrorOverride
  }

  private val LEADER_ELECTION_ERROR_STATUSES = setOf(
      HttpStatusCode.BadRequest,
      HttpStatusCode.Locked,
      HttpStatusCode.ServiceUnavailable,
      HttpStatusCode.InternalServerError,
  )
  ```

  context와 override 모두 `BadRequest`, `Locked`, `InternalServerError`,
  `ServiceUnavailable` 네 값만 허용하고 그 밖에는 생성 시 `require`로
  configuration error를 낸다. `buildStableJson`은 기존 `String.jsonEscape()`만
  사용해 `code`, `message`, `status`, opt-in `lockName` 순서로 출력한다. internal
  `toErrorContext(code, lockName, cause)`는 raw cause/message를 출력하거나
  throwable 자체를 logger에 넘기지 않고 sanitized cause type과 code만 구조화해
  남긴다. 응답에는 class/message/endpoint/credential/leader identity를 복사하지
  않는다.

  새 public enum/data class/functional interface와 KTOR-03에서 노출할 public
  config에는 한국어 KDoc을 함께 작성한다. KDoc은 normal contention-null,
  passive state, authentication boundary, optional dependency 책임을 약속하고
  구현 세부사항이나 backend credential을 예시로 싣지 않는다.

  `LeaderElectionPluginConfig`에는 optional Ktor type을 참조하지 않는 다음
  typed policy만 둔다.

  ```kotlin
  internal var errorResponder: LeaderElectionErrorResponder? = null
  internal var errorOverrides: Map<LeaderElectionErrorCode, LeaderElectionErrorOverride> = emptyMap()
  ```

  policy를 외부에 공개할 필요가 없으면 adapter와 route가 공유하는 internal
  attribute로 저장한다. 기존 config default와 `runIfLeader` API는 수정하지 않는다.

- [ ] **Step 4: fallback responder unit test를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderElectionErrorTest' --no-daemon --no-build-cache`

  Expected: status matrix, JSON escaping, lockName opt-in, forbidden status
  override rejection이 PASS한다.

- [ ] **Step 5: error source를 리팩터하고 독립 commit한다.**

  context에 backend 원인이 저장되지 않는지와 `CancellationException`을
  `INTERNAL`로 매핑하지 않는지 읽기 검토한 후 `git diff --check`를 실행한다.

  ```bash
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionError.kt leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginConfig.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionErrorTest.kt
  git commit -m "Epic #701 오류 code와 안전한 JSON 응답 계약을 고정한다"
  ```

### Task 6: StatusPages adapter와 dependency-light fallback

**Files:**
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/statuspages/LeaderElectionStatusPagesAdapter.kt`
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionStatusPagesAdapterTest.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `leader-ktor/build.gradle.kts`

- [ ] **Step 1: StatusPages가 있는/없는 두 pipeline 테스트를 먼저 작성한다.**

  `LeaderElectionStatusPagesAdapterTest`에는 `testApplication`을 두 번 구성한다.
  첫 번째는 `StatusPages`를 설치하지 않고 route에서
  `respondLeaderElectionError`를 직접 호출해 `ContentType.Application.Json`과
  exact stable body를 확인한다. 두 번째는 `install(StatusPages) {
  leaderElectionErrors() }`를 사용하고 route에서 internal
  `LeaderElectionHttpException`을 throw하여 같은 code/status/body를 확인한다.
  두 테스트 모두 `ContentNegotiation`을 설치하지 않아 converter 부재가 fallback을
  깨지 않는다는 증거를 남긴다.

  ```kotlin
  @Test
  fun `StatusPages 없이도 stable JSON fallback을 반환한다`() = runSuspendIO {
      testApplication {
          application { routing { get("/error") { call.respondLeaderElectionError(contextFor(LeaderElectionErrorCode.NOT_LEADER)) } } }
          val response = client.get("/error")
          response shouldHaveStatus HttpStatusCode.ServiceUnavailable
          response.bodyAsText() shouldBeEqualTo
              """{"code":"NOT_LEADER","message":"leader state does not allow this request","status":503}"""
      }
  }

  @Test
  fun `StatusPages adapter는 예외를 같은 payload로 변환한다`() = runSuspendIO {
      testApplication {
          application {
              install(StatusPages) { leaderElectionErrors() }
              routing { get("/error") { throw LeaderElectionHttpException(contextFor(LeaderElectionErrorCode.BACKEND_UNAVAILABLE)) } }
          }
          val response = client.get("/error")
          response shouldHaveStatus HttpStatusCode.ServiceUnavailable
          response.bodyAsText() shouldContain "\"code\":\"BACKEND_UNAVAILABLE\""
      }
  }
  ```

  테스트 파일은 `ktor-server-status-pages`를 직접 `testImplementation`으로
  사용하고, production plugin/config가 해당 타입을 import하지 않는지 함께
  검사한다.

- [ ] **Step 2: catalog와 Gradle classpath를 RED 상태에서 확인한다.**

  `gradle/libs.versions.toml`에
  `ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages" }`
  alias를 추가하고, `leader-ktor/build.gradle.kts`에는 compileOnly와
  testImplementation을 함께 선언한다. 버전은 개별로 쓰지 않고 기존 Ktor BOM에
  맡긴다. alias가 없던 시점의 test compile 실패가 해결되는지 다음을 실행한다.

  ```bash
  ./gradlew :bluetape4k-leader-ktor:dependencies --configuration testRuntimeClasspath --no-daemon --no-build-cache | rg 'ktor-server-status-pages:3\.5\.2'
  ```

  Expected: `io.ktor:ktor-server-status-pages:3.5.2` 한 줄. 다른 Ktor version
  constraint를 추가하지 않는다.

- [ ] **Step 3: compileOnly adapter와 fallback responder를 구현한다.**

  adapter 파일은 `StatusPagesConfig.leaderElectionErrors()`라는 명시적 설치
  extension을 제공하고 `StatusPagesConfig.exception` handler에서
  `LeaderElectionHttpException` context를 받아 `respondText` fallback responder를
  호출한다. 소비자는 `install(StatusPages) { leaderElectionErrors() }`로 설치한다.
  `StatusPages`가 설치되지 않은 route는 동일한 `respondText` 함수를 직접 사용한다.
  converter가 있더라도 응답 필드는 typed context의 allow-list로만 생성한다.
  detached scheduler exception은 이 adapter에 전달하지 않는다.

  ```kotlin
  public fun StatusPagesConfig.leaderElectionErrors(
      responder: LeaderElectionErrorResponder? = null,
  ) {
      exception<LeaderElectionHttpException> { call, failure ->
          call.respondLeaderElectionError(failure.context, responder)
      }
  }

  internal suspend fun ApplicationCall.respondLeaderElectionError(
      context: LeaderElectionErrorContext,
      responder: LeaderElectionErrorResponder? = null,
  ) {
      val safeContext = responder?.customize(context)?.let(context::withOverride) ?: context
      respondText(safeContext.toJson(), ContentType.Application.Json, safeContext.status)
  }
  ```

  `ktor-server-status-pages`는 adapter의 `compileOnly` classpath에서만 참조한다.
  실제 Ktor 3.5.2 `StatusPagesConfig.exception` signature와 import를 compile
  probe로 고정하고, adapter 외부의 plugin/config/management source에는
  `StatusPages` 타입이 새어 나오지 않게 한다. `respondLeaderElectionError`는
  responder의 typed override만 적용하고 항상 `respondText`로 stable JSON을 써서
  `ContentNegotiation` 유무와 무관하게 동일한 body를 반환한다.

- [ ] **Step 4: 두 pipeline test와 class-loading check를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderElectionStatusPagesAdapterTest' --tests '*LeaderElectionErrorTest' --no-daemon --no-build-cache`

  Expected: StatusPages 설치/미설치 body가 byte-for-byte 동일하고, converter
  미설치에서도 503 JSON이 PASS한다. `rg -n 'statuspages' leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginConfig.kt`는 결과가 없어야 한다.

- [ ] **Step 5: KTOR-02 dependency와 adapter를 commit한다.**

  ```bash
  git add gradle/libs.versions.toml leader-ktor/build.gradle.kts leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/statuspages/LeaderElectionStatusPagesAdapter.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionStatusPagesAdapterTest.kt
  git commit -m "Epic #701 optional StatusPages adapter와 JSON fallback을 연결한다"
  ```

### Task 7: management route failure mapping과 KTOR-02 문서

**Files:**
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementRoute.kt` (`LeaderElectionManagementRegistry` 선언 포함)
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementRouteTest.kt`
- Modify: `leader-ktor/README.md`, `leader-ktor/README.ko.md`, `docs/manual/en/modules/bluetape4k-leader-ktor.md`, `docs/manual/ko/modules/bluetape4k-leader-ktor.md`

- [x] **Step 1: invalid lock/backend/cancellation route 테스트를 RED로 추가한다.**

  management registry에 공백 또는 허용되지 않은 문자의 lock name을 넣는 경로,
  state provider가 `IllegalStateException`을 던지는 경로, request coroutine이
  취소되는 경로를 각각 테스트한다. invalid lock은 400 `INVALID_LOCK_NAME`,
  backend failure는 503 `BACKEND_UNAVAILABLE`, cancellation은 예외 재전파로
  고정한다. 기존 정상 JSON test는 exact body를 유지한다.

- [x] **Step 2: register와 route를 core validation/error mapping에 연결한다.**

  `LeaderElectionManagementRegistry.register`는 기존 blank 검증에 더해 core
  `validateLockName`을 호출한다. route handler는 lock별 state 조회를
  `CancellationException` catch 없이 실행한다. lock validation 구간에서 발생한
  `IllegalArgumentException`만 `INVALID_LOCK_NAME`으로 매핑하고, provider 호출
  이후의 `IllegalArgumentException`을 포함한 backend/state exception은
  `BACKEND_UNAVAILABLE` context로 변환해 `respondLeaderElectionError`를 호출한다.
  exception은 raw message/stack 없이 sanitized cause type과 code만 logger에 남기고
  응답에는 넣지 않는다. 정상 `LeaderStatus.Empty/Occupied`와 기존
  leaderId/leaseExpiry JSON shape은 바꾸지 않는다.

- [x] **Step 3: management 및 전체 KTOR-02 테스트를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderElectionManagementRouteTest' --tests '*LeaderElectionStatusPagesAdapterTest' --no-daemon --no-build-cache`

  Expected: 기존 4개 management test, 새 400/503/cancellation 및 backend
  `IllegalArgumentException` 503 test, StatusPages parity test가 PASS한다. 정상
  `runIfLeader` contention-null을 바꾸는 core diff가 생기면 즉시 되돌리고
  KTOR-02 범위를 유지한다.

- [x] **Step 4: 오류 문서와 child commit을 완료한다.**

  README와 manual EN/KO에 code/status 표, converter 없는 fallback, typed
  override allow-list, backend cause 비노출, detached scheduler 예외의
  log-and-continue를 추가한다. Korean 문장 audit와 `git diff --check`를 실행한다.

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json leader-ktor/README.ko.md
  git diff --check
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementRoute.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementRouteTest.kt leader-ktor/README.md leader-ktor/README.ko.md docs/manual/en/modules/bluetape4k-leader-ktor.md docs/manual/ko/modules/bluetape4k-leader-ktor.md
  git commit -m "Epic #701 management route를 stable error contract에 연결한다"
  ```

### Task 8: KTOR-02 exact-head handoff

**Files:**
- Read-only: KTOR-02 branch status, exact commit, issue #540

- [ ] **Step 1: 공통 검증 6개와 public descriptor를 순서대로 실행한다.**

  `:bluetape4k-leader-ktor:test`, compile, jar, detekt, manual inventory,
  `git diff --check`를 실행한다. 공통 `javap` 목록 외에
  `LeaderElectionStatusPagesAdapterKt`의 `leaderElectionErrors` descriptor를
  확인하고 compileOnly adapter가 core API에 dependency를 새로 새지 않는지
  확인한다. 대표 consumer test source를 `compileTestKotlin`으로 다시 컴파일한다.

- [ ] **Step 2: optional artifact 부재 smoke를 실행한다.**

  Task 6의 `LeaderElectionStatusPagesAdapterTest`에 둔
  `URLClassLoader` smoke를 실행한다. test runtime URL에서
  `ktor-server-status-pages` jar만 제거한 loader로
  `io.bluetape4k.leader.ktor.LeaderElectionPluginKt`와
  `LeaderElectionPluginConfig`를 초기화하면 성공하고, 같은 loader에서
  `LeaderElectionStatusPagesAdapterKt`를 명시적으로 초기화하면
  linkage/configuration error가 발생해야 한다. filtered URL 목록과 두 결과를 test assertion/log에
  남긴다.

- [ ] **Step 3: KTOR-02 exact head를 기록하고 KTOR-03 child base를 만든다.**

  ```bash
  REPO_ROOT=/Users/debop/work/bluetape4k/bluetape4k-leader
  git status --short
  git log -1 --format='%H%n%s'
  gh issue view 540 --json number,state,title,body,comments,labels,milestone
  KTOR_02_SHA=$(git rev-parse feat/epic-ktor-02-errors)
  git -C "$REPO_ROOT" worktree add -b feat/epic-ktor-03-route-guard "$REPO_ROOT/.worktrees/feat/epic-ktor-03-route-guard" "$KTOR_02_SHA"
  ```

  KTOR-03 worktree 생성은 모든 KTOR-02 검증이 PASS하고 clean exact head가
  기록된 뒤에만 실행한다. PR 생성은 아직 하지 않는다.

## 5. KTOR-03 — route-scoped leader guard (#542)

### Task 9: guard config와 passive `STATE` semantics

**Files:**
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuard.kt`
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuardTest.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `leader-ktor/build.gradle.kts`

- [ ] **Step 1: public DSL와 state path 테스트를 RED로 작성한다.**

  테스트는 `testApplication`과 `LocalSuspendLeaderElector` 또는
  `FakeSuspendLeaderElector`를 사용한다. 다음 DSL 및 기본값을 compile-time와
  HTTP behavior로 고정한다.

  ```kotlin
  @Test
  fun `Occupied STATE는 downstream을 실행한다`() = runSuspendIO {
      val downstream = AtomicInteger(0)
      testApplication {
          application {
              install(LeaderElectionPlugin) {
                  leaderElection = FakeSuspendLeaderElector(
                      stateValue = LeaderState.occupied("job", LeaderLease("test-node")),
                  )
              }
              routing {
                  leaderGuard("job") { handle { downstream.incrementAndGet(); call.respondText("ok") } }
              }
          }
          startApplication()
          client.get("/").bodyAsText() shouldBeEqualTo "ok"
      }
      downstream.get() shouldBeEqualTo 1
  }

  @Test
  fun `Empty STATE는 downstream을 호출하지 않고 NOT_LEADER 503을 반환한다`() = runSuspendIO {
      val downstream = AtomicInteger(0)
      testApplication {
          application {
              install(LeaderElectionPlugin) { leaderElection = FakeSuspendLeaderElector() }
              routing {
                  leaderGuard("job") { get { downstream.incrementAndGet(); call.respondText("ok") } }
              }
          }
          startApplication()
          val response = client.get("/")
          response shouldHaveStatus HttpStatusCode.ServiceUnavailable
          response.bodyAsText() shouldContain "\"code\":\"NOT_LEADER\""
      }
      downstream.get() shouldBeEqualTo 0
  }
  ```

  새 DSL이 없으므로 RED는 compile failure여야 한다.

- [ ] **Step 2: auth test에 필요한 test-only alias를 추가하고 의존성을 확인한다.**

  `gradle/libs.versions.toml`에
  `ktor-server-auth = { module = "io.ktor:ktor-server-auth" }`를 추가하고
  `leader-ktor/build.gradle.kts`에는 `testImplementation(libs.ktor.server.auth)`만
  선언한다. runtime API에는 auth provider를 구현하지 않는다. 다음 명령에서 BOM
  3.5.2 resolution을 확인한다.

  ```bash
  ./gradlew :bluetape4k-leader-ktor:dependencyInsight --dependency ktor-server-auth --configuration testRuntimeClasspath --no-daemon --no-build-cache
  ```

- [ ] **Step 3: config와 route-scoped plugin의 최소 API를 구현한다.**

  `LeaderRouteGuard.kt`에는 다음 public 이름과 default를 둔다.

  ```kotlin
  enum class LeaderRouteAuthorityMode { STATE, LEASE }

  class LeaderRouteGuardConfig {
      var authorityMode: LeaderRouteAuthorityMode = LeaderRouteAuthorityMode.STATE
      var rejectionStatus: HttpStatusCode = HttpStatusCode.ServiceUnavailable
      var exposeMetadata: Boolean = false
      var leaseMaxDuration: Duration = 30.seconds
      var stateProvider: ((String) -> LeaderState)? = null
      var leaseAcquirer: SuspendLeaderLeaseAcquirer? = null
      var errorResponder: LeaderElectionErrorResponder? = null
  }

  fun Route.leaderGuard(
      lockName: String,
      configure: LeaderRouteGuardConfig.() -> Unit = {},
      build: Route.() -> Unit = {},
  ): Route

  fun Route.leaderOnlyRoute(lockName: String, build: Route.() -> Unit = {}): Route =
      leaderGuard(lockName, build = build)
  ```

  `leaderGuard`는 `createRouteScopedPlugin`을 통해 child route에 설치하며,
  Ktor 3.5.2의 public `AuthenticationChecked` hook을 통해 인증 검증 이후에만
  state/acquire를 실행한다. `Route.application.leaderElectionPluginConfig()`에서
  기본 elector를 얻는다.
  `lockName`은 route 등록 시 core `validateLockName`으로 확인한다.
  `rejectionStatus`는 KTOR-02 allow-list(`BadRequest`, `Locked`,
  `ServiceUnavailable`, `InternalServerError`)에만 둘 수 있다.
  `STATE`에서 explicit `stateProvider`가 없으면 `elector.supportsAuditLeaderState`
  가 true인지 startup 시점에 검사한다. false인 elector를 Empty로 오인해
  서비스 요청을 계속 거부하지 않고 `IllegalArgumentException` configuration
  error로 시작을 중단한다.

  `AuthenticationChecked` hook은 `stateProvider(lockName)` 또는
  `elector.state(lockName)`를 정확히 한 번 읽는다. `LeaderState.isOccupied`일 때만 downstream을 통과시키고,
  Empty에는 KTOR-02 `NOT_LEADER` context를 `rejectionStatus`와 함께 응답한 뒤
  `finish()`한다. state exception은 `BACKEND_UNAVAILABLE` 503으로 정규화한다.
  `rejectionStatus`는 기본적으로 `NOT_LEADER`에 적용하고, `LEASE`의 null
  acquire는 matrix의 `LEADER_LOCKED` 423을 유지한다. LEASE status를 바꾸려면
  KTOR-02 typed override allow-list를 명시적으로 사용한다.
  기본 `exposeMetadata=false`에서는 leaderId/leaseExpiry/lockName을 응답에 넣지
  않는다.

- [ ] **Step 4: state tests를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderRouteGuardTest' --no-daemon --no-build-cache`

  Expected: Occupied downstream 1회, Empty downstream 0회 + 503,
  backend exception 503, unsupported capability startup failure,
  explicit state provider 허용, default config descriptor가 PASS한다.

- [ ] **Step 5: guard state path를 commit한다.**

  ```bash
  git add gradle/libs.versions.toml leader-ktor/build.gradle.kts leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuard.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuardTest.kt
  git commit -m "Epic #701 passive STATE route guard 계약을 추가한다"
  ```

### Task 10: 명시적 `LEASE` mode와 capability/시간 경계

**Files:**
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuard.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuardTest.kt`

- [ ] **Step 1: acquire/release/timeout 테스트를 RED로 추가한다.**

  fake `SuspendLeaderLeaseAcquirer`와 `SuspendLeaderLeaseHandle`을 만들어
  `tryAcquire` 호출 횟수, `release` 횟수, downstream 실행 횟수를 atomic하게
  센다. `TrackingLeaseHandle`은 `releaseAction: suspend () -> Unit`을 받아
  정상 완료, `awaitCancellation()` hang, 예외 release를 같은 fixture로 만든다.
  다음 동작을 독립 test로 둔다.

  ```kotlin
  @Test
  fun `기본 STATE mode는 lease acquire를 호출하지 않는다`() = runSuspendIO {
      val handle = TrackingLeaseHandle()
      val acquirer = CountingLeaseAcquirer(handle)
      val response = runGuardRequest(
          elector = FakeSuspendLeaderElector(
              stateValue = LeaderState.occupied("job", LeaderLease("test-node")),
          ),
          guard = { leaseAcquirer = acquirer },
      )
      response shouldHaveStatus HttpStatusCode.OK
      acquirer.acquireCount.get() shouldBeEqualTo 0
  }

  @Test
  fun `LEASE contention은 LEADER_LOCKED 423이고 release하지 않는다`() = runSuspendIO {
      val acquirer = CountingLeaseAcquirer(handle = null)
      val response = runGuardRequest(
          elector = FakeSuspendLeaderElector(
              stateValue = LeaderState.occupied("job", LeaderLease("test-node")),
          ),
          guard = {
              authorityMode = LeaderRouteAuthorityMode.LEASE
              leaseAcquirer = acquirer
          },
      )
      response shouldHaveStatus HttpStatusCode.Locked
      acquirer.acquireCount.get() shouldBeEqualTo 1
  }

  @Test
  fun `LEASE downstream cancellation과 timeout에서도 release는 한 번이다`() = runSuspendIO {
      val handle = TrackingLeaseHandle()
      assertFailsWith<CancellationException> {
          runGuardRequest(
              elector = FakeSuspendLeaderElector(
                  stateValue = LeaderState.occupied("job", LeaderLease("test-node")),
              ),
              guard = {
                  authorityMode = LeaderRouteAuthorityMode.LEASE
                  leaseAcquirer = CountingLeaseAcquirer(handle)
                  leaseMaxDuration = 50.milliseconds
              },
              downstream = { delay(250.milliseconds) },
          )
      }
      handle.releaseCount shouldBeEqualTo 1
  }

  @Test
  fun `release가 hang해도 leaseMaxDuration 뒤 원래 cancellation을 보존한다`() = runSuspendIO {
      val releaseStarted = CompletableDeferred<Unit>()
      val handle = TrackingLeaseHandle(releaseAction = {
          releaseStarted.complete(Unit)
          awaitCancellation()
      })
      val cancellation = assertFailsWith<CancellationException> {
          runLeaseGuardForTest(
              handle = handle,
              leaseMaxDuration = 50.milliseconds,
              downstream = { throw CancellationException("downstream cancelled") },
          )
      }
      releaseStarted.await()
      cancellation.message shouldBeEqualTo "downstream cancelled"
      handle.releaseCount shouldBeEqualTo 1
  }

  @Test
  fun `release failure는 성공 응답을 대체하지 않고 한 번만 기록한다`() = runSuspendIO {
      val handle = TrackingLeaseHandle(releaseAction = { error("release failed") })
      runLeaseGuardForTest(
          handle = handle,
          leaseMaxDuration = 50.milliseconds,
          downstream = { respondText("ok") },
      ).status shouldBeEqualTo HttpStatusCode.OK
      handle.releaseCount shouldBeEqualTo 1
  }
  ```

  `runGuardRequest`는 `LeaderRouteGuardTest` 안에 `testApplication`을 생성하고
  `client.get("/")`를 실행하는 작은 fixture로 정의한다. fixture는 다음처럼
  elector, config lambda, downstream suspend action을 명시적으로 받는다.

  ```kotlin
  private suspend fun runGuardRequest(
      elector: SuspendLeaderElector,
      guard: LeaderRouteGuardConfig.() -> Unit,
      downstream: suspend ApplicationCall.() -> Unit = { respondText("ok") },
  ): HttpResponse {
      lateinit var result: HttpResponse
      testApplication {
          application {
              install(LeaderElectionPlugin) { leaderElection = elector }
              routing {
                  leaderGuard("job", configure = guard) { get { downstream() } }
              }
          }
          startApplication()
          result = client.get("/")
      }
      return result
  }

  private suspend fun runLeaseGuardForTest(
      handle: SuspendLeaderLeaseHandle,
      leaseMaxDuration: Duration,
      downstream: suspend ApplicationCall.() -> Unit,
  ): HttpResponse = runGuardRequest(
      elector = FakeSuspendLeaderElector(
          stateValue = LeaderState.occupied("job", LeaderLease("test-node")),
      ),
      guard = {
          authorityMode = LeaderRouteAuthorityMode.LEASE
          leaseAcquirer = CountingLeaseAcquirer(handle)
          this.leaseMaxDuration = leaseMaxDuration
      },
      downstream = downstream,
  )
  ```

  RED는 config의 `LEASE` branch 부재 또는 새 route behavior 부재로 확인한다.

- [ ] **Step 2: lease capability 검증을 구현한다.**

  `LEASE` mode에서 explicit `leaseAcquirer`가 있으면 그것을 사용하고, 없으면
  elector를 `SuspendLeaderLeaseAcquirerSupport` 또는
  `SuspendLeaderLeaseAcquirer`로 해석한다. support의
  `leaseCapabilityAvailable=false`는 configuration error로 거부한다. acquirer가
  없으면 `LEASE` mode를 조용히 STATE로 낮추지 않고 configuration error를 낸다.
  `leaseMaxDuration`는 finite positive만 허용한다.

- [ ] **Step 3: request pipeline에 bounded lease를 구현한다.**

  route-scoped plugin의 call interceptor는 `withTimeout(leaseMaxDuration)` 안에서
  `tryAcquire(lockName)`을 호출한다. null이면 `LEADER_LOCKED` 423을 응답하고
  downstream을 실행하지 않는다. handle을 얻으면 downstream 처리 전체를
  try/finally로 감싸고 `release()`를 한 번 호출한다. `leaseMaxDuration`는
  이미 공개된 finite positive request bound이며 별도 release timeout property를
  추가하지 않는다. finally의 cleanup은 정확히
  `withContext(NonCancellable) { withTimeout(leaseMaxDuration) { handle.release() } }`
  순서로 실행해 cancellation 중에도 유한 시간 안에 끝낸다. release가 timeout 또는
  예외로 실패하면 logger에 구조화해 남기고, downstream의 원래
  `CancellationException`/실패를 대체하지 않는다. 정상 downstream 성공은 release
  실패 때문에 HTTP 오류로 바꾸지 않는다. `STATE` branch에서는 이 interceptor가
  acquire를 호출하지 않는다. 테스트 fixture에는 hanging release와 release failure를
  각각 두어 위 bound, exactly-once, 원래 cancellation 우선순위를 직접 검증한다.

- [ ] **Step 4: lease tests와 KTOR-02 error tests를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderRouteGuardTest' --tests '*LeaderElectionErrorTest' --no-daemon --no-build-cache`

  Expected: STATE acquire 0, LEASE null 423, acquired downstream success,
  timeout/cancellation의 `CancellationException` 재전파와 release 1,
  unsupported capability startup failure가 PASS한다.

- [ ] **Step 5: public descriptor와 cancellation behavior를 검토하고 commit한다.**

  `javap`로 `LeaderRouteAuthorityMode`, `LeaderRouteGuardConfig`,
  `LeaderRouteGuardKt`의 `Route.leaderGuard`/`leaderOnlyRoute` overload를 확인한다.
  기존
  `runIfLeader`의 contention-null을 호출하는 source가 생기지 않았는지 `rg`로
  확인하고 Lore commit을 만든다.

  ```bash
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuard.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuardTest.kt
  git commit -m "Epic #701 opt-in LEASE route guard의 bounded release를 고정한다"
  ```

### Task 11: auth nesting와 KTOR-03 보안 문서

**Files:**
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuardTest.kt`
- Modify: `leader-ktor/README.md`, `leader-ktor/README.ko.md`, `docs/manual/en/modules/bluetape4k-leader-ktor.md`, `docs/manual/ko/modules/bluetape4k-leader-ktor.md`

- [ ] **Step 1: 인증·인가·rate-limit pipeline 회귀를 RED로 작성한다.**

  `install(Authentication) { basic("test") { validate { ... } } }`를 사용하고
  route를 정확히 `authenticate("test") { leaderGuard("job") { ... } }`로
  중첩한다. unauthenticated 요청은 401, authenticated Empty 요청은 503,
  authenticated Occupied 요청은 downstream status가 되어야 한다. state provider
  fake의 count를 읽어 401 경로에서 0인지 assertion한다. 별도 route에서는
  rate-limit 또는 authorization interceptor가 403을 먼저 반환할 때 guard가
  state를 호출하지 않는지 확인한다.

  ```kotlin
  @Test
  fun `인증 실패 요청은 state provider를 호출하지 않는다`() = runSuspendIO {
      val stateReads = AtomicInteger(0)
      testApplication {
          application {
              install(Authentication) { basic("test") { validate { UserIdPrincipal(it.name) } } }
              install(LeaderElectionPlugin) { leaderElection = countingStateFake(stateReads) }
              routing {
                  authenticate("test") {
                      leaderGuard("job") { get { call.respondText("ok") } }
                  }
              }
          }
          startApplication()
          client.get("/") shouldHaveStatus HttpStatusCode.Unauthorized
      }
      stateReads.get() shouldBeEqualTo 0
  }
  ```

- [ ] **Step 2: route-scoped plugin 순서를 수정하여 테스트를 GREEN으로 만든다.**

  guard는 `authenticate { leaderGuard { ... } }` route nesting에서 인증/인가
  rejection 뒤에만 실행되도록 Ktor 3.5.2 public `AuthenticationChecked` hook을
  사용한다. `onCall`/`Plugins` phase는 인증 전에 실행되므로 사용하지 않는다.
  guard 자체는 authentication, authorization, rate-limit을 설치하거나 우회하지
  않는다. 실제 phase trace와 compile probe를 남기고, 401/403에서는 state provider와
  lease acquirer가 모두 0회인지 유지한다.

- [ ] **Step 3: 보안 문서와 child commit을 완료한다.**

  README/manual에 다음 문장을 예제와 함께 기록한다: guard는 passive state
  기준 데이터 확인일 뿐 요청 전체의 lease 보호가 아니며, 원자적 실행 보호에는
  `@LeaderElection`, 요청별 lease에는 명시적 bounded `LEASE` mode를 사용한다.
  auth boundary는 caller 책임이고 default metadata/lockName header/body는
  비노출이다. Korean term audit, targeted tests, `git diff --check` 후 commit한다.

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json leader-ktor/README.ko.md
  ./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderRouteGuardTest' --no-daemon --no-build-cache
  git diff --check
  git add leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderRouteGuardTest.kt leader-ktor/README.md leader-ktor/README.ko.md docs/manual/en/modules/bluetape4k-leader-ktor.md docs/manual/ko/modules/bluetape4k-leader-ktor.md
  git commit -m "Epic #701 route guard의 인증 순서와 보안 경계를 문서화한다"
  ```

### Task 12: KTOR-03 exact-head handoff

**Files:**
- Read-only: KTOR-03 branch status, issue #542, public `javap` output

- [ ] **Step 1: 공통 검증과 route guard focused evidence를 수집한다.**

  전체 Ktor test, compile, jar, detekt, manual inventory, `git diff --check`와
  `LeaderRouteGuardTest`를 실행한다. `STATE`/`LEASE` authority mode, 401/403
  short-circuit, capability startup error, release count를 PR용 표로 기록한다.

- [ ] **Step 2: KTOR-03 exact head를 기록하고 event child 직전 #535를 재확인한다.**

  ```bash
  REPO_ROOT=/Users/debop/work/bluetape4k/bluetape4k-leader
  git status --short
  git log -1 --format='%H%n%s'
  gh issue view 542 --json number,state,title,body,comments,labels,milestone
  gh issue view 535 --json number,state,title,body,comments,labels,milestone
  KTOR_03_SHA=$(git rev-parse feat/epic-ktor-03-route-guard)
  git -C "$REPO_ROOT" worktree add -b feat/epic-ktor-04-event-stream "$REPO_ROOT/.worktrees/feat/epic-ktor-04-event-stream" "$KTOR_03_SHA"
  ```

  #535의 event shape 변경이 있으면 KTOR-04 payload mapping을 새 exact source에
  맞춰 계획과 테스트를 먼저 갱신한다. WebSocket API가 #539의 범위를 깨뜨리는
  경우 조용히 확장하지 않고 issue #539에 후속 issue를 만들고 SSE-only child로
  범위를 고정한다.

## 6. KTOR-04 — bounded event stream (#539)

### Task 13: event shape 재확인과 config/JSON payload

**Files:**
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamConfig.kt`
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamPayload.kt`
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamPayloadTest.kt`
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginConfig.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginTest.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderKtorTestDoubles.kt` — `FakePublisher`와 publisher-backed elector.

- [ ] **Step 1: #535와 현재 core sealed event를 live/read-only로 대조한다.**

  KTOR-04 worktree에서 `gh issue view 535 --json number,state,title,body,comments`
  를 실행하고, exact base의
  `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionListener.kt`에서
  `LeaderElectionEvent`의 모든 subtype와 `leaderId`, `leaseExpiry`, `leader`
  property를 확인한다. 변경이 있으면 아래 payload test expected shape을 그
  exact source에 맞추고 plan evidence를 갱신한다. core event model을 수정하지 않는다.

- [ ] **Step 2: config default/validation 테스트를 RED로 작성한다.**

  다음 기본값과 범위를 exact assertion으로 고정한다.

  ```kotlin
  @Test
  fun `event stream defaults are safe and bounded`() {
      val config = LeaderEventStreamConfig()
      config.eventStreamRouteEnabled.shouldBeFalse()
      config.eventStreamRoutePath shouldBeEqualTo "/management/leaderElection/events"
      config.eventStreamSseEnabled.shouldBeTrue()
      config.eventStreamWebSocketEnabled.shouldBeFalse()
      config.eventStreamAllLocksEnabled.shouldBeFalse()
      config.eventStreamExposeLockName.shouldBeFalse()
      config.eventStreamExposeLeaderMetadata.shouldBeFalse()
      config.eventStreamReplayCapacity shouldBeEqualTo 32
      config.eventStreamMaxConnections shouldBeEqualTo 128
      config.eventStreamHeartbeat shouldBeEqualTo 15.seconds
  }

  @Test
  fun `capacity는 0부터 1024까지만 허용한다`() {
      assertFailsWith<IllegalArgumentException> { LeaderEventStreamConfig(eventStreamReplayCapacity = -1) }
      assertFailsWith<IllegalArgumentException> { LeaderEventStreamConfig(eventStreamReplayCapacity = 1025) }
  }

  @Test
  fun `capacity 0은 cursor를 검증한 뒤 replay gap 없이 live-only로 둔다`() = runTest {
      val hub = LeaderEventStreamHub(FakePublisher(), capacity = 0, scope = backgroundScope)
      hub.awaitStarted()
      hub.replay(afterSequence = 1) shouldBeEqualTo emptyList()
  }
  ```

  `LeaderElectionPluginConfig` default getter에도 같은 values를 확인한다. 이
  시점에는 새 config가 없으므로 RED 컴파일 실패가 예상된다.

- [ ] **Step 3: config와 safe payload를 구현한다.**

  `LeaderEventStreamConfig`의 property 이름은 설계 승인 값에 보완된
  `eventStreamRouteEnabled`, `eventStreamRoutePath`, `eventStreamSseEnabled`,
  `eventStreamWebSocketEnabled`, `eventStreamAllLocksEnabled`,
  `eventStreamExposeLockName`, `eventStreamExposeLeaderMetadata`,
  `eventStreamReplayCapacity`, `eventStreamMaxConnections`,
  `eventStreamHeartbeat`로 고정한다. `eventStreamExposeLockName` 기본값은
  false이며 all-lock opt-in은 이 값을 true로 명시하지 않으면 configuration
  error로 거부한다. route enabled인데 SSE/WS가 모두 false인 조합,
  blank/non-slash path, `eventStreamReplayCapacity`의 `0..1024` 밖 값,
  `eventStreamMaxConnections`의 `1..1024` 밖 값, non-finite/non-positive
  heartbeat를 configuration error로 거부한다.

  내부 bootstrap에 전달할 config는 다음 생성자와 동일한 property를 사용한다.

  ```kotlin
  internal data class LeaderEventStreamConfig(
      val eventStreamRouteEnabled: Boolean = false,
      val eventStreamRoutePath: String = "/management/leaderElection/events",
      val eventStreamSseEnabled: Boolean = true,
      val eventStreamWebSocketEnabled: Boolean = false,
      val eventStreamAllLocksEnabled: Boolean = false,
      val eventStreamExposeLockName: Boolean = false,
      val eventStreamExposeLeaderMetadata: Boolean = false,
      val eventStreamReplayCapacity: Int = 32,
      val eventStreamMaxConnections: Int = 128,
      val eventStreamHeartbeat: Duration = 15.seconds,
  )
  ```

  `LeaderElectionPluginConfig`는 이 값을 mutable plugin DSL property로 보유하고
  plugin body에서 immutable `LeaderEventStreamConfig`로 복사한다.

  payload는 core event를 다음 JSON으로 변환한다. `lockName`은
  `eventStreamExposeLockName=true`일 때만 추가하며, all-lock mode에서는 이
  opt-in을 함께 요구한다.

  ```json
  {"type":"Elected","sequence":7}
  ```

  `eventStreamExposeLockName=true`일 때 payload는
  `{"type":"Elected","sequence":7,"lockName":"batch-job"}`가 된다.
  `eventStreamExposeLeaderMetadata=true`일 때만 `leaderId`, `leaseExpiry`를
  추가한다. `LeaderLease` 전체와 backend address는 직렬화하지 않는다. heartbeat
  및 replay gap은 각각 `{"event":"heartbeat"}`와
  `{"event":"replay_gap","from":...,"to":...}` control payload로
  만든다. 모든 문자열은 `LeaderJsonSupport.jsonEscape()`를 사용한다.

- [ ] **Step 4: payload/config test를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderEventStreamPayloadTest' --tests '*LeaderElectionPluginTest' --no-daemon --no-build-cache`

  Expected: default/invalid config, Elected/Revoked/Skipped payload, metadata
  opt-in, escaping, heartbeat/replay_gap control JSON이 PASS한다.

- [ ] **Step 5: KTOR-04 config/payload commit을 만든다.**

  ```bash
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamConfig.kt leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamPayload.kt leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginConfig.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamPayloadTest.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginTest.kt
  git commit -m "Epic #701 bounded event stream 설정과 안전한 payload를 고정한다"
  ```

### Task 14: sequence ring buffer와 replay/live atomic handoff

**Files:**
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamHub.kt`
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamHubTest.kt`

- [ ] **Step 1: hub unit test를 RED로 작성한다.**

  fake `LeaderElectionEventPublisher`는 `MutableSharedFlow<LeaderElectionEvent>`를
  `events`로 제공한다. `runTest`에서 다음을 각각 검증한다.

  ```kotlin
  @Test
  fun `sequence는 monotonic이고 capacity를 넘으면 오래된 replay를 버린다`() = runTest {
      val publisher = FakePublisher()
      val hub = LeaderEventStreamHub(publisher, capacity = 2, scope = backgroundScope)
      hub.awaitStarted()
      publisher.emit(LeaderElectionEvent.Skipped("a"))
      publisher.emit(LeaderElectionEvent.Skipped("b"))
      publisher.emit(LeaderElectionEvent.Skipped("c"))
      hub.replay(afterSequence = null).filterIsInstance<LeaderStreamItem.Event>().map { it.sequence } shouldBeEqualTo listOf(2L, 3L)
  }

  @Test
  fun `replay과 live handoff에는 gap과 duplicate가 없다`() = runTest {
      val publisher = FakePublisher()
      val hub = LeaderEventStreamHub(publisher, capacity = 8, scope = backgroundScope)
      hub.awaitStarted()
      val observed = mutableListOf<Long>()
      val collector = launch {
          hub.subscribe(lockName = "job", afterSequence = null).take(3).collect {
              if (it is LeaderStreamItem.Event) observed += it.sequence
          }
      }
      hub.awaitSubscriberCount(1)
      publisher.emit(LeaderElectionEvent.Skipped("job"))
      publisher.emit(LeaderElectionEvent.Revoked("job"))
      publisher.emit(LeaderElectionEvent.Skipped("job"))
      collector.join()
      observed shouldBeEqualTo listOf(1L, 2L, 3L)
  }

  @Test
  fun `보존 범위를 벗어난 cursor는 replay_gap control event를 만든다`() = runTest {
      val publisher = FakePublisher()
      val hub = LeaderEventStreamHub(publisher, capacity = 2, scope = backgroundScope)
      hub.awaitStarted()
      repeat(4) { publisher.emit(LeaderElectionEvent.Skipped("job")) }
      val items = hub.replay(afterSequence = 0L)
      items.first().kind shouldBeEqualTo LeaderStreamItem.Kind.REPLAY_GAP
      items.drop(1).filterIsInstance<LeaderStreamItem.Event>().map { it.sequence } shouldBeEqualTo listOf(3L, 4L)
  }
  ```

  `FakePublisher`, `LeaderStreamItem`(`Event`/`Control`와 `Kind`),
  `replay`/`subscribe`의 반환 타입은 같은 Task의 hub 구현에서 정의한다.
  `FakePublisher`는 hub upstream collector가 시작되면 완료하는
  `started: CompletableDeferred<Unit>`을 노출하고, hub는 이를 기다리는
  `awaitStarted()`와 subscriber 등록을 확인하는 test-only
  `awaitSubscriberCount(expected: Int)` barrier를 제공한다. hub가 아직 없으므로
  RED는 symbol 부재 컴파일 실패다.

- [ ] **Step 2: bounded hub 자료구조를 구현한다.**

  `LeaderEventStreamHub`는 `LeaderElectionEventPublisher`를 한 번만 collect하는
  application-scope `Job`, `ArrayDeque<SequencedLeaderEvent>` ring buffer와
  subscriber별 `Channel<LeaderStreamItem>`만 가진다. `MutableSharedFlow`를 live
  handoff에 사용하지 않는다. upstream collector가 시작되면 `started` barrier를
  완료하며, test는 첫 emit 전에 `awaitStarted()`를 호출한다. `Mutex` 하나의
  critical section에서 sequence 증가, ring append/eviction, subscriber 등록,
  replay 목록 enqueue와 live channel fan-out을 모두 수행한다. 따라서
  subscribe는 channel을 등록하고 replay를 enqueue한 뒤 channel을 소비하며,
  publish는 같은 mutex에서 바로 등록된 channel에 전송해 replay/live 사이의
  gap과 duplicate를 막는다.

  subscriber별 channel은 `capacity = config.eventStreamReplayCapacity.coerceAtLeast(1)`
  및 `onBufferOverflow = DROP_OLDEST`로 bounded 처리하고 drop count를 logger와
  metric에 남긴다. `eventStreamReplayCapacity=0`은 ring replay를 끄고 live
  channel만 유지하는 명시적 모드다. 이 모드에서 유효한 cursor는 replay 없이
  live-only로 처리하며 `replay_gap`도 생성하지 않는다. 총 연결 수는
  `eventStreamMaxConnections` admission semaphore로 제한하며, permit 초과는
  `BACKEND_UNAVAILABLE` 503과 안정적인 capacity metric으로 응답한다. disconnect,
  application stop, adapter failure는 반드시 permit을 반환한다. close는
  collector와 subscriber channel을 idempotent하게 종료하고 이미 드레인한
  entry를 다시 닫지 않는다.

  hub가 외부 route에 노출하는 item shape은 다음으로 고정한다.

  ```kotlin
  internal sealed interface LeaderStreamItem {
      val kind: Kind
      enum class Kind { EVENT, HEARTBEAT, REPLAY_GAP }
      data class Event(val sequence: Long, val event: LeaderElectionEvent) : LeaderStreamItem {
          override val kind: Kind = Kind.EVENT
      }
      data class Control(val control: Kind, val from: Long? = null, val to: Long? = null) : LeaderStreamItem {
          override val kind: Kind get() = control
      }
  }
  ```

  `replay(afterSequence)`는 `List<LeaderStreamItem>`을, `subscribe`는
  `Flow<LeaderStreamItem>`을 반환하며, 정상 event만 monotonic sequence를 가진다.
  `LeaderStreamItem`과 shutdown report는 `internal`로 유지해 불필요한
  serialization 계약을 만들지 않는다.

- [ ] **Step 3: cursor/filter/replay gap 동작을 구현한다.**

  SSE의 `Last-Event-ID`와 WebSocket의 `afterSequence`를 hub API의
  `afterSequence: Long?`로 통일한다. 연결 전에 단일 non-negative decimal
  parser로 검증하며, 빈 값은 cursor 없음으로 취급하고 비수치·음수·overflow·중복
  값은 `INVALID_CURSOR` 400으로 거부한다. 미래 cursor는 replay 없이 현재 이후
  live만 받는 의미로 고정한다. cursor가 ring의 earliest보다 오래되면 replay
  목록 앞에 `replay_gap` control을 한 번 넣고 보존된 최신 event부터 보낸다.
  `lockName` filter는 event에 적용하고 all-lock 모드가 꺼져 있으면 null filter를
  거부한다. sequence는 연결마다 재시작하지 않고 application hub에서 monotonic하게
  유지한다.

- [ ] **Step 4: hub tests를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderEventStreamHubTest' --no-daemon --no-build-cache`

  Expected: sequence/replay capacity, atomic handoff, cursor gap, lock filter,
  bounded slow consumer, idempotent close가 PASS한다. handoff interleaving test는
  동일 barrier를 100회 반복해 contiguous sequence·duplicate/loss 부재를 확인한다.
  flake가 생기면 sleep을 늘리지 말고 `CompletableDeferred` barrier와 `runTest`
  virtual time으로 동기화한다.

- [ ] **Step 5: hub를 registry와 연결하기 전 독립 commit을 만든다.**

  ```bash
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamHub.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamHubTest.kt
  git commit -m "Epic #701 event hub의 sequence와 bounded replay를 구현한다"
  ```

### Task 15: plugin bootstrap과 optional class-loading 격리

**Files:**
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamBootstrap.kt`
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt`
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderOptionalClasspathSmokeTest.kt`

- [ ] **Step 1: publisher/flag/bootstrap test를 RED로 작성한다.**

  `LeaderElectionPlugin`에 `eventStreamRouteEnabled=false`인 기본 설치를 하고
  publisher가 있어도 collector/route가 생성되지 않는지 확인한다. flag를 true로
  켜고 elector가 `LeaderElectionEventPublisher`가 아니면 configuration error,
  SSE와 WebSocket flag가 모두 false면 configuration error를 확인한다. flag를
  true로 켰지만 `routing { authenticate { leaderElectionEventStream() } }`를
  등록하지 않은 앱은 startup configuration error가 되어 공개 root endpoint가
  생기지 않는지 확인한다. publisher를 제공하고 caller-owned authenticated route를
  등록하면 hub가 한 번 collect되고 KTOR-01 registry의 `ApplicationStopped`에서
  collector가 cancel되는지 test double로 확인한다.

- [ ] **Step 2: optional adapter class 이름과 reflection boundary를 고정한다.**

  항상 로드되는 bootstrap은 SSE/WebSocket 타입을 import하지 않고 다음 문자열만
  사용한다.

  ```kotlin
  private const val SSE_ADAPTER = "io.bluetape4k.leader.ktor.stream.sse.LeaderEventSseAdapter"
  private const val WEBSOCKET_ADAPTER = "io.bluetape4k.leader.ktor.stream.websocket.LeaderEventWebSocketAdapter"
  ```

  flag가 켜진 경우 항상 로드되는 bootstrap이 제공하는
  `Route.leaderElectionEventStream()`을 caller가 원하는
  `authenticate { ... }`/인가 route 안에서 호출한다. bootstrap은
  `Class.forName`으로 adapter object의
  `install(Route, LeaderEventStreamHub, LeaderEventStreamConfig)`를 호출한다.
  class가 없거나 `LinkageError`, initializer failure,
  `InvocationTargetException`의 원인이 plugin 누락/configuration failure이면
  원인을 `CONFIGURATION`으로 정규화한 `LeaderElectionConfigurationException`을
  낸다. parent classloader가 optional jar를 우연히 찾지 않도록 filtered
  `URLClassLoader` smoke에서 adapter class와 plugin class를 각각 차단한다. flag가
  꺼진 경우 Class.forName을 호출하지 않는다.

- [ ] **Step 3: plugin-owned hub와 registry registration을 구현한다.**

  `LeaderElectionPlugin` body는 config flag를 검증한 뒤 publisher cast를 하고,
  `LeaderEventStreamHub`를 Application scope에서 만든다. hub collector와 adapter
  가 보유한 collector close handle을 KTOR-01
  `leaderElectionResourceRegistryOrNull()`에 등록한다. route는 plugin이
  application root에 자동 등록하지 않고 caller-owned
  `Route.leaderElectionEventStream()`에서만 등록한다. 이 registrar는 반드시
  `authenticate { ... }` 또는 명시적 authorization route 내부에서 호출하도록
  하고, 호출되지 않은 enabled flag는 `ApplicationStarted`에서
  `CONFIGURATION`으로 거부한다. 등록 count는 exactly-one으로 선형화하며 중복
  등록도 configuration error다. caller-owned elector/publisher/backend는
  registry에 넣지 않는다. ApplicationStopped에서 registry close가 먼저 hub
  collector를 cancel하고 session은 각 connection scope가 정리하도록 한다.
  `eventStreamRouteEnabled=false`이면 publisher가 존재해도 hub·collector·route를
  생성하지 않는다.

- [ ] **Step 4: classpath smoke와 plugin tests를 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderOptionalClasspathSmokeTest' --tests '*LeaderElectionPluginTest' --no-daemon --no-build-cache`

  Expected: optional jar를 제외한 URLClassLoader에서도
  `LeaderElectionPlugin`/config/management class가 로드되고, flag-on missing
  adapter는 configuration error, flag-off no-op은 PASS한다. smoke가 parent
  classloader에서 optional jar를 우연히 찾지 않도록 filtered URL 목록을 assertion에
  기록한다.

- [ ] **Step 5: bootstrap isolation을 검토하고 commit한다.**

  ```bash
  rg -n 'ktor.server.(sse|websocket)' leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderEventStreamBootstrap.kt leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt
  git diff --check
  git add leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamBootstrap.kt leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderOptionalClasspathSmokeTest.kt
  git commit -m "Epic #701 event stream optional class-loading 경계를 격리한다"
  ```

  첫 `rg`는 항상 로드되는 두 파일에서 0건이어야 한다. adapter 파일의 import는
  다음 Task에서만 추가한다.

### Task 16: SSE adapter와 route contract

**Files:**
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/sse/LeaderEventSseAdapter.kt`
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamRouteTest.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `leader-ktor/build.gradle.kts`

- [ ] **Step 1: SSE route test를 RED로 작성한다.**

  `testApplication`에서 `install(SSE)`가 있는 runtime test를 구성하고
  `eventStreamRouteEnabled=true`, `eventStreamSseEnabled=true`, lockName query를
  사용한다. route는 반드시 `authenticate("test") {
  leaderElectionEventStream() }` 안에 등록한다. fake publisher가 Elected event를
  emit하면 client가 blank-delimited SSE frame의 `id` sequence, `event` type,
  data JSON을 읽어 확인한다. `Last-Event-ID`를 보내면 해당 cursor 이후 event만
  받고, 오래된 cursor는 `replay_gap` frame을 먼저 받는다. 잘못된 lockName은 400
  `INVALID_LOCK_NAME`, lockName 누락은 all-lock opt-in이 아니면 같은 400으로
  응답한다. 미인증 요청은 401이며 hub subscriber 수가 0인 것도 확인한다.

  ```kotlin
  @Test
  fun `SSE는 lock filter와 Last-Event-ID replay를 적용한다`() = runSuspendIO {
      val publisher = FakePublisher()
      testApplication {
          application { install(SSE); installEventStreamForTest(publisher) }
          startApplication()
          publisher.started.await()
          publisher.emit(LeaderElectionEvent.Skipped("other"))
          publisher.emit(LeaderElectionEvent.Skipped("job"))
          publisher.emit(LeaderElectionEvent.Revoked("job"))
          val response = client.prepareGet("/management/leaderElection/events?lockName=job") {
              header("Last-Event-ID", "1")
          }.execute()
          val channel = response.bodyAsChannel()
          val firstFrame = readSseFrame(channel)
          firstFrame shouldContain "id: 2"
          firstFrame shouldContain "event: Skipped"
          firstFrame shouldContain "\"type\":\"Skipped\""
          val secondFrame = readSseFrame(channel)
          secondFrame shouldContain "id: 3"
          (firstFrame + secondFrame) shouldNotContain "other"
          response.close()
      }
  }

  @Test
  fun `인증 없는 event stream 요청은 401이고 subscriber를 만들지 않는다`() = runSuspendIO {
      val publisher = FakePublisher()
      testApplication {
          application { install(SSE); installEventStreamForTest(publisher) }
          startApplication()
          val response = client.get("/management/leaderElection/events?lockName=job")
          response shouldHaveStatus HttpStatusCode.Unauthorized
          eventStreamHubForTest().activeSubscriberCount shouldBeEqualTo 0
      }
  }

  private suspend fun readSseFrame(channel: ByteReadChannel): String {
      val lines = buildList {
          while (true) {
              val line = channel.readUTF8Line() ?: break
              if (line.isEmpty()) break
              add(line)
          }
      }
      return lines.joinToString("\n")
  }
  ```

  `installEventStreamForTest(publisher)`는 `install(Authentication) {
  basic("test") { validate { UserIdPrincipal(it.name) } } }`와 plugin config를
  설치하고, `routing { authenticate("test") {
  leaderElectionEventStream() } }`를 등록하는 helper다. helper는
  `FakePublisherElector(publisher)`를 `leaderElection`으로 넣고
  `eventStreamRouteEnabled=true`, `eventStreamSseEnabled=true`,
  `eventStreamReplayCapacity=8`, `eventStreamMaxConnections=2`를 설정한다.
  `eventStreamHubForTest()`는 application attribute의 hub를 읽는 internal test
  extension이다. response channel은 `CompletableDeferred` emit barrier와
  `readSseFrame`의 blank-delimited parser로 읽고 고정 sleep으로 stream 타이밍을
  맞추지 않는다.

- [ ] **Step 2: SSE aliases/dependencies를 추가한다.**

  catalog에 `ktor-server-sse = { module = "io.ktor:ktor-server-sse" }`를
  추가하고 `leader-ktor/build.gradle.kts`에는 compileOnly와
  `testImplementation(libs.ktor.server.sse)`를 선언한다. Ktor BOM이 3.5.2를
  공급하며 개별 version은 쓰지 않는다. client frame test에 직접 필요한
  `ktor-client-core`/test helper가 이미 있지 않으면 기존 catalog alias를 재사용하고
  새 alias 추가는 test source를 먼저 compile하여 필요한 경우에만 한다.

- [ ] **Step 3: compileOnly SSE adapter를 구현한다.**

  adapter object는 caller가 제공한 authenticated `Route` parent에
  `application.pluginOrNull(SSE)`가 존재하는지 먼저 확인하고, 없으면
  `LeaderElectionConfigurationException(CONFIGURATION)`을 즉시 발생시킨다.
  확인 후에만 `parent.route(path) { sse { ... } }`를 등록한다. query `lockName`과
  `Last-Event-ID`는 단일 non-negative decimal parser로 검증하고, malformed
  cursor/duplicate header는 `INVALID_CURSOR` 400으로 응답한다. `ServerSSESession`
  scope 안에서 bounded hub `subscribe` item을
  `send(ServerSentEvent(id=sequence.toString(), event=type, data=json))`로
  보낸다. heartbeat job은 config heartbeat 간격으로 control frame을 보내며,
  `finally`에서 collector/job/channel을 닫고 connection permit을 반환한다. peer
  disconnect는 `CancellationException`으로 재전파하고 backend cause를 응답하지
  않는다. `eventStreamMaxConnections` semaphore가 포화되면
  `BACKEND_UNAVAILABLE` 503 stable JSON을 반환한다.

- [ ] **Step 4: SSE test와 dependency resolution을 GREEN으로 실행한다.**

  Run: `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderEventStreamRouteTest' --no-daemon --no-build-cache`

  Expected: SSE event id/type/data, filter, replay cursor, `replay_gap`, heartbeat,
  invalid lock/missing query, malformed/duplicate cursor, unauthenticated 401,
  missing-plugin configuration error, connection admission, disconnect cancellation이
  PASS한다. `dependencyInsight`로 `ktor-server-sse:3.5.2`를 확인한다. artifact가
  없는 filtered classloader와 artifact는 있지만 `install(SSE)`가 없는 host를
  각각 실행해 두 경로 모두 `CONFIGURATION`으로 정규화되는지 기록한다.

- [ ] **Step 5: SSE adapter commit을 만든다.**

  ```bash
  git add gradle/libs.versions.toml leader-ktor/build.gradle.kts leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/sse/LeaderEventSseAdapter.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamRouteTest.kt
  git commit -m "Epic #701 SSE event stream route를 bounded hub에 연결한다"
  ```

### Task 17: WebSocket adapter 또는 명시적 후속 범위

**Files:**
- Create if supported: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/websocket/LeaderEventWebSocketAdapter.kt`
- Modify if supported: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamRouteTest.kt`, `gradle/libs.versions.toml`, `leader-ktor/build.gradle.kts`
- Read-only/possible follow-up: GitHub issue #539

- [ ] **Step 1: Ktor 3.5.2 WebSocket API compatibility를 먼저 compile probe한다.**

  `ktor-server-websockets`와 test-only `ktor-client-websockets` alias를 BOM에
  연결한 뒤 최소 `install(WebSockets) {}`와 `webSocket(path) {}` probe를
  compile한다. API가 route-scoped plugin 및 session cancellation과 충돌하지
  않으면 implementation을 계속한다. compile probe가 실패하거나 WebSocket
  dependency를 always-loaded class에 노출해야만 한다면, 현재 #539 body의
  조건대로 이 task를 SSE-only로 닫고 `gh issue create`로 후속 WebSocket issue를
  등록한다. 후속 issue 생성은 사용자 요청 범위 안의 명시적 child split이며, 기존
  #539 범위를 조용히 확장하지 않는다.

- [ ] **Step 2: WebSocket route contract test를 RED로 작성한다.**

  지원 경로에서는 `install(WebSockets)`가 있는 test host에서
  `authenticate("test") { leaderElectionEventStream() }` route를 사용해
  `afterSequence` query, lock filter, text JSON frame, `replay_gap`, heartbeat,
  peer disconnect와 bounded channel을 SSE와 같은 fake publisher로 검증한다.
  미인증 요청은 401이고 subscriber가 생성되지 않아야 한다. client `webSocket`
  session이 닫힌 뒤 hub subscriber와 heartbeat job이 취소되고 publisher collector는
  살아 있는지 assertion한다.

- [ ] **Step 3: compileOnly WebSocket adapter를 구현하거나 후속 issue evidence를 기록한다.**

  지원 시 adapter object는 caller가 제공한 authenticated `Route` parent에서
  `application.pluginOrNull(WebSockets)`를 먼저 확인하고, 없으면
  `LeaderElectionConfigurationException(CONFIGURATION)`을 낸다. 확인 후에만
  `webSocket("$path/ws")`를 등록한다. text frame은
  `LeaderEventStreamPayload`의 safe JSON만 보내고, `afterSequence`를 단일
  non-negative decimal parser로 검증해 hub에 전달한다. session scope cancellation과
  `finally` cleanup은 SSE와 동일하며 Ktor ping 설정과 별개인 heartbeat control
  frame을 사용한다. 지원하지 않으면 production WebSocket 파일을 만들지 않고
  compile probe log, #539 follow-up issue URL, SSE-only acceptance를 child evidence에
  기록한다.

- [ ] **Step 4: WebSocket tests/dependency 또는 follow-up 상태를 검증한다.**

  지원 시 Run:
  `./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderEventStreamRouteTest' --no-daemon --no-build-cache`
  및 `dependencyInsight --dependency ktor-server-websockets`를 실행하고
  `3.5.2` resolution과 모든 route assertion을 확인한다. artifact present/plugin
  absent, artifact absent filtered classloader, plugin present 세 경로를 모두
  `CONFIGURATION`/정상 route 결과로 구분한다. 미지원 시 Run:
  `FOLLOW_UP_ISSUE=$(gh issue create --title "Epic #701 KTOR-04 WebSocket 후속 범위" --body-file /tmp/epic-701-ktor-websocket-follow-up.md --json number --jq .number)` 후
  `gh issue view "$FOLLOW_UP_ISSUE" --json state,title,body`와
  `git diff --check`를 실행하고 #539가 SSE-only로 명확히 남았는지 확인한다.

- [ ] **Step 5: WebSocket 결과에 맞는 Lore commit을 만든다.**

  지원한 경우:

  ```bash
  git add gradle/libs.versions.toml leader-ktor/build.gradle.kts leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/stream/websocket/LeaderEventWebSocketAdapter.kt leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/stream/LeaderEventStreamRouteTest.kt
  git commit -m "Epic #701 WebSocket event stream을 session scope에 연결한다"
  ```

  후속 issue로 분리한 경우 commit intent는 “SSE 범위를 고정하고 WebSocket을
  후속 issue로 분리한다”로 쓰고, `Rejected`에 compile/API incompatibility를,
  `Not-tested`에 WebSocket runtime을 적는다.

### Task 18: stream 문서, 전체 KTOR-04 검증, PR 준비

**Files:**
- Modify: `leader-ktor/README.md`, `leader-ktor/README.ko.md`
- Modify: `docs/manual/en/modules/bluetape4k-leader-ktor.md`, `docs/manual/ko/modules/bluetape4k-leader-ktor.md`
- Modify: `docs/manual/en/frameworks/ktor.md`, `docs/manual/ko/frameworks/ktor.md`
- Read-only: CI/Nightly workflow, issue #539, exact branch metadata

- [ ] **Step 1: event stream 운영 문서를 작성한다.**

  두 README와 두 module manual에 exact property names/defaults, SSE path와
  WebSocket `/ws` suffix, 필수 `lockName`/all-lock opt-in, `Last-Event-ID`/
  `afterSequence`, bounded best-effort replay와 `replay_gap`, heartbeat,
  slow-consumer drop, disconnect cleanup, optional dependency/runtime artifact,
  caller-owned `authenticate { leaderElectionEventStream() }` authorization
  boundary, leader metadata opt-in을 설명한다. framework manual에는
  `install(SSE)`/`install(WebSockets)` 책임과 Ktor 3.x BOM 사용을 추가한다.

- [ ] **Step 2: focused stream 및 기존 regression을 실행한다.**

  ```bash
  ./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderEventStream*' --tests '*LeaderOptionalClasspathSmokeTest' --no-daemon --no-build-cache
  ./gradlew :bluetape4k-leader-ktor:test --no-daemon --no-build-cache
  ```

  Expected: hub/payload/route/classpath smoke와 baseline 23개를 포함한 전체
  Ktor test가 PASS한다. SSE/WebSocket test가 flaky하면 virtual-time/barrier를
  수정하고 retry만으로 통과시키지 않는다.

- [ ] **Step 3: KTOR-04 공통 검증과 manual release validator를 실행한다.**

  공통 6개 명령, 전체 public `javap` 목록, `jar tf`, Korean audit,
  `export_manifest.rb --check`, release inventory/validator와 manual Ruby test를
  순서대로 실행한다. CI/Nightly workflow의 `leader-ktor` path filter, test job,
  aggregator needs가 이미 등록되어 있음을 `rg`/`git diff`로 확인하고 workflow
  diff는 만들지 않는다. hosted run을 받은 뒤에는 각 run의 `attempts`와 모든
  terminal job conclusion을 `gh run view --json jobs,conclusion,url` 및
  Actions attempts API로 기록하고, Kover XML/HTML artifact가 생성·검사되었는지
  확인한다. retry-only green, continue-on-error, 또는 Kover 누락은 전체 PASS로
  승격하지 않고 원인 수정 또는 명시적 `PENDING` gap으로 남긴다.

- [ ] **Step 4: KTOR-04 exact head와 PR-ready diff를 검토한다.**

  `KTOR_03_SHA=$(git rev-parse feat/epic-ktor-03-route-guard)`를 먼저 설정한 뒤
  `git diff --stat "$KTOR_03_SHA"..HEAD`, `git diff --check`, `git status --short`,
  `git range-diff "$KTOR_03_SHA"...HEAD`를 실행한다. public API, optional
  class-loading, payload metadata, cursor handoff, cancellation, docs EN/KO parity,
  no new module/BOM/CI drift를 체크리스트로 기록한다.

- [ ] **Step 5: KTOR-04 child commit을 Lore 형식으로 완료한다.**

  ```bash
  git add leader-ktor gradle/libs.versions.toml docs/manual/en/modules/bluetape4k-leader-ktor.md docs/manual/ko/modules/bluetape4k-leader-ktor.md docs/manual/en/frameworks/ktor.md docs/manual/ko/frameworks/ktor.md
  git commit -m "Epic #701 bounded Ktor event management surface를 완성한다"
  ```

  commit body `Tested`에 focused/전체 Ktor test, compile, jar, detekt, manual
  validator, classpath smoke 결과를 쓰고, 후속 issue로 분리한 WebSocket은
  `Not-tested`와 `Directive`에 정확히 기록한다.

## 7. stacked PR train, review, merge, sync, cleanup

### Task 19: 구현 완료 전 독립 code review와 lesson gate

**Files:**
- Read-only: KTOR-01~04 exact diffs, tests, CI, issue/PR metadata
- Create if needed: `docs/review/2026-08-26-epic-701-ktor-train-review.md`
- Create if needed: `docs/lessons/2026-08-26-epic-701-ktor-train.md`

- [ ] **Step 1: child별 code review를 실행한다.**

  `$code-review` 또는 `review-pr` read-only pass를 child별로 실행하고,
  lifecycle ownership, error privacy, route auth order, lease release,
  replay/live handoff, optional class loading, API/binary compatibility, docs
  parity를 독립적으로 확인한다. HIGH/CRITICAL finding은 PR 생성 전에 수정하고,
  LOW finding은 issue-sized인지 duplicate인지 확인한 뒤 기록한다.

- [ ] **Step 2: exact-head와 range-diff로 stacked ancestry를 검증한다.**

  계획에 기록된 immutable train base와 현재 live base를 먼저 분리해 읽는다.
  `TRAIN_BASE_SHA`는 작업 중 바꾸지 않고, `LIVE_DEVELOP_SHA`가 달라졌으면
  PR 전에 descendant rebase와 전체 재검증을 별도 증거로 남긴다. 현재
  `develop`을 조용히 기준으로 삼아 이미 정렬되었다고 보고하지 않는다.

  ```bash
  TRAIN_BASE_SHA=37bcff6b41f166769dd5d851f90fc28c1f8e92bd
  LIVE_DEVELOP_SHA=$(git rev-parse origin/develop)
  git show -s --format='%H %s' "$TRAIN_BASE_SHA" "$LIVE_DEVELOP_SHA"
  git merge-base --is-ancestor "$TRAIN_BASE_SHA" feat/epic-ktor-01-lifecycle
  git merge-base --is-ancestor feat/epic-ktor-01-lifecycle feat/epic-ktor-02-errors
  git merge-base --is-ancestor feat/epic-ktor-02-errors feat/epic-ktor-03-route-guard
  git merge-base --is-ancestor feat/epic-ktor-03-route-guard feat/epic-ktor-04-event-stream
  git range-diff "$TRAIN_BASE_SHA"...feat/epic-ktor-01-lifecycle
  git range-diff feat/epic-ktor-01-lifecycle...feat/epic-ktor-02-errors
  git range-diff feat/epic-ktor-02-errors...feat/epic-ktor-03-route-guard
  git range-diff feat/epic-ktor-03-route-guard...feat/epic-ktor-04-event-stream
  ```

  `LIVE_DEVELOP_SHA != TRAIN_BASE_SHA`이면 KTOR-01을 live `develop`에
  descendant rebase하고, 각 downstream을 새 exact parent에 차례로 rebase한
  뒤 같은 test/descriptor/manual 검증과 range-diff를 다시 실행한다. 순서가
  뒤집혔거나 child diff가 predecessor를 재작성하면 PR을 만들지 않는다.

- [ ] **Step 3: 한국어 lesson/PR evidence를 작성한다.**

  반복된 실패·설계 경계·검증 명령·남은 위험을 Korean lesson 문서에 짧게
  기록한다. `docs/review`/`docs/lessons`를 만들면 manual contract에 포함되는지
  확인하고, 단순 PR metadata는 repository docs에 복제하지 않는다.

### Task 20: PR 생성과 hosted CI/review gate

**Files:**
- Read/Write external: GitHub PR #541/#540/#542/#539 equivalents
- Read-only local: exact branch heads and clean worktrees

- [ ] **Step 1: PR 전 live issue와 branch base를 재확인한다.**

  각 issue의 state/milestone/labels/assignee/body와 exact branch head를
  `gh issue view`, `git show`, `git status`로 다시 읽는다. PR target은 #541만
  `develop`, #540은 KTOR-01 branch, #542는 KTOR-02 branch, #539는 KTOR-03
  branch다. `git rev-parse origin/develop`와 계획의 `TRAIN_BASE_SHA`, 각
  predecessor head의 `git merge-base`를 비교해 live base drift를 기록하고,
  drift가 있으면 descendant rebase 후 새 head와 전체 검증 증거가 생길 때까지
  PR creation을 보류한다. merge approval은 이 task의 PR creation approval과
  별개로 유지한다.

- [ ] **Step 2: Korean PR body와 DoD section을 준비한다.**

  각 body는 child issue를 `Closes #541`/`Closes #540`/`Closes #542`/`Closes #539`로
  연결하고 predecessor/base/head SHA, 변경 계약, tests/commands,
  docs, known gaps, rollback order를 Korean으로 작성하고 마지막을 정확히
  `## DoD Status` section으로 끝낸다. child dependency와 “먼저 ancestor를
  merge해야 downstream을 merge할 수 있음”을 명시한다. `gh pr create`는
  target repository/base/head가 이 계획과 일치할 때만 실행한다.

- [ ] **Step 3: PR을 ancestor→descendant 순서로 생성한다.**

  ```bash
  gh pr create --base develop --head feat/epic-ktor-01-lifecycle --title "Epic #701 KTOR-01 lifecycle 소유권 정리" --body-file /tmp/epic-701-ktor-01-pr.md
  gh pr create --base feat/epic-ktor-01-lifecycle --head feat/epic-ktor-02-errors --title "Epic #701 KTOR-02 stable error contract를 고정한다" --body-file /tmp/epic-701-ktor-02-pr.md
  gh pr create --base feat/epic-ktor-02-errors --head feat/epic-ktor-03-route-guard --title "Epic #701 KTOR-03 route guard를 추가한다" --body-file /tmp/epic-701-ktor-03-pr.md
  gh pr create --base feat/epic-ktor-03-route-guard --head feat/epic-ktor-04-event-stream --title "Epic #701 KTOR-04 event stream을 연결한다" --body-file /tmp/epic-701-ktor-04-pr.md
  ```

  실제 PR 번호와 URLs를 plan evidence에 기록한다. merge나 auto-merge는 이
  단계에서 실행하지 않는다. 생성 직후 각 PR에 issue와 같은 milestone
  `1.0.0`, `feature`/`integration` 및 child issue에 이미 있는 security label을
  적용하고 assignee `debop`을 지정한다.

  ```bash
  gh pr edit "$PR" --add-assignee debop --add-label feature --add-label integration --milestone "1.0.0"
  ```

- [ ] **Step 4: exact-head hosted CI와 review/thread를 확인한다.**

  각 PR 번호를 `PR` shell 변수에 넣은 뒤 `gh pr view "$PR" --json
  headRefOid,baseRefName,reviewDecision,statusCheckRollup,mergeable,body`와
  `gh pr checks "$PR" --watch`를 사용한다. 해당 run id를 `RUN_ID`에 넣어
  `gh run view "$RUN_ID" --json jobs,conclusion,url`로 terminal job conclusion과
  SHA를 확인한다. `gh api "repos/$(gh repo view --json nameWithOwner --jq
  .nameWithOwner)/actions/runs/$RUN_ID/attempts"`로 모든 retry attempt를 읽고,
  각 attempt의 terminal job과 Kover XML/HTML artifact를 기록한다.
  skipped/path-filtered/local baseline, retry-only green, continue-on-error 또는
  Kover 누락을 full exact-head PASS로 오인하지 않는다. review thread와
  requested changes를 모두 읽고 unresolved HIGH/CRITICAL이 있으면 child를
  수정하고 downstream base를 rebase한다.

### Task 21: 별도 merge approval 이후 순차 merge와 canonical sync

**Files:**
- Read/Write external: GitHub PRs and `develop`
- Read-only/controlled local: canonical checkout, child worktrees

- [ ] **Step 1: merge 전 별도 approval gate를 확인한다.**

  merge 직전에 사용자의 fresh explicit approval을 기다린다. 승인 전에는
  PR 생성/CI/review evidence만 보고하고 merge를 실행하지 않는다. approval이
  오면 각 PR의 exact head/base, checks/Nightly, reviews/threads, mergeability,
  linked issue, body `## DoD Status`, labels/milestone를 다시 읽는다.

- [ ] **Step 2: ancestor부터 re-read 후 squash merge한다.**

  `gh pr merge "$KTOR_01_PR" --squash --delete-branch=false`를 첫 실행으로 하고,
  hosted develop CI와 exact merged SHA를 확인한다. 같은 검사를 KTOR-02,
  KTOR-03, KTOR-04 순서로 반복한다. auto-merge를 켜지 않으며, merge 후
  descendant base가 새 ancestor SHA를 가리키는지 확인한다. merge conflict가
  나면 descendant-first rollback 원칙에 따라 PR base와 range-diff를 먼저
  복구한다.

- [ ] **Step 3: canonical develop을 fast-forward sync한다.**

  canonical checkout의 branch/worktree/dirty paths를 먼저 읽고, unrelated
  변경이 있으면 path-scoped stash로 보존한다. `git fetch origin develop` 후
  proven merged remote head로 `git pull --ff-only origin develop`하고,
  `git status --short`, `git rev-parse develop origin/develop`가 일치함을
  확인한다. broad reset이나 broad stash를 사용하지 않는다.

- [ ] **Step 4: descendant-first rollback drill과 cleanup을 통합 증거 뒤에 실행한다.**

  merge 직후 별도 임시 ref/작업 디렉터리에서 descendant-first rollback drill을
  수행한다. KTOR-04→KTOR-03→KTOR-02→KTOR-01 순서로 feature flag를 끄고
  startup-focused test를 실행해 public route 비노출, registry/stream cleanup,
  canonical develop 복귀가 증명되는지 기록한다. drill을 실제 merged branch에
  destructive하게 적용하지 않으며, 검증할 수 없으면 `PENDING` gap으로 남긴다.
  그 뒤 각 child PR merged, branch ancestry/range-diff 동등성, canonical develop
  sync, dirty/unrelated path 부재를 모두 확인한 후에만 child worktree/branch
  cleanup을 고려한다. rebase merge에서는 `git range-diff`로 patch equivalence를
  확인한다. ambiguous/detached/dirty/unrelated target은 보존하고 cleanup하지
  않는다. cleanup 후 `git worktree list`, `git branch --merged`, `git status`
  를 재확인한다.

## 8. 계획 자체 검토와 수용 기준 추적

### 8.1 승인된 설계와 task coverage

| 설계 수용 기준 | 구현 task | 완료 증거 |
|---|---|---|
| Application stop에서 plugin-owned resource exactly-once cleanup | Task 1–3 | registry report, plugin stop test, scheduler Job completion |
| caller-owned elector/backend 비종료 | Task 2–3 | close-count fake와 plugin test |
| register/stop race, duplicate close, bounded cancel/join, failure 집계 | Task 1 | `LeaderElectionResourceRegistryTest` |
| scheduler cycle/cancellation/contention-null 보존 | Task 3 | `ApplicationExtTest`, 기존 core contract |
| StatusPages/converter 유무 동일 code/allow-list payload | Task 5–7 | fallback/adapter parity tests |
| STATE passive guard, unsupported capability startup rejection | Task 9 | state route tests와 startup error |
| explicit LEASE capability/bounded/release, default STATE acquire 0 | Task 10 | lease fake tests |
| auth/authz/rate-limit nesting와 unauth state read 0 | Task 11 | 401/403 pipeline tests |
| SSE/WS opt-in, filter, bounded replay, heartbeat, cancellation, cursor gap | Task 13–18 | payload/hub/route/classpath tests |
| current publisher shape 재사용, metadata opt-in | Task 13–14 | #535 readback와 payload assertions |
| optional artifact isolation 및 missing-artifact error | Task 6, 15–17 | URLClassLoader smoke, flag-on configuration error |
| EN/KO README/manual과 KDoc | Task 3, 7, 11, 18 | Korean audit, manual validators |
| no new module/BOM/CI drift | Task 8, 12, 18 | `git diff`, workflow path evidence |
| descendant-first rollback와 exact-head train | Task 4, 8, 12, 19–21 | SHA/range-diff/PR evidence |

### 8.2 계획 작성자 자체 검토 체크리스트

- [x] 각 구현 task에는 exact file path, RED command, minimal implementation shape,
      GREEN command, commit 명령이 있고, handoff/review/PR task에는 그 단계의
      evidence와 외부 작업 명령이 있다.
- [x] 빈칸이나 실행 결과를 미루는 표현이 없고,
      optional WebSocket만 compile probe 결과에 따라 후속 issue로 분리하는
      구체적 조건이 있다.
- [x] 앞 task에서 정한 `LeaderElectionErrorCode`, `LeaderRouteGuardConfig`,
      `LeaderEventStreamConfig`, hub cursor API가 뒤 task에서 같은 이름으로
      사용된다.
- [x] KTOR-01→04 base/branch와 shared file sequential ownership가 표와 task
      양쪽에 일치한다.
- [x] 기존 `LeaderElectionManagementRegistry` 실제 파일 위치를 확인한 뒤
      nonexistent path를 만들지 않는다.
- [x] `checkBinaryCompatibility` 같은 존재하지 않는 Gradle task를 호출하지 않고
      jar/`javap` 및 pinned manual validator만 사용한다.
- [x] TDD exception assertion이 Bluetape `assertFailsWith`로만 고정되어 있다.
- [x] Ktor 3.5.2 BOM, optional aliases, compileOnly/testImplementation, classpath
      smoke와 caller-owned lifecycle가 모두 명시되어 있다.

### 8.3 독립 six-lens 계획 검토 결과

계획 초안은 세 개의 독립 review lane이 서로 다른 렌즈를 담당하도록 검토했다.
각 lane의 결과를 안정성, 성능, 보안, 운영, 개발자/API, 사용자/호출자 여섯
관점으로 합쳤으며, 아래 보완을 반영한 뒤 미해결 P0/P1은 없다.

| review lane | 적용한 렌즈 | 주요 finding | 계획에 반영한 repair | 상태 |
|---|---|---|---|---|
| stability review | 안정성·API | stop callback의 동기 join, register/close 경합, replay handoff race, SSE parser/plugin 확인 부족 | dedicated cleanup dispatcher와 `awaitClosed`, atomic mutex handoff, blank-delimited parser, plugin presence/barrier test | 반영 |
| performance/ops review | 성능·운영 | 연결 수 무제한, timeout 관측 부족, stale manifest/live base drift, retry-only green·Kover 누락 | `eventStreamMaxConnections`, kind별 shutdown report, live manifest derive, base drift rebase gate, attempt/terminal job/Kover evidence, rollback drill | 반영 |
| security/consumer review | 보안·호출자·개발자/API | 인증 전 자동 route, cursor/status allow-list 모호성, lockName metadata 노출, backend IAE/log redaction | caller-owned authenticated registrar, `INVALID_CURSOR`/status allow-list, metadata opt-in, validation/provider IAE 분리와 sanitized logging | 반영 |

검토 결과는 설계의 §11과 일치하며, 구현 착수 전 남은 gate는 사용자의 상세 계획
승인뿐이다. plan commit 이후에도 production source, child PR, merge는 승인 전까지
변경하지 않는다.

### 8.4 계획 완료 조건

이 계획 문서 자체는 Korean term audit, `git diff --check`, rendered Markdown
read-back, six-lens plan review에서 P0/P1이 없고 사용자의 명시적 계획 승인이
있을 때만 구현 단계로 전환한다. 그 전에는 위 checkbox를 실제 구현 완료로
표시하지 않으며 production source를 변경하지 않는다.
