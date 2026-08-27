# Issue #774 diagnostics observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and
> `bluetape-kotlin-patterns` while implementing each task. Follow the task order,
> keep the child PRs independently verifiable, and record the exact validation
> output before moving to the next child.

**Goal:** Issue #774의 `UNKNOWN` 원인 신호, backend connectivity 관측성,
readiness/health/Ktor 해석 규칙을 기존 #766 diagnostics 계약 위에 additive하게
구현한다. 기존 `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED` 상태와 custom provider의
예외 소유권은 유지하고, 민감정보 없는 저카디널리티 reason만 추가한다.

**Architecture:** `leader-core`가 상태와 reason 불변식, bounded probe 예외 경계,
기본 provider의 unsupported 의미를 소유한다. `leader-micrometer`는 기존
instrumented elector의 `MeterRegistry`와 backend-name sanitizer를 재사용하는
private provider decorator로 active probe 결과만 counter에 기록한다. Spring
health와 Ktor route는 같은 core 결과를 소비하되 readiness, transport status,
애플리케이션 pipeline 예외를 서로 합치지 않는다. 문서는 release-pinned manual을
건드리지 않고 EN/KO draft와 README에 같은 상태 표와 운영 runbook을 기록한다.

**Tech Stack:** Kotlin/JVM, Kotlin `Duration`, Micrometer, Spring Boot Actuator,
Ktor 3.x, JUnit 5, `io.bluetape4k.assertions`, `MultithreadingTester`, Gradle
version catalog, `checkBinaryCompatibility`, Kotlin consumer compile, `jar tf`,
`javap`, repository manual validators.

## 0. 고정 전제와 범위

1. 작업 기준은 `origin/develop`의 작업 시작 시점 SHA이며, 각 child 생성 직전
   live `origin/develop`와 직전 child의 exact merge head를 다시 읽는다.
2. PR train 순서는 `OBS-01 -> OBS-02 -> OBS-03 -> OBS-04`다. 다음 child는 직전
   child가 rebase merge된 commit을 base로 하며, squash merge는 사용하지 않는다.
3. #766의 helper 도입, provider migration, adapter regression, ABI baseline,
   consumer compile, packaging 검증을 다시 설계하지 않는다. 새 reason field가
   실제 public descriptor에 포함되는 child에서 compatibility 검증만 비례하여
   재실행한다.
4. 현재 Hazelcast/ZooKeeper처럼 legacy 수동 경계를 가진 provider는 이번
   train에서 timeout/cancellation 경계를 재작성하지 않는다. 기존 status와
   `LeaderBackendConnectivity` factory의 기본 reason을 유지하고, migration은
   별도 이슈로 남긴다.
5. 새 backend I/O, lock 획득·해제, lease mutation, retry, background polling,
   executor, dependency, global observer registry를 추가하지 않는다.
6. 공개 문장(KDoc, README, manual, issue/PR, commit)은 한국어로 작성하고,
   Kotlin API·enum·명령·URL·metric name은 정확한 원문을 보존한다.
7. 테스트 예외 검증은 `io.bluetape4k.assertions.assertFailsWith`만 사용한다.
   JUnit `assertThrows`, `kotlin.test.assertFailsWith`, `shouldThrow`를 새로
   추가하지 않는다. `!!`와 삼킨 cancellation도 추가하지 않는다.
8. 1인 개발자 환경이므로 독립 리뷰 lane은 N/A다. 각 child의 7-Tier
   self-review, source-to-claim read-back, targeted test, exact-head CI가 그
   증거를 대신한다.

## 1. Acceptance traceability

