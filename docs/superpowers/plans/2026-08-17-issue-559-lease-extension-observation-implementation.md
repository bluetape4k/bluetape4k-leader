# Issue #559 lease-extension observation 구현 계획

## 문서 상태

- 이슈: [#559](https://github.com/bluetape4k/bluetape4k-leader/issues/559)
- Epic: [#699](https://github.com/bluetape4k/bluetape4k-leader/issues/699)
- train: `OBS-02`
- 승인된 사양: `docs/superpowers/specs/2026-08-17-issue-559-lease-extension-observation-design.md`
- 기준 head: `42f42ffa6df4d2906a4312fc9b7acb14d75439e9`
- 작업 worktree: `.worktrees/epic-obs-02-extension-observation`
- 계획 상태: 사용자 계획 승인 대기

이 계획은 승인된 사양을 테스트 우선으로 구현하기 위한 실행 순서와 stacked PR train 경계를 고정한다. 계획 승인 전에는 production code, README, PR, merge를 변경하지 않는다.

## 목표와 불변 조건

1. `leader-core`에 framework-neutral lease-extension observer contract와 process-local registry를 추가한다.
2. blocking/suspend `LockExtender`와 blocking/suspend watchdog가 같은 `ExtendOutcome`을 terminal event로 관찰하도록 연결한다.
3. callback 실패가 lease ownership, deadline, cancellation, watchdog retry/stop 결과를 바꾸지 않도록 한다.
4. `leader-micrometer`와 `leader-spring-boot`에서 bounded low-cardinality Observation을 선택적으로 등록하고 context destroy 때 registration을 닫는다.
5. EN/KO manual과 README에서 API, redaction, cancellation, watchdog, rollback을 설명하고 #559 stale marker를 제거한다.

다음은 구현 전체에서 보존한다.

- 정상 contention은 예외가 아니라 `NotHeld`/`false`로 유지한다.
- `CancellationException`은 event로 평탄화하지 않고 재전파한다. `Error`는 observer task 밖으로 재전파한다.
- `Extended`일 때만 기존 `lastExtendDeadline` 갱신을 수행하고, 그 뒤 terminal event를 publish한다.
- observer가 없으면 `hasObservers()` guard에서 event/context/timing allocation을 건너뛴다.
- event/context는 일반 immutable non-`Serializable` class이며 명시적 `equals`/`hashCode`와 redacted `toString`을 제공한다.
- public JVM API는 사양 표의 exact object-member `@JvmStatic` 선언과 기존 overload descriptor를 유지한다.
- 새 dependency, backend별 wrapper, `LeaderElectionListener` API 변경, watchdog context/registry overload는 추가하지 않는다.

## Stacked PR train

| 순서 | branch | base | 책임 | 완료 조건 |
| --- | --- | --- | --- | --- |
| 1 | `feat/obs-02-core-contract` | `develop` | core event/value/registry contract와 registry concurrency tests | core contract tests PASS, ABI fixture PASS |
| 2 | `feat/obs-02-renewal-boundaries` | PR 1 exact head | `LockExtender` 4개 detailed boundary와 watchdog blocking/suspend 연결 | core boundary/watchdog tests PASS, 기존 delegate semantics 유지 |
| 3 | `feat/obs-02-micrometer-spring` | PR 2 exact head | Micrometer observer, Spring auto-configuration, lifecycle tests | micrometer/spring targeted tests PASS, NOOP 조건 PASS |
| 4 | `feat/obs-02-docs` | PR 3 exact head | manual/README EN·KO, stale-marker 제거, 문서 검증 | docs scan/links/`git diff --check` PASS |

모든 PR은 #559를 링크하고 Issue의 assignee `debop`, milestone `0.6.0`, labels `enhancement`, `feature`, `integration`을 반영한다. 각 PR의 마지막 H2는 정확히 `## DoD Status`이며, PR 4가 전체 acceptance와 최종 CI를 대표한다. PR 2~4는 선행 PR이 merge된 뒤 base/head를 다시 읽어 rebase 또는 branch recreation을 결정한다.

현재 계획 worktree의 사양·review·계획 문서는 승인된 첫 PR에 포함한다. 계획 승인 후 `develop` exact head에서 PR 1 branch를 만들고 이 문서들을 옮겨 담아, 첫 PR의 base가 문서 작성용 임시 branch가 되지 않도록 한다.

## Task 0 — 실행 전 고정 및 baseline

- [ ] 승인된 사양, 이 계획, `.bluetape/obs-02-checklist.md`, workflow receipt를 같은 worktree에서 read-back한다.
- [ ] `develop == origin/develop == 42f42ffa…`와 canonical worktree clean 상태를 확인한다. 다른 worktree 변경은 보존한다.
- [ ] 현재 baseline을 다시 실행한다.

```bash
./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-micrometer:test --no-daemon --no-configuration-cache --console=plain
```

- [ ] baseline 결과, JDK/Gradle, exact base head를 checklist에 기록한다.

## Task 1 — PR 1 core contract RED → GREEN

### 대상 파일

- 생성: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserver.kt`
- 생성 또는 분리: `LeaderLeaseExtensionContext`, `LeaderLeaseExtensionEvent`, source/execution enum, `LeaderLeaseExtensionObservers`
- 생성: `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserversTest.kt`
- 생성: `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionApiContractTest.kt`
- 생성: `leader-core/src/test/java/io/bluetape4k/leader/LeaderLeaseExtensionJavaApiFixture.java` (Java compile/`javap` surface fixture)

### RED 테스트

- `addObserver`가 registration token을 반환하고 `close()`가 자기 registration만 idempotently 제거하는지 검증한다.
- 동일 observer 다중 등록과 identity 기반 `removeObserver`가 모든 일치 entry만 제거하는지 검증한다.
- COW snapshot에서 publish 중 add/remove가 현재 snapshot membership을 바꾸지 않는지 검증한다.
- `hasObservers=false → add` 현재 attempt 누락, `true → remove` 불필요 allocation, accepted task-after-close 정책을 동시성 테스트로 고정한다.
- per-registration 256/global 1024 permit, non-blocking drop, `droppedCount`가 누락된 observer delivery 수를 누적하는지와 warning coalescing을 검증한다.
- callback 일반 `Exception` 격리, callback `CancellationException` 격리, fatal `Error` 재전파, permit `finally` 반환을 검증한다.
- `Context/Event` constructor, explicit equality, redacted `toString`, non-`Serializable` surface를 검증한다.
- `javap`/Java fixture로 `@JvmStatic` add/remove/droppedCount, nullable context constructor, `copy`/`componentN` 부재, `@JvmSynthetic` bridge Java source 비노출과 `ACC_SYNTHETIC`을 검증한다.
- 구현 전 `LockExtender`와 두 `LeaderLeaseAutoExtender.start` overload의 ABI descriptor를 저장하고 구현 후 동일함을 비교한다.

예상 RED 실행:

```bash
./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtensionObserversTest' --tests '*LeaderLeaseExtensionApiContractTest' --no-daemon --no-configuration-cache --console=plain
```

### 구현 및 GREEN

- COW registration array와 registration별/global semaphore를 사용한다.
- `publish(event)`는 snapshot array reference를 읽는 순간 delivery membership을 선형화한다.
- admission은 `tryAcquire`만 사용하고 warning/appender를 기다리지 않는다.
- virtual-thread 시작 실패는 drop count를 증가시키고 두 permit을 `finally`에서 반환한다.
- `toString()`은 Context에서 고정된 타입명만, Event에서 bounded source/execution/outcome만 출력한다.
- registry dispatcher 수명은 `LeaderLeaseAutoExtender.shutdown()`과 독립시킨다.

검증:

```bash
./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtension*' --no-daemon --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-core:compileKotlin --no-daemon --no-configuration-cache --console=plain
```

## Task 2 — PR 2 LockExtender/watchdog RED → GREEN

### 대상 파일

- 수정: `leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt`
- 수정: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt`
- 수정/생성: `leader-core/src/test/kotlin/io/bluetape4k/leader/LockExtenderTest.kt`
- 수정/생성: `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtenderDelegateTest.kt`
- 생성: `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionBoundaryContractTest.kt`

### RED 테스트

- blocking unnamed/named detailed와 suspend unnamed/named detailed가 각각 정확히 한 USER event를 publish하는지 검증한다.
- blocking/suspend Boolean wrapper와 Kotlin/Java `Duration` overload도 detailed 경로를 통해 정확히 한 event만 발행하는지 검증한다.
- outside scope, name mismatch, FailOpen은 `NotHeld`, context null 또는 lock-name-only, elapsed `0`을 검증한다.
- active `Real`은 lock name과 audit leader ID만 context에 넣고 delegate outcome reference를 그대로 전달하는지 검증한다.
- `Extended` deadline 갱신 후 event가 publish되는지, Boolean wrapper가 이중 publish하지 않는지 검증한다.
- `CancellationException`/`Error`는 event 없이 원래 예외를 전달하고, 다른 `Exception`은 observation-only `BackendError` 후 원래 예외를 재전파하는지 검증한다.
- watchdog blocking/suspend가 같은 delegate reference를 사용해 `WATCHDOG` source와 올바른 execution을 publish하는지 검증한다.
- scheduler admission `RejectedExecutionException`은 event 없이 기존 no-op/close를 유지하고, delegate-thrown rejection은 `BackendError` event 후 기존 cancel/stop을 유지하는지 검증한다.
- watchdog context는 항상 null이며 기존 `start` JVM overload가 변하지 않는지 ABI fixture로 검증한다.

### 구현 및 GREEN

- 각 public detailed boundary는 `hasObservers()`를 timing/event allocation보다 먼저 호출한다.
- delegate invocation은 `System.nanoTime()` 차이로 elapsed를 측정하고 음수는 0으로 clamp한다.
- 기존 `lastExtendDeadline`, `handleOutcome`, retry/stop, close/wait semantics를 변경하지 않는다.
- event publish는 delegate 결과 처리와 deadline 갱신 이후, caller return/exception semantics 직전에 수행한다.
- watchdog scheduler 제출부와 delegate 호출부의 rejection catch를 분리한다.

검증:

```bash
./gradlew :bluetape4k-leader-core:test --tests '*LockExtenderTest' --tests '*LeaderLeaseAutoExtender*' --tests '*LeaderLeaseExtensionBoundaryContractTest' --no-daemon --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-core:check --no-daemon --no-configuration-cache --console=plain
```

## Task 3 — PR 3 Micrometer/Spring RED → GREEN

### 대상 파일

- 생성: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerObservationLeaderLeaseExtensionObserver.kt`
- 생성: `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/MicrometerObservationLeaderLeaseExtensionObserverTest.kt`
- 수정: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfiguration.kt`
- 생성/수정: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/ObservationRegistryNotNoopCondition.kt`
- 생성: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaseExtensionObservationRegistrationManager.kt`
- 수정: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfigurationTest.kt`
- 필요 시 수정: Spring metadata/imports files

### RED 테스트

- `Extended`, `NotHeld`, `WrongThread`, `BackendError`의 source/execution/outcome/result mapping과 observation name을 검증한다.
- elapsedNanos가 tag로 노출되지 않고, bounded low-cardinality 값만 기본 tag가 되는지 검증한다.
- 기본 옵션에서 lock/leader/exception detail이 기록되지 않고, opt-in에서만 high-cardinality/detail이 허용되는지 검증한다.
- `ObservationRegistry.NOOP`와 registry 미설정 시 observer registration bean이 생성되지 않는지 검증한다.
- Spring context close 시 registration handle이 닫히고 global registry에 callback이 남지 않는지 검증한다.
- 동일 `ObservationRegistry`를 공유하는 두 ApplicationContext가 순차·동시에 생성되어도 context별 handle은 두 개, shared core registration과 Micrometer callback은 event당 한 번이고 acquire/last-close race에서도 double registration·조기 close가 없으며 마지막 close 뒤 manager 누수가 없는지 검증한다. 서로 다른 registry는 각각 한 번 등록되는지 검증하고, 동일 registry의 옵션 충돌은 observer 추가 없이 `IllegalStateException`으로 fail-fast해 redaction 약화를 막는지 고정한다.
- accepted callback이 close 뒤 실행될 수 있지만 extension 결과를 막지 않는지 검증한다.

### 구현 및 GREEN

- public implementation class는 `io.bluetape4k.leader.micrometer.MicrometerObservationLeaderLeaseExtensionObserver`로 고정한다. Spring이 주입하는 타입이므로 public class로 두되 observer 구현체 외부에 새로운 public SPI를 만들지 않는다.
- observation name은 `bluetape4k.leader.lease.extension`으로 고정하고, `source`, `execution`, `outcome`, `result` tag 이름과 값은 observer 파일의 `internal`/`private` 상수로 둔다. public constant API는 추가하지 않는다.
- observer는 `Observation.createNotStarted("bluetape4k.leader.lease.extension", registry)`를 사용한다.
- source/execution/outcome/result만 low-cardinality로 기록한다.
- `LeaderObservationOptions`의 기존 instance와 redaction/tag policy를 재사용한다.
- `ObservationRegistryNotNoopCondition`은 실제 registry bean의 `isNoop`를 확인하며, NOOP이면 registration bean 자체를 만들지 않는다.
- Spring bean은 `@Bean(destroyMethod = "close")`가 반환하는 context별 `AutoCloseable` handle 하나를 소유한다. `LeaseExtensionObservationRegistrationManager`는 `internal object` process-global singleton으로서 `IdentityHashMap<ObservationRegistry, Entry>`와 하나의 lock을 사용해 registry identity별 shared core registration 하나와 ref-count를 유지하고 acquire/ref-count/last-close를 선형화한다. 마지막 handle에서만 `LeaderLeaseExtensionObservers` registration을 닫는다. observer 객체를 별도 Spring bean으로 노출하지 않으며, context destroy가 자기 handle만 닫고 process-local dispatcher는 계속 독립적으로 유지한다. 동일 registry에 서로 다른 `LeaderObservationOptions`가 들어오면 observer를 추가 등록하거나 조용히 옵션을 재사용하지 않고 `IllegalStateException`으로 fail-fast하며, 마지막 close 후 registry/handle strong reference를 제거한다. 사용자가 직접 core facade에 등록한 observer는 이 manager의 dedup 대상이 아니다.
- Micrometer 모듈이 core를 framework-neutral하게 유지하는지 dependency/source scan으로 확인한다.

검증:

```bash
./gradlew :bluetape4k-leader-micrometer:test --tests '*LeaseExtension*' --no-daemon --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderObservationAutoConfigurationTest' --no-daemon --no-configuration-cache --console=plain
```

## Task 4 — PR 4 문서와 최종 acceptance

### 문서 대상

- `docs/manual/en/core/lease-extension.md`
- `docs/manual/ko/core/lease-extension.md`
- root/core/micrometer/spring의 EN/KO README
- 필요 시 `examples/prometheus-dashboard` 설명과 관련 docs index

문서는 source, outcome, cancellation, redaction, watchdog context/drop/lifecycle, NOOP/disable rollback을 설명한다. `issue #559` stale 표현은 모든 tracked README에서 정확한 결합 regex로 0건이어야 하며 Issue #74 정상 문구는 보존한다.

검증:

```bash
rg -n -i --glob 'README*' '(issue[[:space:]]*#559.*(deferred|tracked separately|out of scope|follow-up|별도로|미뤘|범위 밖|후속))|((deferred|tracked separately|out of scope|follow-up|별도로|미뤘|범위 밖|후속).*issue[[:space:]]*#559)' .
rg -n 'LeaderLeaseExtensionObserver|LeaderLeaseExtensionEvent|LeaderLeaseExtensionObservers|bluetape4k.leader.lease.extension' leader-* docs/manual
git diff --check
```

## Task 5 — 전체 검증과 PR gate

- [ ] 변경 모듈 targeted tests 및 `check`를 순서대로 실행한다.
- [ ] core → micrometer → spring → docs 순서의 dependent validation을 증명한다.
- [ ] 필요 시 전체 `./gradlew test`와 `./gradlew detekt`를 실행하고, 실패/skip을 PASS로 분류하지 않는다.
- [ ] public ABI 검사와 Kotlin/Java compile fixture를 재실행한다.
- [ ] virtual-thread 시작 실패 후 permit 반환, callback `Error` escape, `hasObservers` guard/snapshot race를 latch·uncaught-handler 기반 결정적 테스트로 재실행한다.
- [ ] 모든 PR에서 exact head, base, assignee, milestone, labels, linked issue, final `## DoD Status`, CI, review/thread, mergeability를 live-read한다.
- [ ] PR 4에서만 전체 train merge-ready 판정을 내리고, fresh exact-head merge approval 전에는 merge하지 않는다.
- [ ] merge 후 canonical `develop` sync, merged SHA, clean worktree, safe cleanup을 별도 증명한다.

## 위험과 중단 조건

- public ABI 또는 기존 `LeaderObservationOptions` descriptor가 변하면 즉시 P1로 중단한다.
- callback이 extension critical path를 기다리거나 global permit이 무한히 늘어나면 구현을 중단하고 registry 설계를 재검토한다.
- cancellation/event matrix 또는 scheduler/delegate rejection 경계가 기존 동작과 충돌하면 PR을 쌓지 않고 먼저 사양을 다시 연다.
- NOOP registry bean, Spring context destroy, README stale scan 증거가 없으면 PR 3/4를 만들지 않는다.
- CI skip/pending/요청되지 않은 human review는 PASS로 취급하지 않는다.

## 계획 DoD

- `[ ]` 계획 자체가 사용자 승인을 받았다.
- `[ ]` 각 stacked PR의 base/head와 책임 파일이 승인되었다.
- `[ ]` PR 1~4의 RED→GREEN, targeted validation, final CI, exact-head metadata, DoD body가 기록되었다.
- `[ ]` merge/sync/cleanup은 별도 fresh approval 이후에만 수행한다.
