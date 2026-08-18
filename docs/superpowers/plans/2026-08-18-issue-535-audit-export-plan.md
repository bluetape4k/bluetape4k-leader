# OBS-03 Audit Export Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and the matching Kotlin pattern skill to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** `leader-core`와 `leader-micrometer`에 election/history event를 안전하게 bounded asynchronous exporter로 전달하고, JDK HTTP/webhook transport와 관찰 metric을 제공한다.

**Architecture:** core는 token을 제외한 immutable `LeaderAuditExportEvent`, redaction policy, non-blocking bounded admission, retryable one-shot delivery와 lifecycle을 소유한다. History sink와 `LeaderElectionEventPublisher`는 core exporter bridge를 사용하고, HTTP는 JDK `HttpClient.sendAsync`를 주입된 payload encoder와 결합한다. Micrometer는 exporter를 decorator하여 low-cardinality outcome metric만 추가한다.

**Tech Stack:** Kotlin, JDK 25 `java.net.http.HttpClient`, `CompletableFuture`/`CompletionStage`, `ScheduledExecutorService`, kotlinx.coroutines Flow, Micrometer, JUnit 5, Bluetape assertions, MockK where existing patterns require it.

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
  fun `custom sanitizer can hash or truncate allow-listed values`() {
      val sanitizer = LeaderAuditValueSanitizer { _, value -> value.take(8) }

      sanitizer.sanitize(LeaderAuditField.LOCK_NAME, "static-job")
          .shouldBeEqualTo("static-j")
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

  `LeaderAuditExportEvent`는 `Serializable` sealed interface로 만들고 `History`와
  `Lifecycle` data class를 제공한다. token과 `LeaderLease` 객체는 field에 넣지
  않는다. `History.from(record, sanitizer)`는 기존 `sanitize(record)` 결과를
  사용하고 `Lifecycle.from(event, sanitizer)`는 `LeaderElectionEvent`의
  `lockName`, outcome, expiry만 매핑한다.

  ```kotlin
  sealed interface LeaderAuditExportEvent : Serializable {
      val occurredAt: Instant
      val lockName: String
      val attributes: Map<String, String>

      data class History private constructor(
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

      data class Lifecycle private constructor(
          override val occurredAt: Instant,
          override val lockName: String,
          val outcome: LeaderAuditLifecycleOutcome,
          val leaderId: String?,
          val leaseExpiry: Instant?,
          override val attributes: Map<String, String>,
      ) : LeaderAuditExportEvent
  }
  ```

  `LeaderAuditValueSanitizer.Default`는 민감 field를 `redacted`로 바꾸고,
  metadata/error는 기존 core byte/key limits를 재사용한다. 모든 map은 defensive
  copy하고, public KDoc는 Korean technical register로 작성한다.

- [ ] **Step 4: Run the focused test and verify GREEN**

  Run the same focused Gradle test. Expected: all event, token omission, limits,
  and custom sanitizer cases PASS.

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
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/LeaderAuditDelivery.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/internal/BoundedLeaderAuditExporter.kt`
- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/BoundedLeaderAuditExporterTest.kt`

- [ ] **Step 1: Write failing admission and retry tests**

  테스트는 injected executor/scheduler와 `CompletableFuture` delivery fake를
  사용해 wall-clock sleep 없이 다음 계약을 고정한다.

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
  ```

  각 테스트는 `ACCEPTED`, `DROPPED_QUEUE_FULL`, `DROPPED_CLOSED`, attempt count,
  delivery completion과 close state를 `shouldBeEqualTo`/`shouldBeTrue`로 검증한다.

- [ ] **Step 2: Run the focused test and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests \
    'io.bluetape4k.leader.audit.BoundedLeaderAuditExporterTest'
  ```

  Expected: `FAIL` because the exporter SPI and dispatcher are not present.

- [ ] **Step 3: Implement non-blocking bounded dispatcher**

  다음 public boundary를 구현한다.

  ```kotlin
  interface LeaderAuditExporter : AutoCloseable {
      fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult
      override fun close()
  }

  enum class LeaderAuditSubmitResult { ACCEPTED, DROPPED_QUEUE_FULL, DROPPED_CLOSED }

  fun interface LeaderAuditDelivery {
      fun deliver(event: LeaderAuditExportEvent): CompletionStage<LeaderAuditDeliveryResult>
  }

  enum class LeaderAuditDeliveryResult { SUCCESS, RETRYABLE_FAILURE, TERMINAL_FAILURE }
  ```

  `BoundedLeaderAuditExporter`는 `ArrayBlockingQueue.offer`만 사용해 submit을
  non-blocking으로 만들고, `maxInFlight`, `maxAttempts`, `initialBackoff`,
  `maxBackoff`, `Executor`, `ScheduledExecutorService`를 options에서 받는다.
  queue worker는 delivery completion을 한 번만 terminalize하며, close는 admission
  flag를 먼저 바꾸고 queued item·scheduled retry·in-flight future를 취소한다.
  injected executor/scheduler는 exporter가 shutdown하지 않으며 ownership을
  caller에게 문서화한다.

- [ ] **Step 4: Run dispatcher tests and verify GREEN**

  동일 focused test를 실행해 queue, retry, close, late completion 경로가 모두
  PASS인지 확인한다. flaky wall-clock delay가 발견되면 manual scheduler seam으로
  수정하고 테스트를 다시 실행한다.

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
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/ExportingLeaderHistorySinkTest.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderElectionEventExportSubscriptionTest.kt`

- [ ] **Step 1: Write failing bridge tests**

  blocking/suspend sink는 delegate result를 보존하고 exporter의 `DROPPED_*`를
  election result로 바꾸지 않는지 검증한다. publisher bridge는 Elected/Revoked/
  Skipped를 각각 한 번 submit하고 returned `AutoCloseable` 이후에는 submit하지
  않는지 검증한다. suspend cancellation test는 `assertFailsWith<CancellationException>`
  으로 sink cancellation이 재전파되는지 확인한다.

- [ ] **Step 2: Run bridge tests and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests \
    'io.bluetape4k.leader.audit.ExportingLeaderHistorySinkTest' \
    --tests 'io.bluetape4k.leader.audit.LeaderElectionEventExportSubscriptionTest'
  ```

  Expected: `FAIL` because bridge classes do not exist.

- [ ] **Step 3: Implement delegate-first, export-best-effort bridges**

  `ExportingLeaderHistorySink`는 delegate를 먼저 호출한다. `recordAcquired`가
  non-null key를 반환하면 `History(ACQUIRED)`를 submit하고, completed/failed는
  key와 종료 인자를 사용해 event를 만든다. exporter submit 결과는 무시하지 않고
  caller가 선택적으로 관찰할 수 있는 internal counter hook에 전달하되 sink
  return value는 변경하지 않는다.

  `ExportingSuspendLeaderHistorySink`는 같은 mapping을 suspend 함수에 적용하고
  sink 호출 전후의 `CancellationException`/`InterruptedException` 경계를 보존한다.
  `LeaderElectionEventExportSubscription`은 기존 `onEvent(scope, listener)`를
  사용하고 scope cancellation과 explicit close를 모두 idempotent하게 처리한다.

- [ ] **Step 4: Run bridge tests and verify GREEN**

  동일 Gradle test를 실행하고, `runTest`에서 subscription cancellation·late event가
  추가 submit을 만들지 않는지 확인한다.

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
- Modify: audit public types의 KDoc files from Tasks 1–3

- [ ] **Step 1: Write failing public boundary checks**

  reflection으로 `LeaderAuditExporter`, `LeaderAuditExportEvent`, options와 result
  enum의 public constructor/method set을 확인하고, event public field에 `token`이나
  `LeaderLease`가 없는지 고정한다. `close()` idempotence와 `submit` return type을
  JVM descriptor로 확인한다.

- [ ] **Step 2: Run contract test and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests \
    'io.bluetape4k.leader.audit.LeaderAuditExportBoundaryContractTest'
  ```

- [ ] **Step 3: Align KDoc and ABI without widening the API**

  public KDoc에는 caller-owned executor/scheduler, queue drop semantics, redaction
  default, close ownership, JSONL/OpenTelemetry exclusion을 명시한다. 생성자에는
  `@JvmOverloads`를 추가하지 않고, Kotlin default constructor가 필요한 경우
  reflection fixture에 exact descriptor를 기록한다.

- [ ] **Step 4: Run contract test and verify GREEN**

  같은 명령과 `git diff --check`를 실행한다. ABI fixture는 새 public API의 이름,
  descriptor, token 부재, close/submit semantics를 모두 PASS해야 한다.

- [ ] **Step 5: Commit core contract evidence**

  ```bash
  git add leader-core/src/main/kotlin/io/bluetape4k/leader/audit \
    leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderAuditExportBoundaryContractTest.kt
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
  각각 발생시키고 metric 이름과 finite tag set을 검증한다. `lockName`, `leaderId`,
  endpoint, error message가 meter tag에 없는지도 assertion한다.

- [ ] **Step 2: Run Micrometer test and verify RED**

  ```bash
  ./gradlew :bluetape4k-leader-micrometer:test --tests \
    'io.bluetape4k.leader.micrometer.audit.MicrometerLeaderAuditExporterTest'
  ```

- [ ] **Step 3: Implement decorator and constants**

  `MicrometerLeaderAuditExporter(delegate, registry)`는 `LeaderAuditExporter`를
  그대로 반환하고 결과별 counter를 increment한다. metric names는
  `leader.audit.export.accepted`, `leader.audit.export.dropped`,
  `leader.audit.export.retries`, `leader.audit.export.failures`로 고정한다.
  `outcome`, `transport` tag 값은 enum/string allow-list만 사용하고 event field는
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
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/HttpLeaderAuditDelivery.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/HttpLeaderAuditExporter.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/http/HttpLeaderAuditDeliveryTest.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/http/HttpLeaderAuditExporterTest.kt`

- [ ] **Step 1: Write failing HTTP contract tests**

  JDK `HttpServer`를 loopback에 순차적으로 띄워 다음 응답을 검증한다.

  - `202`/`204`를 success로 분류한다.
  - `429`, `503`, I/O disconnect는 최대 attempt까지 retry한다.
  - `400`, `401`, `403`, `404`는 retry하지 않는다.
  - encoder가 만든 body와 allow-listed headers만 request에 전달한다.
  - response body와 endpoint credential이 log에 남지 않는다.
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

  data class LeaderAuditHttpPayload(
      val contentType: String,
      val body: ByteArray,
  )
  ```

  `HttpLeaderAuditDelivery`는 `HttpRequest.newBuilder(uri).POST(...)`와
  `HttpClient.sendAsync`를 사용한다. 2xx는 `SUCCESS`, 408/429/5xx와 I/O exception은
  `RETRYABLE_FAILURE`, 나머지 4xx는 `TERMINAL_FAILURE`로 매핑한다. 요청 header는
  constructor allow-list를 통해 복사하고 secret 값을 logger에 전달하지 않는다.
  `HttpLeaderAuditExporter`는 Task 2 dispatcher에 delivery를 주입하고, URI·encoder·
  options·executor ownership을 KDoc로 명시한다. JSON serialization을 추가하지
  않으며 JSONL/OpenTelemetry implementation도 만들지 않는다.

- [ ] **Step 4: Run HTTP tests and verify GREEN**

  focused HTTP suite를 실행하고 Testcontainers 없이 loopback server lifecycle이
  매 테스트 종료 시 닫히는지 확인한다. 실패 시 response classification 또는
  cancellation race를 먼저 수정하고 재실행한다.

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
- Modify: `docs/manual/en/modules/bluetape4k-leader-micrometer.md`
- Modify: `docs/manual/ko/modules/bluetape4k-leader-micrometer.md`

- [ ] **Step 1: Add matching Korean/English capability guidance**

  core README에는 `LeaderAuditExportEvent`, `LeaderAuditExporter`, bridge lifecycle,
  default redaction, queue drop와 close semantics를 짧은 예제로 설명한다.
  Micrometer README/manual에는 metric catalog와 no raw/high-cardinality tag 규칙을
  추가한다. top-level README capability matrix와 event stream 설명에는 HTTP/webhook
  adapter와 JSONL/OpenTelemetry 후속 범위를 반영한다.

- [ ] **Step 2: Validate docs and locale parity**

  ```bash
  git diff --check
  ./gradlew exportManualModuleInventory
  ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json
  ```

  변경된 두 locale의 제목·API·metric·scope가 대응하고, release-pinned manual의
  현재 source claim을 과장하지 않는지 read-back한다. diagram 변경은 없으므로
  `bluetape-diagram`은 N/A이며 evidence에 이유를 기록한다.

- [ ] **Step 3: Commit documentation slice**

  ```bash
  git add README.md README.ko.md leader-core/README.md leader-core/README.ko.md \
    leader-micrometer/README.md leader-micrometer/README.ko.md \
    docs/manual/en/modules/bluetape4k-leader-micrometer.md \
    docs/manual/ko/modules/bluetape4k-leader-micrometer.md
  git commit -m $'OBS-03 audit exporter 사용 계약을 README와 manual에 반영한다\n\nConstraint: README는 concise entry point이고 manual은 release-facing source of truth다.\nRejected: 한 locale만 수정 | public API와 redaction guidance가 불일치함\nConfidence: high\nScope-risk: moderate\nDirective: JSONL/OpenTelemetry 후속 범위를 문서에서 명확히 유지한다.\nTested: locale parity, manual validator, diff check\nNot-tested: CI workflow는 변경하지 않는다.'
  ```

## Task 8: 통합 검증과 performance/stability scan

**Files:**

- Inspect all AUD-01~03 changed files and tests
- Create only if required: `docs/review/2026-08-18-issue-535-audit-export-review.md`

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
  and per-lock Micrometer tags. Record the command, files, and either PASS or a
  repaired finding in the tracked review artifact.

- [ ] **Step 4: Run final acceptance matrix**

  | Acceptance | Evidence |
  |---|---|
  | token/raw sensitive value absent | `LeaderAuditExportEventTest` + boundary fixture |
  | blocking/suspend/event bridge parity | bridge tests + cancellation test |
  | queue/drop/failure isolation | bounded exporter tests + fake delivery |
  | HTTP status/retry/backoff/close | loopback HTTP tests |
  | Micrometer finite cardinality | `MicrometerLeaderAuditExporterTest` |
  | no dependency/module registration | `git diff`, `./gradlew projects` |
  | docs locale parity | README/manual validator and read-back |

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
| bounded admission/close/retry | 2, 8 |
| history and lifecycle bridges | 3, 8 |
| Micrometer accepted/drop/retry/failure | 5, 8 |
| HTTP status classification and webhook delivery | 6, 8 |
| README/manual locale parity | 5, 7 |
| no new dependency/module and ABI compatibility | 4, 8, 9 |
| JSONL/OpenTelemetry excluded | 6, 7, 9 |
| stacked PR exact base/head and fresh CI/review | 9 |

## Rollback and rerun points

- Task 1–4 failure: revert the latest Lore commit on AUD-01; existing history/event
  APIs remain unchanged because all new types are opt-in.
- Task 5 failure: drop AUD-02 branch/commits and retain the green AUD-01 head; core
  exporter behavior is independent of Micrometer.
- Task 6 failure: drop AUD-03 transport commits and retain AUD-02; no HTTP request is
  created by existing elector code without explicit opt-in.
- Task 7 validator or locale mismatch: repair docs only and rerun manual/read-back
  checks; do not rerun code tests unless a code claim changed.
- Any P1 from performance/stability review: stop PR delivery, repair the affected
  task, rerun its focused test and all dependent validation rows.