| 설계 수용 기준 | 계획 task | 검증 증거 |
| --- | --- | --- |
| 상태와 reason 조합이 bounded하고 `NOT_CHECKED`가 오염되지 않음 | 2.1 | core invariant 테스트와 public constructor/copy 확인 |
| 일반 `Exception`만 `PROVIDER_EXCEPTION`으로 정규화되고 cancellation/interruption/`Error`는 재전파 | 2.2 | probe exception matrix, interrupt flag 및 동일 인스턴스 검증 |
| 기본 provider는 unsupported, helper-backed provider는 unconfirmed를 표현 | 2.3 | provider default/helper callback 테스트; legacy provider migration 제외 증거 |
| 세 instrumented wrapper가 active diagnostics를 한 번 계측하고 passive는 계측하지 않음 | 3.1 | `SimpleMeterRegistry` count/tag/cardinality 테스트와 concurrent first-use 회귀 |
| Spring health가 bounded reason detail을 제공하고 기존 status/warning을 보존 | 3.2 | `LeaderBackendHealthIndicatorTest` status/exception/detail matrix |
| Ktor JSON에 additive `reason`을 포함하고 HTTP/pipeline 경계를 유지 | 4.1 | exact JSON 및 custom exception `StatusPages` 경계 테스트 |
| EN/KO 문서와 Prometheus 예제가 상태·운영 의미를 일치시킴 | 5.1 | locale parity, PromQL lint/read-back, release pin diff |
| 각 child가 7-Tier와 공통 품질 gate를 통과 | 6.1 | targeted/module tests, detekt, ABI/consumer/packaging, CI exact head |

## 2. OBS-01 — core reason contract

### 2.1 RED 테스트와 파일 소유

**소유 파일:**

- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt`
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbe.kt`
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LocalLeaderBackendDiagnostics.kt` only when the additive reason call is required
- Modify: existing helper-backed provider source only for named reason arguments; do not change its timeout or cancellation algorithm
- Modify/Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsTest.kt`
- Modify: `leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsProbeTest.kt`
- Modify: provider tests only where their public result gains an asserted default reason

먼저 다음 RED 사례를 기존 source에 추가한다.

1. `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED` factory 결과가 각각
   `CONNECTED`, `DISCONNECTED`, `CLIENT_STATE_UNCONFIRMED`, `NOT_CHECKED`를
   갖는지 검증한다.
2. `NOT_CHECKED`에 checked time, latency, 또는 다른 reason을 넣으면 실패하고,
   checked status에 null `checkedAt` 또는 `NOT_CHECKED` reason을 넣으면 실패하는
   불변식을 검증한다.
3. probe callback이 일반 `IllegalStateException`을 던지면 결과 status는
   `UNKNOWN`, reason은 `PROVIDER_EXCEPTION`이고 raw message/class가 모델에
   저장되지 않는지 검증한다.
4. callback이 `UNKNOWN`을 반환하면 기본 reason은
   `CLIENT_STATE_UNCONFIRMED`이며, 명시한 `unknownReason`은 해당 callback에만
   적용되는지 검증한다.
5. `unknownReason = NOT_CHECKED`, 음수/무한 timeout은 즉시 실패하고 callback과
   clock이 호출되지 않는지 검증한다.
6. `CancellationException`, `InterruptedException`, `Error`가 동일 인스턴스로
   재전파되고 interruption flag가 복원되는지 기존 #766 matrix로 고정한다.
7. 기본 `LeaderBackendDiagnosticsProvider.checkConnectivity`가
   `PROVIDER_UNSUPPORTED`를 반환하고, helper-backed built-in provider가
   client state를 증명하지 못할 때 `CLIENT_STATE_UNCONFIRMED`를 반환하는지
   검증한다. legacy 수동 provider는 status와 factory 기본값만 검증한다.

실행하여 RED 증거를 기록한다.

```bash
./gradlew :bluetape4k-leader-core:test --tests '*LeaderBackendDiagnosticsTest' \
  --tests '*LeaderBackendDiagnosticsProbeTest' --no-configuration-cache --console=plain
```

### 2.2 최소 구현

`LeaderBackendConnectivityReason`을 `leader-core` diagnostics package에
추가한다. enum vocabulary는 다음 여섯 값으로 고정한다.

