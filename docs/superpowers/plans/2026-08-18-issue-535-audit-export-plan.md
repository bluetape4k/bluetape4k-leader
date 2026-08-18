# OBS-03 Audit Export Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and the matching Kotlin pattern skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** `leader-core`와 `leader-micrometer`에 election/history event를 안전하게 bounded asynchronous exporter로 전달하고, JDK HTTP/webhook transport와 관찰 metric을 제공한다.

**Architecture:** core는 token을 제외한 immutable `LeaderAuditExportEvent`, redaction policy, non-blocking bounded admission, retryable one-shot delivery와 lifecycle을 소유한다. History sink와 `LeaderElectionEventPublisher`는 core exporter bridge를 사용하고, HTTP는 JDK `HttpClient.sendAsync`를 주입된 payload encoder와 결합한다. Micrometer는 exporter를 decorator하여 low-cardinality outcome metric만 추가한다.

**Tech Stack:** Kotlin, JDK 25 `java.net.http.HttpClient`, cancellation-capable `CompletableFuture`, `ScheduledExecutorService`, kotlinx.coroutines Flow, Micrometer, JUnit 5, Bluetape assertions, MockK where existing patterns require it.

---

## 실행 경계와 공통 규칙

- 작업 worktree: `.worktrees/issue-535-audit-core`
- AUD-01 branch: `feat/epic-obs-03-audit-export-core`, base `origin/develop`
- AUD-02 branch: `feat/epic-obs-03-audit-export-micrometer`, base AUD-01 exact head
- AUD-03 branch: `feat/epic-obs-03-audit-export`, base AUD-02 exact head
- 각 task는 RED → 실패 확인 → 최소 구현 → GREEN → `git diff --check` → Lore commit 순서로 실행한다.
- 새 exception test는 `io.bluetape4k.assertions.assertFailsWith`만 사용한다. JUnit `assertThrows`, `kotlin.test.assertFailsWith`, `shouldThrow`는 사용하지 않는다.
- exporter callback은 election 결과를 변경하지 않는다. coroutine cancellation은 broad `Exception`보다 먼저 재전파한다.
- 외부 dependency, 새 module, BOM/CI/Nightly registration은 추가하지 않는다.

## Task 1: 안전한 core audit event와 redaction policy

**Files:**

- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditExportEvent.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditLifecycleOutcome.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditValueSanitizer.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderAuditExportEventTest.kt`

- [ ] **Step 1: Write the failing event/redaction tests**

  다음 테스트를 작성한다.

  ```kotlin
  @Test
  fun `history event never exposes token and bounds error metadata`() {
      val event = LeaderAuditExportEvent.History.from(
          record = recordWithTokenAndOversizedMetadata(),
          sanitizer = LeaderAuditValueSanitizer.Default,
      )

      event::class.memberProperties.none { it.name == "token" }.shouldBeTrue()
      val errorBytes = event.errorMessage?.toByteArray(Charsets.UTF_8)?.size ?: 0
      (errorBytes <= LeaderAuditExportEvent.MAX_ERROR_MESSAGE_BYTES).shouldBeTrue()
      (event.attributes.size <= LeaderAuditExportEvent.MAX_ATTRIBUTES).shouldBeTrue()
  }

  @Test
  fun `default sanitizer redacts lock node and leader identity`() {
      val sanitizer = LeaderAuditValueSanitizer.Default

      sanitizer.sanitize(LeaderAuditField.LOCK_NAME, "tenant-42-job")
          .shouldBeEqualTo("redacted")
      sanitizer.sanitize(LeaderAuditField.LEADER_ID, "node-1")
          .shouldBeEqualTo("redacted")
  }

  @Test
  fun `default policy removes sentinel from every sensitive event field and string form`() {
      val event = LeaderAuditExportEvent.History.from(
          record = recordWithSentinelInEveryField(),
          sanitizer = LeaderAuditValueSanitizer.Default,
      )

      event.toString().contains(SECRET_SENTINEL).shouldBeFalse()
      event.attributes.values.any { it.contains(SECRET_SENTINEL) }.shouldBeFalse()
      event.errorMessage?.contains(SECRET_SENTINEL).shouldBeFalse()
  }

  @Test
  fun `event and attributes are immutable snapshots without public copy mutation`() {
      val source = mutableMapOf("key" to "value")
      val event = LeaderAuditExportEvent.Lifecycle.from(
          event = electedEvent(),
          attributes = source,
          sanitizer = LeaderAuditValueSanitizer.Default,
      )
      source["key"] = "changed"
      event.attributes["key"].shouldBeEqualTo("value")
      assertFailsWith<UnsupportedOperationException> {
          @Suppress("UNCHECKED_CAST")
          (event.attributes as MutableMap<String, String>)["key"] = "mutated"
      }
      event::class.memberFunctions.none { it.name == "copy" }.shouldBeTrue()
  }

  @Test
  fun `hash truncate and raw modes enforce field allow list and max length`() {
      LeaderAuditValueSanitizer.Hash.sanitize(LeaderAuditField.LOCK_NAME, SECRET_SENTINEL)
          .shouldNotBeEqualTo(SECRET_SENTINEL)
      LeaderAuditValueSanitizer.Truncate(maxLength = 8)
          .sanitize(LeaderAuditField.ERROR_TYPE, SECRET_SENTINEL)
          .length.shouldBeEqualTo(8)
      val raw = LeaderAuditValueSanitizer.Raw(
          allowList = setOf(LeaderAuditField.KIND),
          maxLength = 16,
      )
      raw.sanitize(LeaderAuditField.KIND, "ACQUIRED").shouldBeEqualTo("ACQUIRED")
      assertFailsWith<IllegalArgumentException> {
          raw.sanitize(LeaderAuditField.LOCK_NAME, SECRET_SENTINEL)
      }
  }
  ```

- [ ] **Step 2: Run the focused test and verify RED**

  Run:

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests \
    'io.bluetape4k.leader.audit.LeaderAuditExportEventTest'
  ```

  Expected: `FAIL` because the audit package and event types do not exist.

- [ ] **Step 3: Implement the minimum event contract**

  `LeaderAuditExportEvent`는 sealed interface로 만들고 `History`와
  `Lifecycle` 일반 불변 클래스를 제공한다. token과 `LeaderLease` 객체는 field에 넣지
  않는다. `History.from(record, sanitizer)`는 기존 `sanitize(record)` 결과를
  사용하고 `Lifecycle.from(event, sanitizer)`는 `LeaderElectionEvent`의
  `lockName`, outcome, expiry만 매핑한다.

  ```kotlin
  sealed interface LeaderAuditExportEvent {
      val occurredAt: Instant
      val lockName: String
      val attributes: Map<String, String>

      class History private constructor(
          override val occurredAt: Instant,
          override val lockName: String,
          val kind: LockIdentity.AnnotationKind?,
          val status: LeaderHistoryStatus,
          val nodeId: String?,
          val slotId: String?,
          val durationMs: Long?,
          val errorType: String?,
          val errorMessage: String?,
          override val attributes: Map<String, String>,
      ) : LeaderAuditExportEvent

      class Lifecycle private constructor(
          override val occurredAt: Instant,
          override val lockName: String,
          val outcome: LeaderAuditLifecycleOutcome,
          val leaderId: String?,
          val leaseExpiry: Instant?,
          override val attributes: Map<String, String>,
      ) : LeaderAuditExportEvent
  }
  ```

  `LeaderAuditValueSanitizer`는 임의 lambda가 아니라 `Default`, `Hash`, `Truncate`,
  `Raw` 정책으로 제한한다. `Raw`는 사전에 허용된 비민감 enum field subset과 양의
  최대 길이를 생성 시 검증하고, 모든 정책은 공통 byte/key limits를 적용한다. event
  `toString()`과 encoder fixture에는 secret sentinel이 없어야 한다. 모든 map은
  defensive copy 후 `Collections.unmodifiableMap(LinkedHashMap(...))` 또는 동등한
  실제 immutable runtime map으로 노출한다. Java fixture에서 `getAttributes().put`이
  `UnsupportedOperationException`을 내고 원본 map mutation도 event에 반영되지 않는지
  고정한다. public KDoc는 Korean technical register로 작성한다.

