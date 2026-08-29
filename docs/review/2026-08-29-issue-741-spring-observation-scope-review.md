# Issue #741 Spring 관측 범위 격리 구현 검토

## 범위와 판정

- 대상 diff: `origin/develop@4c0d1156c268cde6191f6901c933b60ae6b92cff...575cccdc505284528eea5bcf645d0b2e8b62124b`
- 범위: core scope/dispatch, USER/WATCHDOG producer, virtual/coroutine adapter, Spring registry manager/owner, single/group AOP, auto-configuration, tests, README/manual, benchmark
- 자체 검토: **PASS (P0=0, P1=0, P2=0)**
- 자동 검증: **PASS**
- 독립 검토: **PENDING** — native `code-reviewer`/`verifier` lane을 네 차례 bounded 실행했지만 각 lane이 90초 이상 결과 없이 정체되어 회수됐다.
- merge gate: exact-head CI와 독립 human/code review를 확인하고 fresh 승인을 받기 전에는 병합하지 않는다.

## 계약별 검토

| 계약 | 판정 | 근거 |
|---|---|---|
| 공개 event ABI | PASS | 5-인자 `LeaderLeaseExtensionEvent`와 기존 global facade를 유지하고 새 scope bridge는 `@JvmSynthetic`으로 Java source에서 숨겼다. JavaCompiler negative fixture와 `checkBinaryCompatibility unknown=0`으로 확인했다. |
| Cross-registry event 격리 | PASS | dispatcher가 wildcard bucket과 전달된 scope identity bucket만 선택한다. Spring manager는 `ObservationRegistry` object identity별 canonical scope를 공유한다. distinct registry 및 same-registry parent/child 테스트가 있다. |
| Raw exception privacy | PASS | 명시적인 global observer는 기존 process-global 계약대로 수신하고, Spring automatic observer는 source registry scope에만 등록된다. 상대 registry에 callback 자체가 admission되지 않는다. |
| Lifecycle | PASS | scope close는 active revoke 후 identity bucket 제거와 registration close를 수행한다. 이미 dispatcher에 수락된 callback은 완료할 수 있고 close 뒤 publish는 무시된다. manager release는 lock 안에서 ref-count와 last-close를 선형화한다. |
| Async propagation | PASS | watchdog는 `start()` 시 scope를 캡처하고 blocking/suspend adapter는 virtual thread/coroutine context에 전달한다. AOP는 sync/suspend/Mono/Flux/Flow와 지원되는 group 경계에 scope를 설치한다. |
| Direct-call fail-closed | PASS | attribution이 없는 direct elector/extension 호출은 Spring automatic scope를 설치하지 않는다. global observer는 기존처럼 event를 받는다. |
| Hot path | PASS | matching bucket은 map identity lookup으로 선택하며 no-observer/mismatch는 event/context/timer 생성 전 빠져나간다. 3-fork 비교가 15% 한도를 통과했다. |
| Shutdown/rollback | PASS | owner clear와 manager last-close가 scope를 revoke한다. 문서에 kill switch, canary, rollback, close 후 late callback 경계를 명시했다. |

## 구현 중 발견하고 해소한 항목

1. scope가 없는 suspend advice에도 `withContext(EmptyCoroutineContext)`를 적용하면 기존 exception identity/wrapping이 달라지는 회귀가 발생했다. scope가 없을 때는 원래 suspend lambda를 직접 실행하도록 수정했고 full Spring suite로 회귀를 닫았다.
2. 원래 `suspendBlock` 변수명을 `executeSuspend`로 바꾸자 Kotlin compiler-generated `WhenMappings` 이름이 달라져 ABI gate가 두 synthetic removal을 보고했다. 변수명을 보존해 `unknown=0`으로 복구했다.
3. USER benchmark가 active leader handle 없이 `LockExtender`를 호출해 WARN과 logging allocation을 측정하던 결함을 발견했다. 실제 `LocalLeaderElector`/`LocalSuspendLeaderElector` 실행 안에서 extension을 호출하도록 수정하고 exact baseline/candidate SHA의 3-fork 결과를 다시 기록했다.

## 검증 증거

- rebased core full: `989 tests`, failures `0`, errors `0`, skipped `0`
- Spring full exact implementation head: `628 tests`, failures `0`, errors `0`, skipped `0`
- `./gradlew detekt`: PASS
- `./gradlew checkBinaryCompatibility`: `artifacts=16 ignored=10 unknown=0`, PASS
- benchmark compile: PASS
- JMH 3 forks: throughput `1.335%` 개선, average-time regression `0.023%`, 15% gate PASS
- manual inventory/validation: PASS
- Ruby manual suite: `37 runs, 392 assertions, 0 failures, 0 errors, 0 skips`
- `git diff --check`: PASS

## 남은 gate

독립 review artifact가 아직 없다. PR 생성과 hosted CI는 진행할 수 있지만 merge는 독립 검토 결과, unresolved thread, exact-head checks, mergeability를 다시 확인하고 사용자에게 fresh 승인을 받은 뒤에만 수행한다.