```kotlin
NOT_CHECKED,
CONNECTED,
DISCONNECTED,
PROVIDER_UNSUPPORTED,
PROVIDER_EXCEPTION,
CLIENT_STATE_UNCONFIRMED,
```

`LeaderBackendConnectivity`에는 기존 세 property 뒤에 trailing
`reason`을 추가한다. Kotlin source compatibility를 위해 status에 맞는
기본값을 사용하고, JVM 호출자는 기존 생성 descriptor를 유지할 수 있도록
명시적 compatibility constructor/copy overload를 검토한다. data class의
`equals`, `hashCode`, `toString`, serialization 영향과 public constructor는
`javap`로 확인한다.

factory 계약은 다음과 같다.

- `notChecked()`는 `NOT_CHECKED` status/reason과 null time/latency만 반환한다.
- `up()`은 `CONNECTED`, `down()`은 `DISCONNECTED`, `unknown()`은
  `CLIENT_STATE_UNCONFIRMED`를 기본값으로 사용한다.
- 명시적인 reason overload가 필요하면 status와 reason의 불일치를
  `require`로 차단하고, raw exception이나 endpoint를 받지 않는다.

`LeaderBackendDiagnosticsProbe.check`에는 trailing optional
`unknownReason`을 추가한다. timeout 검증, 단일 clock read, callback 호출 순서,
cancellation/interruption/`Error` 경계는 기존 구현과 동일하게 둔다. 일반
`Exception` catch에서 반환하는 결과만 `PROVIDER_EXCEPTION`으로 바꾼다.
`NOT_CHECKED` callback은 계속 `IllegalArgumentException`으로 거절한다.

기본 provider는 `unknownReason = PROVIDER_UNSUPPORTED`를 전달한다. 이미
helper-backed인 Local/Mongo/Lettuce/Redisson provider는 callback의 client
state 미확정을 `CLIENT_STATE_UNCONFIRMED`로 명시한다. Hazelcast/ZooKeeper의
legacy 수동 timeout/catch 구조는 이 child에서 재작성하지 않으며, 필요한 경우
factory 기본값으로만 additive 결과를 얻는다.

### 2.3 GREEN 및 core 품질 확인

```bash
./gradlew :bluetape4k-leader-core:test --tests '*LeaderBackendDiagnosticsTest' \
  --tests '*LeaderBackendDiagnosticsProbeTest' --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-core:detekt --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-core:jar --no-configuration-cache --console=plain
jar tf leader-core/build/libs/*.jar | rg 'LeaderBackendDiagnostics(Probe)?|LeaderBackendConnectivityReason'
javap -classpath leader-core/build/libs/*.jar \
  io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity \
  io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbe
```

Kotlin consumer compile fixture가 있다면 새 field를 읽는 consumer와 기존 세
argument factory 호출을 모두 compile한다. 없으면 기존 `leader-core` test
source와 `checkBinaryCompatibility` 결과를 대체 증거로 기록하고, child PR에서
누락을 명시한다. `git diff --check`와 raw assertion 금지 scan도 통과해야 한다.

### 2.4 OBS-01 self-review와 commit

7-Tier 순서로 입력 검증·API/ABI·상태 불변식·예외/취소·동시성·성능/보안·운영
호환성을 source와 test에 대조한다. 특히 `PROVIDER_EXCEPTION`은 reason enum만
남기고 로그/metric에 예외 원문을 복사하지 않는지 확인한다. Lore commit에는
정확한 테스트와 미실행 CI를 기록한다.

## 3. OBS-02 — Micrometer active diagnostics와 Spring detail

### 3.1 RED 테스트와 구현

**소유 파일:**

- Modify: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerNames.kt`
- Modify: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/InstrumentedLeaderElectors.kt`
- Modify: `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/InstrumentedLeaderElectorsTest.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendHealthIndicator.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendHealthIndicatorTest.kt`

먼저 failing test로 다음을 고정한다.

1. `leader.backend.connectivity` counter가 active
   `checkConnectivity` 또는 `diagnostics(probe = true)`마다 정확히 한 번
   증가한다.