- [ ] **Step 4: Run the focused test and verify GREEN**

  Run the same focused Gradle test. Expected: all event, token omission, limits,
  redaction-negative, and sanitizer policy cases PASS.

- [ ] **Step 5: Commit AUD-01 model slice**

  ```bash
  git add leader-core/src/main/kotlin/io/bluetape4k/leader/audit \
    leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderAuditExportEventTest.kt
  git commit -m $'OBS-03 안전한 audit export event 계약을 추가한다\n\nConstraint: token과 high-cardinality 값은 core event 경계에서 제거한다.\nRejected: 기존 LeaderLockHistoryRecord를 외부 payload로 직접 노출 | token/redaction 경계가 불명확함\nConfidence: high\nScope-risk: moderate\nDirective: exporter는 sanitized event만 수신해야 한다.\nTested: focused LeaderAuditExportEventTest\nNot-tested: dispatcher와 transport는 후속 task에서 검증한다.'
  ```

## Task 2: bounded exporter SPI, admission, retry lifecycle

**Files:**

- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditExporter.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditSubmitResult.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditExportOptions.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditExportSnapshot.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditDelivery.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/internal/BoundedLeaderAuditExporter.kt`
- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/BoundedLeaderAuditExporterTest.kt`

- [ ] **Step 1: Write failing admission and retry tests**

  테스트는 injected executor/scheduler와 `CompletableFuture` delivery fake를
  사용해 wall-clock sleep 없이 다음 계약을 고정한다. manual scheduler와 manual
  executor는 task admission, cancellation, rejection, worker 종료를 관찰할 수
  있어야 하며 fixed `delay`/sleep은 사용하지 않는다.

  ```kotlin
  @Test
  fun `queue full returns dropped without calling delivery`() { /* capacity=1 */ }

  @Test
  fun `closed exporter rejects new work and cancels pending retry`() { /* close */ }

  @Test
  fun `retryable failure stops at max attempts and never throws to submitter`() { /* 503 */ }

  @Test
  fun `non retryable failure is terminal without retry`() { /* 400 */ }

  @Test
  fun `late completion after close does not schedule another attempt`() { /* race */ }

  @Test
  fun `hung delivery times out, releases slot, retries, and terminalizes once`() { /* timeout */ }

  @Test
  fun `worker executor rejection releases every permit and recovers capacity`() { /* executor reject */ }

  @Test
  fun `retry scheduler rejection releases every permit and recovers capacity`() { /* scheduler reject */ }

  @Test
  fun `submit close crossing is linearized and caller executor is never shutdown`() { /* close race */ }

  @Test
  fun `owner closes exporter before scheduler and executor and owner-first shutdown is deterministic`() { /* ownership */ }

  @Test
  fun `invalid options and saturating backoff fail fast`() { /* boundary table */ }

  @Test
  fun `observer sees exact outcomes and observer failure is isolated`() { /* observer */ }

  @Test
  fun `blocking observer never delays submit and diagnostic drop is counted`() { /* async observer */ }

  @Test
  fun `diagnostics capacity is bounded and close stops queued observer callbacks`() { /* diagnostics lifecycle */ }

  @Test
  fun `observer handle close prevents queued callback start after return`() { /* handle */ }

  @Test
  fun `diagnostics Error closes diagnostics without restarting the worker`() { /* fatal diagnostics */ }

  @Test
  fun `exceptional and cancelled delivery futures terminalize once without throwing to submitter`() { /* exception matrix */ }

  @Test
  fun `max in flight never exceeds configured cap under concurrent submissions`() { /* cap */ }

  @Test
  fun `observer and delivery Error rethrow after permit cleanup at uncaught boundary`() { /* fatal */ }

  @Test
  fun `worker exit and concurrent submit reschedule without lost wakeup`() { /* worker barrier */ }

  @Test
  fun `fixed contention has no submit wait and bounded admission outcomes`() {
      // 32 contenders × 100 iterations, barrier before each batch, manual executor paused.
  }

  ```

  각 테스트는 `ACCEPTED`, `DROPPED_QUEUE_FULL`, `DROPPED_CLOSED`, attempt count,
  delivery completion과 close state를 `shouldBeEqualTo`/`shouldBeTrue`로 검증한다.
  timeout/self-cancel/close cancellation matrix와 late completion의 terminalization,
  queued/scheduled/in-flight cancel count, retry permit lifecycle, executor rejection과
  scheduler rejection 각각의 capacity recovery, observer exact count와 async dispatch,
  `inFlight <= maxInFlight`, worker-exit double-check/reschedule, caller-owned
  executor/scheduler의 미종료를 함께 검증한다. diagnostics capacity
  `min(queueCapacity, 1024)`, named daemon worker termination, observer registration cap
  16, observer handle close linearization, queued callback drop와 `observerDrops` exact
  count도 검증한다. `submit` source/benchmark guard는
  lock·blocking queue·capacity wait가 없고 contention 시 즉시 drop되는지 확인한다.
  contention fixture는 32 contender, 100 iteration, barrier와 paused manual executor를
  고정하고 각 submit 호출의 반환 latch, accepted/drop 합계, `snapshot()` permit bounds를
  검증한다. wall-clock 성능 수치나 allocation threshold는 주장하지 않는다.

