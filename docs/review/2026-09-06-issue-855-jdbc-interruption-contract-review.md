# Issue #855 JDBC transaction interruption contract 7-Tier 코드 리뷰

## DoD 판정

- 기준: `origin/develop` `5d3eefe4376634f25da5879ac8329764adcc34b3`
- 구현 검토 commit: `133c8302467f3e190e41acf49a77fd7246d603a2`
- 변경 branch/worktree: `test/issue-855-jdbc-interruption-contract` / `.worktrees/test/issue-855-jdbc-interruption-contract`
- 이슈: [#855](https://github.com/bluetape4k/bluetape4k-leader/issues/855), milestone `1.1.0`
- 요청 모델 계약: `gpt-5.6-luna`, effort `max`
- 검토 provenance: 사용자 standing rule에 따른 인라인 exact-diff 검토이며 독립 검토로 간주하지 않는다.
- 인라인 판정: **P0=0, P1=0, P2=0, P3=0**
- 전달 판정: **PENDING** — 로컬 검증은 완료됐고 PR exact-head CI와 review/thread read-back이 남아 있다.

## 7-Tier 결과

| Tier | 판단 및 근거 | 상태 |
|---|---|---|
| 1. Kotlin correctness | `JdbcTransactionInterruptionContractTest.kt:35-130`의 네 parameterized test와 `:151-376`의 private harness만 추가했다. nullable terminal interrupt expectation으로 Connector/J의 미보장 값을 표현하고 `assertFailsWith` 및 bluetape4k assertion을 사용한다. | PASS |
| 2. Concurrency/cancellation | `JdbcTransactionInterruptionContractTest.kt:179-205`에서 database system view의 marker query가 active임을 확인한 뒤에만 cancellation을 주입한다. `:225-241`은 `Statement.cancel()` 후 connection close fallback과 executor termination을 확인한다. | PASS |
| 3. API/ABI | production source, public type, method, dependency를 변경하지 않았다. binary compatibility는 `artifacts=16`, `ignored=1`, `unknown=0`, `unclassified=0`으로 통과했다. | PASS |
| 4. JDBC backend semantics | `JdbcTransactionInterruptionContractTest.kt:313-370`에서 H2 2.4.240, pgjdbc 42.7.13, Connector/J 9.7.0의 실제 예외 class, SQLState, system-view query를 고정했다. caller future cancel, worker interrupt, driver `Statement.cancel()`을 서로 다른 계약으로 검증한다. | PASS |
| 5. Tests/flakiness | targeted matrix는 최종 코드에서 12/12 통과했고 구현 중 3회 반복도 모두 12/12였다. active probe 제거 변이는 4개 중 3개가 실패해 timing gate의 검출력을 확인했다. 전체 JDBC module은 367/367 통과했다. | PASS |
| 6. Security/operations | 새 credential이나 외부 endpoint를 추가하지 않는다. `leader-exposed-jdbc/README.ko.md:201-226`과 대응 영문은 production query, credential, timeout, retry, 취소 정책을 caller 책임으로 유지하며 Connector/J terminal interrupt flag를 완료 근거로 사용하지 않도록 제한한다. | PASS |
| 7. CI/delivery | root detekt와 ABI gate는 73 tasks 모두 통과했다. PR exact-head CI, reviews/threads, mergeability는 PR 생성 뒤 확인한다. | 로컬 PASS; PR PENDING |

## 발견사항과 처분

| Severity | 발견 | 처분과 근거 |
|---|---|---|
| P1 -> 해소 | query가 실제 실행되기 전에 cancellation을 주입하면 테스트가 cancellation 계약을 검증하지 않고 통과할 수 있었다. | `JdbcTransactionInterruptionContractTest.kt:179-186,294-300`에서 session id와 marker를 조회하는 `awaitActive()` barrier를 추가했다. barrier를 우회한 변이에서 H2 4개 중 3개가 실패했다. |
| P1 -> 해소 | caller/worker 경로에서 `Statement.cancel()`이 정상 반환하거나 future가 이미 canceled면 transaction이 commit 경로에 도달할 수 있었다. | `JdbcTransactionInterruptionContractTest.kt:192-195,254-282`에서 cancel 전에 `rollbackOnly`를 설정하고 terminal 경로에서 rollback을 우선하도록 수정했다. `:214-223`은 세 driver의 probe 값 `0`을 확인한다. |
| P2 -> 해소 | H2 cancel 예외를 `JdbcSQLNonTransientException`으로 가정했지만 실제 결과는 `JdbcSQLTimeoutException`이었다. | resolved H2 2.4.240의 실제 예외와 SQLState `57014`로 test/spec/README를 맞췄다. |
| P2 -> 해소 | Connector/J cancel 결과를 `MySQLQueryInterruptedException`/`70100`으로 가정했지만 직접 `Statement.cancel()`은 `MySQLStatementCancelledException`/`null`이었다. | Connector/J 9.7.0 source와 실제 실행 결과를 기준으로 matrix를 수정했다. |
| P2 -> 해소 | Connector/J worker interrupt flag를 terminal 시점 공통 계약으로 고정하려 했지만 반복 실행에서 `true`와 `false`가 모두 관찰됐다. | `JdbcTransactionInterruptionContractTest.kt:61-70,313-336`에서 주입 직후 flag만 공통 검증하고 terminal flag는 Connector/J에서 미보장으로 남겼다. H2와 PostgreSQL만 보존을 단언한다. |
| P2 -> 해소 | 최초 close 경로가 cancel 후 종료 여부와 executor termination을 확인하지 않았고 connection 획득 실패가 latch를 남길 수 있었다. | `JdbcTransactionInterruptionContractTest.kt:225-241,254-282`에 outer `try/catch/finally`, connection close fallback, 두 단계 bounded wait, executor termination check를 추가했다. |

## 실행 모델 및 lifecycle matrix

| 경로 | caller-visible 상태 | JDBC query | transaction/history/lock |
|---|---|---|---|
| `CompletableFuture.cancel(false)` | canceled | 자동 중단되지 않고 active 유지 | test adapter가 명시적으로 cancel하고 rollback |
| `Thread.interrupt()` | worker flag 설정 | 자동 중단되지 않고 active 유지 | 명시적 cancel과 rollback; Connector/J terminal flag는 미보장 |
| `Statement.cancel()` | exceptional completion | driver별 cancel 예외로 종료 | probe rollback |
| Leader integration | 반환 future에 driver cause 전달 | active 이후 cancel | `FAILED` history 1개, terminal 1회, 동일 lock 재획득 |

## 검증 증거

| Check | 결과 |
|---|---|
| compile RED | `RunningJdbcTransaction` unresolved reference로 실패 |
| driver contract RED | H2 예상 예외 불일치, Connector/J 예외/SQLState 및 terminal flag 가정 불일치 확인 |
| active barrier mutation | barrier 우회 시 H2 4개 중 3개 실패 |
| targeted 반복 | 3회 연속 12/12 PASS, 14초/13초/14초 |
| 최종 exact-code targeted | 12/12 PASS, 14초 |
| full module | 367/367 PASS, failures/errors/skipped 0, Gradle 51초 |
| module detekt | fresh `--rerun-tasks --no-build-cache` PASS |
| root detekt + ABI | 73 tasks PASS, `artifacts=16`, `ignored=1`, `unknown=0`, `unclassified=0` |
| README locale parity | driver version, 예외, SQLState, 책임 경계를 영문/한국어에서 대조 |
| Korean terminology audit | spec/plan/review/lesson/README.ko 5 files, findings 0 |
| PR exact-head CI | PENDING |

## 최종 판정

현재 구현 diff에 남은 P0/P1/P2/P3 finding은 없다. 새 검증은 실제 query 실행을 확인한 뒤 세 interruption 수단을 구분하고, rollback과 Leader history/lock 회수를 함께 증명한다. production/public API는 바뀌지 않았다. 로컬 pre-PR gate는 통과했으며 PR exact-head CI가 완료되기 전까지 전달 상태는 **PENDING**이다.

## Writer DoD

- SPW-01 PASS: JDBC test maintainer를 독자로 두고 issue, exact diff, driver source와 실행 결과를 근거로 고정했다.
- SPW-02 PASS: 범위, severity, 위치/행동 근거, 처분, 검증, 남은 delivery gap과 verdict를 포함했다.
- SPW-03 PASS: KO-01~KO-06 기준으로 기술 용어와 불확실성을 유지했다.
- SPW-04 PASS: 모든 finding을 구현 commit과 RED/GREEN 또는 반복 검증에 연결했다.
- SPW-05 PASS: 표, 수치, final verdict를 다시 읽었고 unresolved placeholder가 없다.