2. passive `diagnostics()`는 counter를 만들거나 증가시키지 않는다.
3. `backend.name`, `status`, `reason` 세 tag만 사용하고 backend name은
   기존 `LeaderMetricTagSanitizer`를 통과한다. exception class/message,
   endpoint, credential, lock name은 tag에 나타나지 않는다.
4. 결과가 `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED`인 경우 reason과 함께
   기록되고, custom provider의 일반 exception은 `PROVIDER_EXCEPTION`으로
   기록한 뒤 원래 exception을 호출자에게 전파한다. `CancellationException`,
   `InterruptedException`, `Error`도 기존 경계를 유지한다.
5. blocking, group, suspend instrumented wrapper 모두 같은 decorator를
   사용하며 provider null 보존과 concurrent first-use meter registration을
   검증한다.
6. Spring health detail에 `reason`이 bounded enum name으로 포함되고 기존
   `UP`/`DOWN`/`UNKNOWN`/`NOT_CHECKED` status와 warning이 유지된다. provider
   호출 예외에는 raw message가 detail에 들어가지 않는다.

### 3.2 최소 구현

`MicrometerNames`에 counter와 tag key 상수를 추가한다. provider decorator는
`LeaderBackendDiagnosticsProvider`를 구현하고 다음 규칙을 지킨다.

- `backendDescriptor`는 delegate 그대로 반환한다.
- `checkConnectivity(timeout)`는 delegate 호출을 `try/finally`로 감싸되,
  성공 결과에서 status/reason을 읽어 한 번 기록한다.
- 일반 exception은 `PROVIDER_EXCEPTION`을 기록할 수 있도록 safe fallback을
  사용하되 delegate가 소유한 예외를 숨기지 않는다. cancellation/interruption/
  `Error`에는 새로운 catch를 추가하지 않는다.
- `diagnostics(probe = false)`는 그대로 위임하고 기록하지 않는다.
- `diagnostics(probe = true)`는 결과의 connectivity를 한 번 기록한다.
  provider가 custom override에서 예외를 던지는 경우에도 결과를 바꾸지 않는다.
- backend name은 `LeaderMetricTagOptions.TAG_BACKEND_NAME`을 사용한 기존
  sanitizer 결과만 metric tag로 사용한다.

세 instrumented class의 `backendDiagnosticsProvider` getter는 delegate
provider를 decorator로 감싸되 이미 decorator인 provider를 중복 계측하지
않는다. 각 wrapper의 기존 `LeaderBackendDiagnosticsAware`와 lease/audit
capability forwarding은 보존한다.

Spring `LeaderBackendHealthIndicator`는 성공한 diagnostics의 reason을
`withDetail("reason", connectivity.reason.name)`으로 추가한다. null 결과와
기존 warning/status 분기는 유지한다. reason 외에 exception 원문이나 provider
payload는 detail에 넣지 않는다.

### 3.3 GREEN 및 Micrometer/Spring 품질 확인

```bash
./gradlew :bluetape4k-leader-micrometer:test --tests '*InstrumentedLeaderElectorsTest' \
  --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderBackendHealthIndicatorTest' \
  --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-micrometer:detekt :bluetape4k-leader-spring-boot:detekt \
  --no-configuration-cache --console=plain
```

`SimpleMeterRegistry`에서 unique meter 수와 tag set을 직접 읽고, count가
background scheduler 없이 active 호출 수와 일치하는지 확인한다. Spring
`ApplicationContextRunner` 또는 기존 indicator fixture를 사용해 auto-config
기본값과 disabled path를 회귀시킨다. OBS-01 exact head를 dependency로
사용하는 Kotlin consumer compile 및 `jar tf`/`javap`를 다시 실행한다.

### 3.4 OBS-02 self-review와 commit

metric cardinality, exception disclosure, wrapper parity, coroutine cancellation,
passive/active 구분을 7-Tier로 확인한다. `leader.backend.connectivity`가
기존 leader election meter와 이름·tag 충돌을 일으키지 않는지 registry dump로
확인하고, PR body에 active/passive 증거와 N/A 독립 리뷰를 기록한다.