- [ ] **Step 2: Run the focused test and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests \
    'io.bluetape4k.leader.audit.BoundedLeaderAuditExporterTest'
  ```

  Expected: `FAIL` because the exporter SPI and dispatcher are not present.

- [ ] **Step 3: Implement non-blocking bounded dispatcher**

  다음 public boundary를 구현한다. 모든 생성자·property·method의 JVM descriptor와
  Java creation path는 Task 4 표에 먼저 고정한다.

  ```kotlin
  interface LeaderAuditExporter : AutoCloseable {
      fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult
      fun observe(observer: LeaderAuditExportObserver): AutoCloseable
      fun snapshot(): LeaderAuditExportSnapshot
      override fun close()
  }

  enum class LeaderAuditSubmitResult { ACCEPTED, DROPPED_QUEUE_FULL, DROPPED_CLOSED }

  fun interface LeaderAuditDelivery {
      fun deliver(event: LeaderAuditExportEvent): CompletableFuture<LeaderAuditDeliveryResult>
  }

  enum class LeaderAuditDeliveryResult { SUCCESS, RETRYABLE_FAILURE, TERMINAL_FAILURE }

  enum class LeaderAuditExportObservation {
      ACCEPTED, DROPPED_QUEUE_FULL, DROPPED_CLOSED, RETRY, TERMINAL_FAILURE,
      CANCELLED, EXECUTOR_REJECTED, SCHEDULER_REJECTED,
  }

  fun interface LeaderAuditExportObserver {
      fun onObservation(observation: LeaderAuditExportObservation)
  }

  class LeaderAuditExportSnapshot private constructor(
      val queued: Int,
      val inFlight: Int,
      val scheduledRetries: Int,
      val admitted: Int,
      val accepted: Long,
      val droppedQueueFull: Long,
      val droppedClosed: Long,
      val retries: Long,
      val terminalFailures: Long,
      val cancellations: Long,
      val executorRejections: Long,
      val schedulerRejections: Long,
      val observerDrops: Long,
      val closed: Boolean,
  ) {
      companion object {
          @JvmSynthetic
          internal fun create(
              queued: Int,
              inFlight: Int,
              scheduledRetries: Int,
              admitted: Int,
              accepted: Long,
              droppedQueueFull: Long,
              droppedClosed: Long,
              retries: Long,
              terminalFailures: Long,
              cancellations: Long,
              executorRejections: Long,
              schedulerRejections: Long,
              observerDrops: Long,
              closed: Boolean,
          ): LeaderAuditExportSnapshot = LeaderAuditExportSnapshot(
              queued, inFlight, scheduledRetries, admitted, accepted, droppedQueueFull,
              droppedClosed, retries, terminalFailures, cancellations, executorRejections,
              schedulerRejections, observerDrops, closed,
          )
      }
  }

  class LeaderAuditExportOptions(
      val queueCapacity: Int,
      val maxInFlight: Int,
      val maxAttempts: Int,
      val attemptTimeout: java.time.Duration,
      val initialBackoff: java.time.Duration,
      val maxBackoff: java.time.Duration,
      val executor: Executor,
      val scheduler: ScheduledExecutorService,
  )
  ```

  `BoundedLeaderAuditExporter`는 CAS permit counter와 `ConcurrentLinkedQueue`로
  admission을 선형화한다. `queueCapacity`는 queued, in-flight, scheduled retry를
  합친 전체 admitted work의 hard upper bound이고 `maxInFlight <= queueCapacity`다.
  `submit`은 lock, `put`, capacity 대기를 하지 않으며 permit CAS 실패 시 즉시
  `DROPPED_QUEUE_FULL`을 반환한다. `maxAttempts`, 양의 유한 `attemptTimeout`,
  `initialBackoff`, `maxBackoff`, `Executor`, `ScheduledExecutorService`는
  `LeaderAuditExportOptions`에서 받는다. retry는 같은 permit을 유지하고 success,
  terminal failure, close/cancel에서 정확히 한 번 반환한다.

  각 attempt는 `CompletableFuture`와 timeout task 중 먼저 완료한 쪽만
  `AtomicBoolean`으로 terminalize한다. timeout은 future를 cancel하고 retryable
  failure로 분류하며, close가 먼저 이기면 observer/retry/failure를 만들지 않는다.
  `observe` callback은 submit thread에서 직접 실행하지 않는다. exporter가 소유하는
  bounded diagnostics queue와 이름이 고정된 daemon virtual-thread worker가 observation을
  전달한다. diagnostics capacity는 `min(queueCapacity, 1024)`로 파생해 별도 public
  option 없이 항상 `1..1024`로 bounded하며, atomic permit과 `LockSupport.unpark`를
  사용해 admission은 queue offer/CAS만 수행한다. diagnostics queue가 가득 차면
  `observerDrops`를 증가시키고 callback 없이 끝낸다. callback이 오래 걸려도
  admission worker와 선거 thread는 block하지 않는다. observer callback의 일반
  `Exception`만 격리하며 `Error`는 callback task를 terminalize한 뒤 전용 diagnostics
  worker의 uncaught boundary로 재전파한다. observer 또는 delivery가 `Error`를 던지면
  상태/permit을 정확히 한 번 terminalize하고 같은 원래 `Error`를 재전파한다. exporter
  `close()`는 diagnostics closed gate를 먼저 세우고 queued diagnostics를 drop한 뒤
  worker를 interrupt/unpark하고 join하여 worker 종료와 late callback 0을 확인한다.
  close는 이미 실행 중인 callback을 마칠 때까지 join할 수 있으며, callback은 interrupt를
  존중해야 한다. join이 끝난 뒤 diagnostics thread/task와 queued callback은 0이고,
  callback이 interrupt를 무시해도 close는 callback admission을 다시 열지 않으며 해당
  observer는 close 이후 새 callback을 받지 않는다. executor 또는 scheduler
  rejection은 현재 worker가 보유한 queued batch 전체를 deterministic하게
  `EXECUTOR_REJECTED` 또는 `SCHEDULER_REJECTED`로 terminalize하고 각 item의 permit을
  정확히 한 번 반환한다. 어떤 accepted item도 고착되지 않으며, batch N개를 거부한
  뒤 다음 `queueCapacity` submissions가 다시 모두 admission될 수 있어야 한다.
  worker는 `workerRunning.compareAndSet(false, true)`로 시작한다. drain loop가 empty를
  보면 먼저 `workerRunning.set(false)`를 publish한 다음 queue를 다시 확인하고,
  non-empty면 `compareAndSet(false, true)`로 drain ownership을 재획득한다. submit도
  enqueue 직후 동일한 `tryStartWorker()` CAS를 호출하므로 flag-clear와 enqueue가
  어느 순서로 교차해도 lost wakeup이 없다. 별도 atomic
  in-flight reservation을 delivery 전에 획득하고 completion/timeout/close에서 정확히
  반환해 `inFlight <= maxInFlight`를 유지한다. close는 gate를 닫고 queue·retry·in-flight
  future를 취소한 뒤 scheduling critical section과 worker admission이 quiesce된 것을
  확인하고 반환한다. network drain은 기다리지 않지만 close 반환 후 exporter가
  executor/scheduler에 새 execute/schedule을 호출하지 않는다. injected
  executor/scheduler는 exporter가 shutdown하지 않으며 ownership을 caller에게
  문서화한다.

  observer registry는 최대 16개로 고정한다. `observe()` handle close는 registry에서
  observer를 먼저 제거하고 반환하며, queued item은 callback 직전 active 상태를 다시
  확인한다. 이미 실행 중인 callback만 현재 호출을 마친다. 17번째 registration은
  no-op handle을 반환하고 `observerDrops`를 1 증가시킨다.
  diagnostics worker에서 observer `Error`가 발생하면 diagnostics gate를 CLOSED로
  전환하고 queued callback을 drop한 뒤 원래 `Error`를 uncaught boundary로 재전파하며,
  worker를 자동 재시작하지 않는다. 이후 `observe()`는 no-op handle을 반환한다.

  `queued`, `inFlight`, `scheduledRetries`, `admitted`는 각각 `AtomicInteger`로
  유지하며 snapshot은 `queue.size` 또는 work-item 순회를 사용하지 않는 O(1) 읽기로
  생성한다. outcome/rejection/observer-drop 누적값도 atomic counter로 유지한다.
  snapshot은 명시적으로 호출될 때만 할당하고 `submit`/delivery hot path에서는 생성하지
  않는다. quiescent 상태에서 `queued + inFlight + scheduledRetries == admitted`와
  `0 <= admitted <= queueCapacity`, `0 <= inFlight <= maxInFlight`를 검증한다.

  `LeaderAuditExportOptions`는 Java-visible 명시적 8-argument constructor만 제공하고
  shared executor/scheduler default를 만들지 않는다. 기본값은 `queueCapacity=1024`,
  `maxInFlight=8`, `maxAttempts=3`, `attemptTimeout=5s`, `initialBackoff=100ms`,
  `maxBackoff=5s`이며 hard upper bound는 각각 `65536`, `queueCapacity`, `16`,
  `5m`, `1m`, `1m`이다. `queueCapacity`와 `maxInFlight`는 1 이상,
  `maxInFlight <= queueCapacity`, `maxAttempts`는 1 이상, 세 Duration은 positive
  finite, `initialBackoff <= maxBackoff`여야 하며 nanosecond/millisecond conversion
  overflow는 fail-fast한다. backoff는 saturating multiplication으로 `maxBackoff`를
  넘지 않는다.

  delivery가 동기적으로 `Exception`을 던지거나 exceptional future를 반환하면
  `RETRYABLE_FAILURE` 분류 규칙에 따라 한 번만 retry/terminalize하고 `submit` 호출자에게
  던지지 않는다. `CancellationException`은 원인을 `CLOSE`, `TIMEOUT`, `CALLER`로
  구분한다. `CLOSE`는 `CANCELLED`만, `TIMEOUT`은 timeout의 `RETRY` 또는 마지막
  attempt의 `TERMINAL_FAILURE`만, `CALLER`는 `CANCELLED`만 기록하고 모두 재시도하지
  않는다. `Error`는 permit/attempt 정리 후 executor uncaught boundary로 재전파한다.

- [ ] **Step 4: Run dispatcher tests and verify GREEN**

  동일 focused test를 실행해 queue, retry, timeout, rejection, close, late
  completion, observer 경로가 모두 PASS인지 확인한다. flaky wall-clock delay가
  발견되면 manual scheduler/future seam으로 수정하고 테스트를 다시 실행한다.

- [ ] **Step 5: Commit core dispatcher slice**

  ```bash
  git add leader-core/src/main/kotlin/io/bluetape4k/leader/audit \
    leader-core/src/test/kotlin/io/bluetape4k/leader/audit/BoundedLeaderAuditExporterTest.kt
  git diff --check
  git commit -m $'OBS-03 bounded audit exporter lifecycle을 구현한다\n\nConstraint: election hot path는 queue admission만 수행하고 delivery latency를 기다리지 않는다.\nRejected: 무한 queue와 무제한 retry | 종료 불능과 장애 전파 위험\nConfidence: high\nScope-risk: broad\nDirective: close와 late completion의 상태 전이를 유지한다.\nTested: focused bounded exporter tests\nNot-tested: history bridge와 HTTP delivery는 후속 task에서 검증한다.'
  ```

## Task 3: history sink와 lifecycle publisher bridge

**Files:**

- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/ExportingLeaderHistorySink.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/ExportingSuspendLeaderHistorySink.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderElectionEventExportSubscription.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/internal/LeaderAuditPendingContextStore.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/ExportingLeaderHistorySinkTest.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderElectionEventExportSubscriptionTest.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderAuditPendingContextStoreTest.kt`

- [ ] **Step 1: Write failing bridge tests**

  blocking/suspend sink는 delegate result를 보존하고 exporter의 `DROPPED_*`를
  election result로 바꾸지 않는지 검증한다. publisher bridge는 Elected/Revoked/
  Skipped를 각각 한 번 submit하고 returned `AutoCloseable` 이후에는 submit하지
  않는지 검증한다. direct submit과 blocking/suspend/lifecycle bridge 각각에서
  accepted/drop/retry/terminal-failure observer가 보이는지 확인하고 observer가
  예외를 던져도 delegate/election result가 바뀌지 않는지 검증한다. suspend
  cancellation test는 `assertFailsWith<CancellationException>`으로 sink
  cancellation이 재전파되는지 확인한다. paused callback과 close barrier를 사용해
  close 반환 이후 submit이 0건이고, crossing event는 gate를 먼저 획득한 쪽만
  처리되는지 결정적으로 검증한다. virtual-thread/`VirtualFuture` wrapper도
  blocking/suspend와 동일한 admission, result, cancellation, close semantics를
  갖는지 검증한다.

- [ ] **Step 2: Run bridge tests and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests \
    'io.bluetape4k.leader.audit.ExportingLeaderHistorySinkTest' \
    --tests 'io.bluetape4k.leader.audit.LeaderElectionEventExportSubscriptionTest' \
    --tests 'io.bluetape4k.leader.audit.LeaderAuditPendingContextStoreTest'
  ```

  Expected: `FAIL` because bridge classes do not exist.

- [ ] **Step 3: Implement delegate-first, export-best-effort bridges**

  `ExportingLeaderHistorySink`는 delegate를 먼저 호출한다. `recordAcquired`가
  non-null key를 반환하면 `History(ACQUIRED)`를 submit하고, completed/failed는
  key와 종료 인자를 사용해 event를 만든다. acquisition record의 kind, nodeId,
  acquiredAt, lockedUntil, slotId, metadata는 token 자체를 export하지 않는 bounded
  `LeaderAuditPendingContextStore`에 key fingerprint와 함께 보관한다. context는
  terminal completed/failed에서 exactly-once 제거하고, bounded capacity/TTL eviction,
  missing-key, duplicate completion은 deterministic policy로 처리한다. context가
  없으면 sanitized `context_missing` attribute와 effective status만 가진 최소 event를
  만든다. `status` nullable record는 finishedAt 기준 `effectiveStatus()` 결과를
  사용한다. exporter submit 결과는 sink return value와 분리되며, public
  `LeaderAuditExportObserver`를 통해 caller가 accepted/drop/retry/terminal-failure를
  관찰할 수 있다. observer 오류는 격리하고 sink return value는 변경하지 않는다.

  `ExportingSuspendLeaderHistorySink`는 같은 mapping을 suspend 함수에 적용하고
  sink 호출 전후의 `CancellationException`/`InterruptedException` 경계를 보존한다.
  `LeaderElectionEventExportSubscription`은 기존 `onEvent(scope, listener)`를
  사용하되 atomic gate와 lock으로 callback admission/close를 선형화한다. close가
  gate를 닫고 이미 admitted callback이 끝난 뒤 job을 cancel하므로 scope
  cancellation과 explicit close를 모두 idempotent하게 처리하고 close 반환 이후
  새 submit을 만들지 않는다. 기존 `LeaderElectionEventPublisher`/`Listening*`
  wrapper에만 연결하며 backend elector 자체의 recorder 경로를 자동 변경하지 않는다.

- [ ] **Step 4: Run bridge tests and verify GREEN**

  동일 Gradle test를 실행하고, `runTest`에서 subscription cancellation·late event가
  추가 submit을 만들지 않는지, observer exact count와 close linearization이
  결정적인지 확인한다. context store overflow/TTL/missing-key/duplicate completion과
  blocking/suspend/virtual-thread wrapper parity도 함께 확인한다.

- [ ] **Step 5: Commit bridge slice**

  ```bash
  git add leader-core/src/main/kotlin/io/bluetape4k/leader/audit \
    leader-core/src/test/kotlin/io/bluetape4k/leader/audit
  git diff --check
  git commit -m $'OBS-03 history와 lifecycle event를 exporter에 연결한다\n\nConstraint: 기존 sink와 publisher의 결과·취소 계약을 보존한다.\nRejected: elector 내부에 HTTP/export side effect를 직접 삽입 | backend hot path와 framework contract 결합\nConfidence: high\nScope-risk: broad\nDirective: bridge는 exporter drop을 election outcome으로 변환하지 않는다.\nTested: blocking/suspend bridge and publisher subscription tests\nNot-tested: Micrometer와 HTTP transport는 후속 slice에서 검증한다.'
  ```

## Task 4: core public ABI와 contract evidence

**Files:**

- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderAuditExportBoundaryContractTest.kt`
- Create: `leader-core/src/test/java/io/bluetape4k/leader/audit/LeaderAuditExportJavaContractTest.java`
- Modify: audit public types의 KDoc files from Tasks 1–3