## 4. OBS-03 — Ktor JSON과 adapter 경계

### 4.1 RED 테스트와 구현

**소유 파일:**

- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderBackendDiagnosticsRoute.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderBackendDiagnosticsRouteTest.kt`
- Modify only if required by serialization tests: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderJsonSupport.kt`

RED 테스트는 다음을 확인한다.

1. passive JSON에는 `connectivity.reason=NOT_CHECKED`가 있고 기존
   descriptor/status/checkedAt/latencyMillis field가 그대로 있다.
2. active `UP`, `DOWN`, `UNKNOWN` JSON에 각 bounded reason이 추가되고
   field order 및 JSON escaping이 기존 test contract와 일치한다.
3. active diagnostics 결과는 transport 성공 시 HTTP 200을 유지한다. payload의
   `status`가 backend 의미를 소유하며, custom provider가 exception을 전파하면
   application `StatusPages`/pipeline이 HTTP status를 결정한다.
4. route가 `Dispatchers.IO`에서 실행되는 기존 경계를 보존하고, cancellation과
   `Error`를 route가 임의의 JSON 오류로 바꾸지 않는다.

`appendConnectivity`에 `reason`을 기존 connectivity field와 함께 additive하게
직렬화한다. HTTP status, path validation, application-owned exception handling,
provider timeout/deadline semantics는 변경하지 않는다. strict JSON consumer의
호환성 위험은 docs에 기록하고 field 제거·rename은 하지 않는다.

### 4.2 GREEN 및 route 품질 확인

```bash
./gradlew :bluetape4k-leader-ktor:test --tests '*LeaderBackendDiagnosticsRouteTest' \
  --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-ktor:detekt :bluetape4k-leader-ktor:jar \
  --no-configuration-cache --console=plain
```

Ktor test application에서 built-in-like provider, custom provider, exception
provider를 각각 요청하고 response body·HTTP status·application exception을
읽는다. `reason` 외 데이터에 raw exception/endpoint/credential/lock name이
없는지 fixture scan을 수행한다. OBS-01/02 API descriptor 및 consumer compile을
재확인한다.

### 4.3 OBS-03 self-review와 commit

adapter parity를 core/direct, Ktor, Spring 표에 대조하고 readiness signal과
transport result를 합치지 않는다. route JSON additive compatibility와
pipeline ownership을 7-Tier self-review 기록에 남긴다.

## 5. OBS-04 — README, manual draft, Prometheus runbook

### 5.1 문서 소유 파일

- Modify: `README.md`, `README.ko.md`
- Modify: `leader-core/README.md`, `leader-core/README.ko.md`
- Modify: `leader-micrometer/README.md`, `leader-micrometer/README.ko.md`
- Modify: `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md`
- Modify: `leader-ktor/README.md`, `leader-ktor/README.ko.md`
- Modify: `examples/prometheus-dashboard/README.md`, `examples/prometheus-dashboard/README.ko.md`
- Modify: `examples/prometheus-dashboard/provisioning/prometheus/rules/leader-alerts.yml`
- Modify: `examples/prometheus-dashboard/src/test/kotlin/io/bluetape4k/leader/examples/prometheus/PrometheusAssetsTest.kt`
- Create: `docs/manual/drafts/2026-08-28-issue-774-observability.en.md`
- Create: `docs/manual/drafts/2026-08-28-issue-774-observability.ko.md`
- Do not modify: `docs/manual/manifest.yaml`, `docs/manual/generated/manifest.json`

각 EN/KO 문서는 같은 source claim을 설명한다.

1. 상태 표에서 `UP`, `DOWN`, `UNKNOWN`, `NOT_CHECKED`와 six reason vocabulary,
   ownership/readiness의 분리를 설명한다.