- [ ] **Step 1: Write failing public boundary checks**

  다음 exact public surface를 표와 reflection fixture로 먼저 고정한다.

  | Type | Exact public surface |
  |---|---|
  | `LeaderAuditExporter` | `submit(LeaderAuditExportEvent): LeaderAuditSubmitResult`, `observe(LeaderAuditExportObserver): AutoCloseable`, `snapshot(): LeaderAuditExportSnapshot`, `close(): Unit` |
  | `LeaderAuditExportOptions` | `queueCapacity:Int`, `maxInFlight:Int`, `maxAttempts:Int`, `attemptTimeout:java.time.Duration`, `initialBackoff:java.time.Duration`, `maxBackoff:java.time.Duration`, `executor:Executor`, `scheduler:ScheduledExecutorService`; Java-visible 8-argument constructor, no implicit shared executor |
  | `LeaderAuditDelivery` | `deliver(LeaderAuditExportEvent): CompletableFuture<LeaderAuditDeliveryResult>` |
  | `LeaderAuditExportObserver` | `onObservation(LeaderAuditExportObservation): Unit` with finite enum only |
  | `LeaderAuditExportSnapshot` | immutable `queued`, `inFlight`, `scheduledRetries`, `admitted`, outcome counters, `observerDrops`, `closed`; no dynamic identifiers |
  | `LeaderAuditHttpPayload` | `of(String, byte[])`, `contentType`, `body(): byte[]`; constructor/body defensively copy |
  | `LeaderAuditTrustedHttpsEndpoint` | `trusted(URI): LeaderAuditTrustedHttpsEndpoint`, `uri:URI`; caller-owned explicit trust boundary |
  | `HttpLeaderAuditExporter` | `(HttpClient, LeaderAuditTrustedHttpsEndpoint, Map<String,String>, LeaderAuditPayloadEncoder, LeaderAuditExportOptions, LeaderAuditHttpOptions)` plus `submit/observe/snapshot/close` |
  | `LeaderAuditHttpOptions` | 1-argument constructor `(maxPayloadBytes:Int)`, `defaults(): LeaderAuditHttpOptions`, `maxPayloadBytes:Int`; production HTTPS-only, no public scheme/loopback bypass |

  Kotlin default arguments must not be the only construction path: the Java fixture
  constructs options and HTTP exporter explicitly, calls `submit`, and uses
  try-with-resources `close`. Duration units, caller ownership, `ACCEPTED != delivered`,
  explicit trusted endpoint construction, and redirect/header trust rules are part of the exact
  KDoc/ABI contract. Event types
  are not `Serializable`; v1 wire/Java serialization compatibility is intentionally
  out of scope. The fixture also records nullable event properties (`nodeId`, `slotId`,
  `leaderId`, `leaseExpiry`, `errorType`, `errorMessage`) and rejects accidental platform
  types or synthetic overloads.

  Java fixture는 `LeaderAuditTrustedHttpsEndpoint.trusted(URI)`를 먼저 구성하고 core
  exporter와 HTTP exporter에서 `submit`, `observe`, `snapshot`,
  `close`를 호출하고 decorator의 `snapshot()` delegation도 compile/run으로 고정한다.
  reflection으로 위 public constructor/method set과 JVM descriptor를 확인하고, event
  public field에 `token`이나 `LeaderLease`가 없는지 고정한다. `close()` idempotence와
  `submit` return type을 JVM descriptor로 확인한다.

- [ ] **Step 2: Run contract test and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests \
    'io.bluetape4k.leader.audit.LeaderAuditExportBoundaryContractTest'
  ```

- [ ] **Step 3: Align KDoc and ABI without widening the API**

  public KDoc에는 caller-owned executor/scheduler의 종료 순서(`exporter.close()` 후
  scheduler/executor shutdown), queue drop semantics, `ACCEPTED != delivered`,
  best-effort 중복/순서/프로세스 종료 손실, redaction default, close ownership,
  JSONL/OpenTelemetry exclusion, URI/header trust boundary를 명시한다. 생성자에는
  `@JvmOverloads`를 추가하지 않고 Java 8-argument construction path를 유지한다.

- [ ] **Step 4: Run contract test and verify GREEN**

  같은 명령과 `git diff --check`를 실행하고 Kotlin/Java fixture를 함께 컴파일한다.
  ABI fixture는 새 public API의 이름, descriptor, token 부재, close/submit semantics,
  Java try-with-resources construction을 모두 PASS해야 한다.

- [ ] **Step 5: Commit core contract evidence**

  ```bash
  git add leader-core/src/main/kotlin/io/bluetape4k/leader/audit \
    leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderAuditExportBoundaryContractTest.kt \
    leader-core/src/test/java/io/bluetape4k/leader/audit/LeaderAuditExportJavaContractTest.java
  git commit -m $'OBS-03 core exporter public ABI와 KDoc를 고정한다\n\nConstraint: 기존 core public contract와 binary compatibility를 유지한다.\nRejected: 편의용 overload와 raw record exposure | JVM descriptor와 redaction 경계 확대\nConfidence: high\nScope-risk: moderate\nDirective: 후속 transport는 이 stable event boundary를 사용한다.\nTested: ABI boundary contract test and diff check\nNot-tested: Micrometer/HTTP integration은 후속 slice에서 검증한다.'
  ```

## Task 5: Micrometer exporter decorator와 low-cardinality metrics

**Files:**

- Modify: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerNames.kt`
- Create: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/audit/MicrometerLeaderAuditExporter.kt`
- Test: `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/audit/MicrometerLeaderAuditExporterTest.kt`
- Modify: `leader-micrometer/README.md`
- Modify: `leader-micrometer/README.ko.md`

- [ ] **Step 1: Write failing metric tests**

  `SimpleMeterRegistry`에서 accepted, queue-full, closed, retry, terminal failure를
  각각 발생시키고 metric 이름과 finite tag set을 검증한다. 스냅숏의 queue depth,
  in-flight, cancellation, executor/scheduler rejection와 observer-drop gauge/counter도
  검증한다.
  정확한 allow-list는 `source={history,lifecycle,direct}`,
  `transport={core,http}`, `outcome={accepted,queue_full,closed,retry,failure,
  cancelled,rejected}`로 고정한다. 고유 lockName, leaderId, endpoint, error message를
  대량 제출해도 meter 수가 증가하지 않고 해당 값이 tag에 없는지 assertion한다.
  gauge 구현은 exporter `snapshot()`의 O(1) atomic counter를 읽고 queue를 순회하지
  않으며, snapshot object는 submit hot path에서 생성하지 않는다는 source/contract
  assertion을 추가한다. wrapper close twice에서 observer detach와 delegate close를
  검증하고, wrapper close가 exporter close contract를 그대로 보존하는지 확인한다.

- [ ] **Step 2: Run Micrometer test and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-micrometer:test --tests \
    'io.bluetape4k.leader.micrometer.audit.MicrometerLeaderAuditExporterTest'
  ```

- [ ] **Step 3: Implement decorator and constants**

  `MicrometerLeaderAuditExporter(delegate, registry)`는 항상 delegate를 소유하는
  `LeaderAuditExporter` decorator로 구현하고 결과별 counter와 `snapshot()` gauge를
  delegate에 위임한다. decorator close는 observer handle을 먼저 idempotently 해제한
  뒤 delegate를 닫아 wrapper와 direct delegate 모두 `DROPPED_CLOSED`를 반환하도록
  한다. non-owning observation은 wrapper가 아니라 public `observe()` handle을
  별도로 사용한다. 생성 시 delegate에 bounded
  `LeaderAuditExportObserver`를 등록해 retry, terminal failure, cancellation,
  rejection을 관찰하고 decorator close에서 observer handle을 idempotently 해제한다.
  delegate의 `snapshot()` descriptor와 값은 그대로 전달하며 metric registry 오류가
  admission/election에 전파되지 않는다.
  metric names는 `leader.audit.export.accepted`, `leader.audit.export.dropped`,
  `leader.audit.export.retries`, `leader.audit.export.failures`,
  `leader.audit.export.queue.depth`, `leader.audit.export.in.flight`,
  `leader.audit.export.cancelled`, `leader.audit.export.rejections`,
  `leader.audit.export.observer.dropped`로 고정한다. `observerDrops`는 snapshot과
  observer-dropped counter에서 같은 누적값을 보이며 exporter close 후 0으로 reset하지
  않는다.
  `outcome`, `transport`, `source` tag 값은 위 enum allow-list만 사용하고 event field는
  tag로 복사하지 않는다. 기존 `HISTORY_SINK_FAILURES` counter는 건드리지 않는다.

- [ ] **Step 4: Run Micrometer tests and verify GREEN**

  focused test 후 기존 history/micrometer suite를 실행한다.

  ```bash
  ./gradlew :bluetape4k-leader-micrometer:test \
    --tests 'io.bluetape4k.leader.micrometer.audit.MicrometerLeaderAuditExporterTest' \
    --tests 'io.bluetape4k.leader.micrometer.history.*'
  ```

- [ ] **Step 5: Update both README locales and commit AUD-02**

  두 README에 exporter opt-in, drop/failure metric, default redaction, no JSONL/
  OpenTelemetry statement를 같은 구조로 추가한다. Korean prose는 기술 register로
  작성하고 API·metric·commands는 그대로 보존한다.

  ```bash
  git add leader-micrometer/src/main leader-micrometer/src/test \
    leader-micrometer/README.md leader-micrometer/README.ko.md
  git diff --check
  git commit -m $'OBS-03 Micrometer audit exporter metric을 추가한다\n\nConstraint: metric cardinality는 finite outcome/transport tag로 제한한다.\nRejected: lock별 metric tag | dynamic lock name으로 cardinality가 증가함\nConfidence: high\nScope-risk: moderate\nDirective: 기존 history sink metric semantics를 변경하지 않는다.\nTested: focused and existing Micrometer history tests, README diff check\nNot-tested: HTTP delivery는 AUD-03에서 검증한다.'
  ```

## Task 6: JDK HTTP/webhook delivery와 bounded retry

**Files:**

- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/LeaderAuditPayloadEncoder.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/LeaderAuditHttpOptions.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/LeaderAuditTrustedHttpsEndpoint.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/HttpLeaderAuditDelivery.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/HttpLeaderAuditExporter.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/http/HttpLeaderAuditDeliveryTest.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/http/HttpLeaderAuditExporterTest.kt`

- [ ] **Step 1: Write failing HTTP contract tests**

  classifier/retry/close는 stub `HttpClient`, manual future, manual scheduler로
  deterministic하게 검증하고, JDK `HttpServer` loopback은 request integration smoke로
  분리한다. fixed sleep 없이 모든 future/server/executor를 drain하고 반복 실행한다.

  - `202`/`204`를 success로 분류한다.
  - `429`, `503`, I/O disconnect는 최대 attempt까지 retry한다.
  - `400`, `401`, `403`, `404`는 retry하지 않는다.
  - encoder가 만든 body는 `maxPayloadBytes`를 넘지 않고 immutable 스냅숏으로
    전달되며, hard 1 MiB 초과는 copy 전에 거부하고 allow-listed headers만 request에
    전달한다.
  - oversized/chunked response는 `BodyHandlers.discarding()`으로 0 byte만 보존한다.
    이 계약은 메모리 retention bound이며 네트워크 ingress truncation을 약속하지
    않는다는 문서·negative test를 포함한다.
  - production은 `LeaderAuditTrustedHttpsEndpoint.trusted(uri)`로 명시적으로 감싼
    HTTPS-only target만 받고, unsafe URI/user-info/query/fragment/control character와
    redirect/CRLF/unknown/forbidden header를 거부한다. private/link-local/ULA/CGNAT와
    DNS rebinding 차단은 caller-owned trust boundary의 비목표임을 문서화하고, loopback
    HTTP는 public option이 아닌 `internal` test-only factory에서만 허용한다.
  - raw `URI`를 직접 받는 public constructor가 없고, unsafe URI는 trusted wrapper 생성에서
    거부되며, private/ULA/CGNAT endpoint를 사용하려면 caller가 explicit trusted boundary를
    선택해야 한다는 ABI/negative fixture를 고정한다.
  - encoder throw, exceptional/cancelled future, response body와 endpoint credential이
    log에 남지 않는 negative matrix를 검증한다.
  - exporter close가 in-flight future와 scheduled retry를 취소하고 late completion이
    새 retry를 만들지 않는다.

- [ ] **Step 2: Run HTTP tests and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests \
    'io.bluetape4k.leader.audit.http.HttpLeaderAuditDeliveryTest' \
    --tests 'io.bluetape4k.leader.audit.http.HttpLeaderAuditExporterTest'
  ```

- [ ] **Step 3: Implement encoder, delivery, and exporter composition**

  ```kotlin
  fun interface LeaderAuditPayloadEncoder {
      fun encode(event: LeaderAuditExportEvent): LeaderAuditHttpPayload
  }

  class LeaderAuditHttpPayload private constructor(
      val contentType: String,
      private val bytes: ByteArray,
  ) {
      fun body(): ByteArray = bytes.copyOf()

      companion object {
          private const val HARD_MAX_PAYLOAD_BYTES = 1024 * 1024

          @JvmStatic
          fun of(contentType: String, body: ByteArray): LeaderAuditHttpPayload {
              require(contentType.isNotBlank() && contentType.none { it.code < 0x20 || it.code == 0x7f })
              require(body.size <= HARD_MAX_PAYLOAD_BYTES)
              return LeaderAuditHttpPayload(contentType, body.copyOf())
          }
      }
  }

  class LeaderAuditHttpOptions(
      val maxPayloadBytes: Int,
  ) {
      init {
          require(maxPayloadBytes in 1..(1024 * 1024))
      }
      companion object {
          @JvmStatic
          fun defaults(): LeaderAuditHttpOptions =
              LeaderAuditHttpOptions(64 * 1024)
      }
  }

  class LeaderAuditTrustedHttpsEndpoint private constructor(
      val uri: URI,
  ) {
      companion object {
          @JvmStatic
          fun trusted(uri: URI): LeaderAuditTrustedHttpsEndpoint {
              require(uri.scheme.equals("https", ignoreCase = true))
              require(uri.userInfo == null && uri.query == null && uri.fragment == null)
              require(uri.host.isNotBlank())
              require(uri.toString().none { it.code < 0x20 || it.code == 0x7f })
              return LeaderAuditTrustedHttpsEndpoint(uri)
          }
      }
  }
  ```

  `HARD_MAX_PAYLOAD_BYTES`는 1 MiB 상수로 고정하고 `of`는 caller ByteArray를 복사하기
  전에 그 상한을 검사한다. adapter는 다시 configured `maxPayloadBytes`를 검사해
  lower bound도 request 생성 전에 거부한다. `LeaderAuditHttpOptions`는
  `maxPayloadBytes` 기본 64 KiB, hard upper bound 1 MiB를
  검증한다. production `HttpLeaderAuditExporter`는 위 `LeaderAuditTrustedHttpsEndpoint`
  로 명시적으로 감싼 HTTPS target만 받는다. 이 타입은 caller가 allow-list와 DNS/SSRF
  trust를 확인했다는 ownership boundary이며 library는 hostname을 IP에 pin하거나
  DNS rebinding을 방지한다고 주장하지 않는다. 따라서 private/link-local/ULA/CGNAT와
  DNS rebinding은 v1 비목표이고, 운영 문서는 static trusted endpoint 또는 별도 egress
  proxy를 사용하도록 안내한다. loopback HTTP는 `internal` test-only delivery factory에서만
  허용하고 public Java/Kotlin ABI에 포함하지 않는다.
  `HttpLeaderAuditDelivery`는
  `HttpRequest.newBuilder(uri).timeout(options.attemptTimeout).POST(...)`와
  `HttpClient.sendAsync(..., BodyHandlers.discarding())`를 사용한다. injected client의
  `followRedirects()`가 `Redirect.NEVER`가 아니면 생성에 실패한다. 2xx는 `SUCCESS`,
  408/429/5xx와 I/O/timeout exception은 `RETRYABLE_FAILURE`, 나머지 4xx는
  `TERMINAL_FAILURE`로 매핑한다. 요청 header는 immutable constructor allow-list로
  복사하고 CR/LF 및 `Host`, `Content-Length`, `Connection`, `Transfer-Encoding`을
  거부하며 secret 값을 logger/metric에 전달하지 않는다. 허용 header 이름은
  `Content-Type`, `Authorization` 두 개로 고정하고 그 밖의 `Cookie`,
  `Proxy-Authorization`, `X-Api-Key`, `Forwarded`와 모든 unknown header를 거부한다.
  encoder 예외는 delivery
  future의 terminal failure로 격리한다.
  `HttpLeaderAuditExporter`는 Task 2 dispatcher에 delivery를 주입하고, trusted endpoint·encoder·
  options·executor ownership 및 `exporter.close()` 후 외부 scheduler/executor를
  종료하는 순서를 KDoc로 명시한다. `snapshot()`은 delegate snapshot을 그대로
  반환한다. JSON serialization을 추가하지 않으며
  JSONL/OpenTelemetry implementation도 만들지 않는다.

  최소 runnable caller 예제는 dependency-free text encoder, `Content-Type`와
  allow-listed `Authorization` header, receiver의 duplicate/idempotency 및
  `ACCEPTED != delivered` 주의를 함께 보여준다.