2. `leader.backend.connectivity`의 세 low-cardinality tag와 passive 미계측,
   backend-name sanitizer, raw exception/endpoint/credential 금지를 설명한다.
3. Spring health의 `reason` detail, Ktor HTTP 200 payload와 application-owned
   pipeline exception을 구분한다.
4. provider-native timeout은 wall-clock deadline이 아니며, 지연·반복
   `UNKNOWN` 시 active probe bypass, sampling 완화, client/backend 확인,
   rollback(기능 flag 비활성화) 순서를 runbook으로 기록한다.
5. `UNKNOWN`을 자동 `DOWN`이나 page로 승격하지 않고,
   `DOWN`/`PROVIDER_EXCEPTION` 지속 조건과 기존 AOP backend error를 함께
   관찰하는 Prometheus alert를 제시한다. lease-extension metric을 새로
   발명하지 않는다.
6. #766 built-in/custom provider 해석과 legacy provider migration 범위를
   명시한다. release-pinned manual은 새 API가 release에 포함된 뒤 별도 gate에서
   `releaseRef`/`releaseCommit`을 갱신한다.

Prometheus rule은 실제 metric vocabulary만 사용하고 `for` 지속 조건, bounded
  labels, no-page 또는 warning 정책을 테스트 가능한 형태로 고정한다. 기존
  alert와 이름 충돌을 피하고 예제 테스트에서 YAML key, expression, docs link를
  읽어 검증한다.

### 5.2 Writer 및 문서 검증

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  README.ko.md leader-core/README.ko.md leader-micrometer/README.ko.md \
  leader-spring-boot/README.ko.md leader-ktor/README.ko.md \
  examples/prometheus-dashboard/README.ko.md \
  docs/manual/drafts/2026-08-28-issue-774-observability.ko.md
./gradlew :examples:prometheus-dashboard:test --tests '*PrometheusAssetsTest' \
  --no-configuration-cache --console=plain
```

문서 self-review에서 SPW-01~05와 KO-01~07을 source read-back으로 확인한다.
EN/KO heading·표·명령·metric·release pin을 비교하고, placeholder와 stale
`UNKNOWN` 단정 문장을 검색한다. `manifest.yaml` diff가 비어 있는지 별도로
검증한다.

## 6. Stacked PR train, 공통 gate, merge

### 6.1 Child 순서와 base

| 순서 | branch | base | PR 책임 |
|---:|---|---|---|
| 1 | `feat/issue-774-obs-01-core-reason` | live `origin/develop` | core reason/helper/provider additive contract |
| 2 | `feat/issue-774-obs-02-micrometer-health` | OBS-01 exact merge head | Micrometer counter, wrappers, Spring detail |
| 3 | `feat/issue-774-obs-03-ktor-reason` | OBS-02 exact merge head | Ktor JSON reason와 pipeline parity |
| 4 | `feat/issue-774-obs-04-docs-runbook` | OBS-03 exact merge head | EN/KO docs, draft manual, Prometheus example |

각 child에서 다음 순서를 지킨다.

1. exact parent SHA, issue #774 상태, labels/milestone/assignee, working tree,
   user-owned worktree/branch를 다시 읽는다.
2. RED test와 source change를 같은 작은 commit 단위로 수행하고 각 commit은
   Lore trailer를 포함한다.
3. targeted test → module test → detekt → diff/terminology/ABI/consumer/
   packaging 검증을 순서대로 수행한다. 실패 시 원인을 기록하고 다음 child로
   진행하지 않는다.
4. 7-Tier self-review와 PR body의 마지막 `## DoD Status`를 작성한다. PR body와
   comments는 한국어로 작성하고 `Closes #774` 또는 approved linkage를 유지한다.
5. PR 생성 후 live head, changed files, checks, review/thread, mergeability,
   issue linkage, metadata를 읽어 train order를 확인한다.

### 6.2 공통 검증 명령

```bash
./gradlew test --no-configuration-cache --console=plain
./gradlew detekt --no-configuration-cache --console=plain
./gradlew checkBinaryCompatibility --no-configuration-cache --console=plain
git diff --check
```