- [ ] **Step 4: Run HTTP tests and verify GREEN**

  focused HTTP suite와 deterministic fake suite를 실행하고 Testcontainers 없이
  loopback server lifecycle이 매 테스트 종료 시 닫히는지 확인한다. 실패 시 response
  classification, timeout, URI/header trust, payload immutability 또는 cancellation
  race를 먼저 수정하고 재실행한다.

- [ ] **Step 5: Commit AUD-03 transport slice**

  ```bash
  git add leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http \
    leader-core/src/test/kotlin/io/bluetape4k/leader/audit/http
  git diff --check
  git commit -m $'OBS-03 JDK HTTP webhook delivery를 추가한다\n\nConstraint: 새 HTTP/serialization dependency 없이 sendAsync와 bounded retry만 사용한다.\nRejected: JSONL/OpenTelemetry를 같은 PR에 포함 | transport scope와 후속 issue 경계가 흐려짐\nConfidence: high\nScope-risk: broad\nDirective: 2xx/429/5xx/4xx와 close cancellation 분류를 유지한다.\nTested: loopback HTTP delivery and retry tests\nNot-tested: full repository validation은 final verification에서 수행한다.'
  ```

## Task 7: core README와 manual parity

**Files:**

- Modify: `leader-core/README.md`
- Modify: `leader-core/README.ko.md`
- Modify: `leader-micrometer/README.md`
- Modify: `leader-micrometer/README.ko.md`
- Modify: `README.md`
- Modify: `README.ko.md`
- Create: `docs/manual/drafts/2026-08-18-audit-export.en.md`
- Create: `docs/manual/drafts/2026-08-18-audit-export.ko.md`

- [ ] **Step 1: Add matching Korean/English capability guidance**

  core README에는 `LeaderAuditExportEvent`, `LeaderAuditExporter`, bridge lifecycle,
  default redaction, queue drop와 close semantics를 짧은 예제로 설명한다. exporter의
  `ACCEPTED != delivered`, duplicate/reordering/process-shutdown loss와 receiver
  idempotency, `exporter.close()` 후 caller-owned scheduler/executor 종료 순서를
  함께 고정한다. Micrometer README에는 metric catalog, queue/in-flight/rejection
  diagnostics와 no raw/high-cardinality tag 규칙을 추가한다. top-level README
  capability matrix와 event stream 설명에는 HTTP/webhook adapter와 JSONL/OpenTelemetry
  후속 범위를 반영한다.

  운영 lifecycle 표는 `construct resources → create exporter → submit/observe →
  exporter.close() → scheduler.shutdown()/executor.shutdown()` 순서를 제시하고,
  owner가 먼저 외부 executor를 종료한 경우 rejection이 bounded terminal/drop으로
  끝나며 caller 예외가 되지 않는다는 진단 절차를 포함한다. `ACCEPTED`는 delivered가
  아니며 receiver idempotency, duplicate/reordering, crash/close loss를 명시한다.

  release-pinned `docs/manual/manifest.yaml`는 현재 `0.5.0`/immutable
  `releaseCommit`을 가리키므로 이번 train에서 해당 release manual을 수정하지 않는다.
  대신 draft EN/KO manual에 core 조립 순서
  (`sink → ExportingSink → SafeRecorder → backend`), `Listening*` lifecycle wrapper,
  `LeaderAuditTrustedHttpsEndpoint` construction, HTTP encoder/headers/receiver 예제,
  caller-owned DNS/SSRF trust boundary와 migration/limitations를 기록하고
  “0.6.0 release manifest 전환 전에는 released claim이 아님”을 명시한다. 0.6
  release-authorized 후속 작업에서 draft를 pinned manual로 승격하고 manifest/
  release inventory를 함께 갱신한다. HTTP 예제의 `Authorization` 값은
  `${WEBHOOK_TOKEN}` 또는 `<REDACTED>` placeholder만 사용하고 실제 secret-like
  literal은 금지한다.

- [ ] **Step 2: Validate docs and locale parity**

  ```bash
  git diff --check
  ./gradlew exportManualModuleInventory
  ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json
  ruby scripts/manual/validate_release_manuals.rb "$(ruby -e 'require "yaml"; m=YAML.load_file("docs/manual/manifest.yaml"); puts m["releaseRef"]' )" "$(ruby -e 'require "yaml"; m=YAML.load_file("docs/manual/manifest.yaml"); puts m["releaseCommit"]' )"
  ruby scripts/manual/export_manifest.rb --check
  ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
  ```

  변경된 두 locale의 제목·API·metric·scope가 대응하고, draft는 0.5.0 release claim을
  과장하지 않는지 read-back한다. pinned manual의 release source validation은
  immutable commit에 대해 PASS해야 하며 draft token이 pinned 문서에 섞이지 않아야
  한다. `rg -nP '(Authorization:\s*(Basic|Bearer)\s+(?!\$\{WEBHOOK_TOKEN\}|<REDACTED>)[A-Za-z0-9._~+/=-]{8,}|(glpat-|github_pat_|xox[baprs]-|npm_|AIza|sk-|ghp_|AKIA[0-9A-Z]{8,}))' docs/manual/drafts README.md README.ko.md leader-core/README* leader-micrometer/README*`가 0건이고, placeholder가 `${WEBHOOK_TOKEN}` 또는 `<REDACTED>`로만 존재하는지 확인한다. gitleaks가 설치되어 있으면 동일 대상에 `gitleaks dir --no-banner --redact`도 실행한다.
  diagram 변경은 없으므로
  `bluetape-diagram`은 N/A이며 evidence에 이유를 기록한다.

- [ ] **Step 3: Commit documentation slice**

  ```bash
  git add README.md README.ko.md leader-core/README.md leader-core/README.ko.md \
    leader-micrometer/README.md leader-micrometer/README.ko.md \
    docs/manual/drafts/2026-08-18-audit-export.en.md \
    docs/manual/drafts/2026-08-18-audit-export.ko.md
  git commit -m $'OBS-03 audit exporter 사용 계약을 README와 manual에 반영한다\n\nConstraint: README는 concise entry point이고 manual은 release-facing source of truth다.\nRejected: 한 locale만 수정 | public API와 redaction guidance가 불일치함\nConfidence: high\nScope-risk: moderate\nDirective: JSONL/OpenTelemetry 후속 범위를 문서에서 명확히 유지한다.\nTested: locale parity, manual validator, diff check\nNot-tested: CI workflow는 변경하지 않는다.'
  ```

## Task 8: 통합 검증과 performance/stability scan

**Files:**

- Inspect all AUD-01~03 changed files and tests
- Create: `docs/review/2026-08-18-issue-535-audit-export-review.md`

  Review artifact must contain Korean summary, exact plan/spec HEAD, six lens identity
  and verdict table, each finding's file/line and repair, focused validation outputs,
  release-pinned manual evidence, rollback SHA table, and final `P0/P1/P2/P3` totals.

- [ ] **Step 1: Run targeted module validation**

  ```bash
  ./gradlew :bluetape4k-leader-core:test \
    :bluetape4k-leader-micrometer:test
  ./gradlew detekt
  git diff --check
  ```

  Expected: all affected tests, detekt, and diff check PASS; no new dependency or
  module registration appears in `git diff`.

- [ ] **Step 2: Run proportional integration checks**

  ```bash
  ./gradlew :bluetape4k-leader-core:build \
    :bluetape4k-leader-micrometer:build
  ./gradlew projects
  ```

  Verify compile/test fixtures, public ABI, and Kover inputs remain present. No
  Testcontainers backend matrix is triggered because this change does not touch
  backend modules or external databases; local loopback HTTP lifecycle tests are
  the scoped transport proof.

- [ ] **Step 3: Complete performance/stability scan**

  Inspect the exact diff for blocking calls in `submit`/Flow callbacks, unbounded
  queue/retry, scheduler ownership leaks, virtual-thread monitor pinning, coroutine
  cancellation swallowing, duplicate late completion, response-body/secret logging,
  URI/header trust, payload aliasing, executor/scheduler rejection, and per-lock
  Micrometer tags. Record every six independent lens command, exact head, files,
  P0/P1/P2/P3 result, repair or PASS evidence, CI/manual evidence, and final DoD in
  the required tracked review artifact. A benchmark framework is not added; the
  performance claim is limited to source-verified CAS/non-blocking admission plus the
  fixed 32×100 contention fixture's submit-return latch and permit-bound evidence. No
  allocation or wall-clock throughput threshold is claimed.

- [ ] **Step 4: Run final acceptance matrix**

  | Acceptance | Evidence |
  |---|---|
  | token/raw sensitive value absent | `LeaderAuditExportEventTest` + boundary fixture |
  | blocking/suspend/event bridge parity | bridge tests + cancellation test |
  | queue/drop/failure isolation | bounded exporter tests + fake delivery |
  | HTTP status/retry/backoff/close | loopback HTTP tests |
  | HTTP timeout/trusted-endpoint/header/payload/response bounds | deterministic fake HTTP tests + loopback smoke; DNS/SSRF non-goal documented |
  | Micrometer finite cardinality/diagnostics | `MicrometerLeaderAuditExporterTest` |
  | no dependency/module registration | `git diff`, `./gradlew projects` |
  | docs locale parity/release pin | README/draft manual validators and release read-back |
  | public Java/Kotlin ABI | reflection + Java compile fixture |
  | backend bridge context/virtual-thread parity | bridge/context/virtual-thread tests |

  Every row must have fresh output before Step 8 is checked.

## Task 9: stacked PR delivery preparation

- [ ] **Step 1: Verify branch ancestry and clean slices**

  ```bash
  git log --graph --oneline --decorate --all --max-count=30
  git status --short --branch
  git diff origin/develop...HEAD --stat
  ```

  AUD-01 contains only core event/dispatcher/bridge/contract commits; AUD-02 adds
  only Micrometer and its docs; AUD-03 adds only HTTP/webhook and final docs. Any
  cross-slice file drift is repaired before PR creation.

- [ ] **Step 2: Build PR bodies from fresh evidence**

  Each PR uses Korean public prose, assignee `debop`, milestone `0.6.0`, Issue #535
  link and mirrored labels. The final section is exactly `## DoD Status` and includes
  reconciled `Required checks: X/Y; N/A: N; Blocked: N`, evidence table, final status,
  and unchecked items. Base/head SHA and predecessor PR are recorded explicitly.

- [ ] **Step 3: Run Type-A pre-PR review**

  Execute six independent perspectives plus main-session integration against each
  exact stacked diff. P0/P1 blocks PR creation; P2/P3 is fixed or filed with rationale.
  Apply the Korean writer gate to the integrated review artifact.

- [ ] **Step 4: Stop at merge-ready**

  Re-read exact PR head, CI, review threads, mergeability, linked Issue #535 and
  final `## DoD Status`. Report merge-ready only after required checks pass; do not
  merge until fresh approval for that exact head.

## Traceability matrix

| Spec requirement | Plan tasks |
|---|---|
| safe event/token omission/redaction | 1, 4, 8 |
| immutable event/context/status mapping | 1, 3, 4, 8 |
| bounded CAS admission/close/retry/timeout/rejection | 2, 8 |
| history/lifecycle/virtual-thread bridges | 3, 8 |
| Micrometer accepted/drop/retry/failure/diagnostics | 5, 8 |
| HTTP status/timeout/trust/byte bounds and webhook delivery | 6, 8 |
| README/draft manual locale parity and release pin | 5, 7, 8 |
| no new dependency/module and ABI compatibility | 4, 8, 9 |
| JSONL/OpenTelemetry excluded | 6, 7, 9 |
| stacked PR exact base/head and fresh CI/review | 9 |

## Rollback and rerun points

- Before any rollback, require a clean target worktree, record exact branch/PR head,
  and verify the target commit is not merged. Never use `reset --hard` or delete a
  branch as a first step. Use `git revert <known-good-parent>..<bad-head>` on the
  isolated slice, then rerun dependent acceptance and `git diff --check`.
- AUD-01 known-good SHA is the last green core event/dispatcher/bridge/ABI commit
  recorded in the PR body; retain that SHA in the review artifact before AUD-02 starts.
  AUD-01 failure reverts only its latest Lore commit and reruns core tests/ABI/context
  fixtures; existing history/event APIs remain unchanged because all new types are opt-in.
- AUD-02 known-good SHA is the green AUD-01 head plus the Micrometer decorator/docs
  commit. Its failure retains the recorded AUD-01 SHA, reverts only AUD-02 commits,
  and reruns core acceptance plus Micrometer baseline tests.
- AUD-03 known-good SHA is the green AUD-02 head plus the HTTP transport commit. Its
  failure retains the recorded AUD-02 SHA, reverts only AUD-03 commits, and reruns
  core/Micrometer acceptance; no HTTP request is created by existing elector code
  without explicit opt-in.
- Task 7 validator or locale mismatch: repair docs only and rerun manual/read-back
  checks; do not rerun code tests unless a code claim changed.
- Any P1 from performance/stability review: stop PR delivery, repair the affected
  task, rerun its focused test and all dependent validation rows.

## Writer gate evidence

- `SPW-01` 범위: Korean spec/plan이며 API·commands·URLs·numeric limits는 원문 token을
  보존한다.
- `SPW-02` 사실 보존: six-lens findings, release pin `0.5.0`/`721a9a...`, JSONL/
  OpenTelemetry follow-up 경계를 변경하지 않았다.
- `SPW-03` 자연스러움: `korean-naturalness-checklist.md` 기준으로 문장 주어·술어,
  기술 register, `스냅숏`/`스케줄러` 용어를 점검한다.
- `SPW-04` 범위 보존: draft manual은 release-pinned manual과 분리하고, 이번 train에
  새 manual claim을 승격하지 않는다.
- `SPW-05` read-back: `git diff --check`, placeholder scan, 문서 token/locale
  parity와 exact spec/plan read-back 결과를 review artifact에 기록한다.