전체 test가 비용·환경상 실행 불가하면 affected module tests와 실패 원인,
대체 evidence를 명확히 기록한다. Testcontainers를 사용하는 backend test는
Colima/docker context를 먼저 확인하고 healthy runtime을 재시작하지 않는다.
skipped/path-filtered CI는 exact-head runtime proof로 세지 않는다.

### 6.3 Merge와 canonical sync gate

merge 직전에 fresh exact-head approval을 별도 gate로 둔다. 승인 전에는 merge나
auto-merge를 실행하지 않는다. 승인 후에는 다음을 다시 확인한다.

- PR head/base가 plan의 parent SHA와 일치한다.
- required checks가 exact head에서 성공하고 skipped/N/A 이유가 기록되어 있다.
- unresolved review/thread, merge conflict, stale issue linkage가 없다.
- PR body 마지막이 `## DoD Status`이고 변경 파일·테스트·known gap이 최신이다.

GitHub merge는 **rebase merge**로 실행한다. merge commit이 생긴 뒤 canonical
`develop`을 fast-forward sync하고, checkout 변경이 있으면 path-scoped stash로
보존한다. 통합이 증명된 이 작업의 worktree만 cleanup하며 user-owned worktree,
branch, detached provenance ref는 삭제하지 않는다.

## 7. 최종 DoD와 중단 조건

완료 조건은 다음 모두다.

1. OBS-01~04가 순서대로 merge되어 Issue #774와 연결되고, Epic/후속 issue의
   live 상태가 실제 결과와 일치한다.
2. core reason 불변식, 예외 경계, Micrometer active-only counter, Spring
   detail, Ktor additive JSON, EN/KO runbook이 fresh test와 source read-back으로
   증명된다.
3. exact-head CI, affected tests, detekt, ABI/consumer/packaging,
   terminology/diff/manual/example 검증 결과가 보존된다.
4. `docs/manual/manifest.yaml`은 release train 전 unchanged이고, release 후
   pin 갱신을 위한 별도 checklist가 남아 있다.
5. canonical branch와 origin이 같은 SHA이며, cleanup은 proven integrated
   owned target에 한정된다.

다음 조건이면 해당 child에서 중단하고 `PENDING`으로 보고한다: exact-head
   CI 미완료, public ABI/JSON 호환성 불명, provider exception/cancellation
   경계 회귀, raw sensitive tag/detail 노출, 문서 source claim 불일치, 또는
   user-owned worktree/branch와 scope 충돌. 이슈·PR 생성·merge·branch 삭제가
   필요한데 authority가 없으면 해당 외부 side effect만 중단하고 로컬 검증을
   계속한다.

## 8. Plan self-review

- 설계 문서의 모든 목표·대안·상태 표·metric 계약·timeout/runbook·compatibility·
  stacked order·DoD가 task와 validation에 연결되어 있다.
- #766 migration 재수행 금지와 legacy provider의 기본 reason 유지가 OBS-01,
  acceptance, train scope에 반복해서 고정되어 있다.
- `bluetape4k-assertions`, `bluetape-kotlin-patterns`, `MultithreadingTester`,
  no-raw-exception-disclosure, no-new-dependency 규칙이 구현 task와 test gate에
  명시되어 있다.
- release pin을 변경하지 않는 manual 정책과 release 후 별도 gate가 문서 task와
  final DoD에 명시되어 있다.
- 다음 명령으로 plan 자체를 검증한다.

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/plans/2026-08-28-issue-774-observability-plan.md
placeholder_re='T(ODO|BD)|FIX(ME)|fill[[:space:]]+in|la(ter)'
if rg -n -i "$placeholder_re" \
  docs/superpowers/plans/2026-08-28-issue-774-observability-plan.md; then
  exit 1
fi
```

검증을 통과한 뒤 plan을 Lore commit으로 저장하고 OBS-01 worktree를 해당
commit에서 생성한다.
