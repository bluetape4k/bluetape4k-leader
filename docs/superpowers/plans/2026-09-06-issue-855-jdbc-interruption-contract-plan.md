# Issue #855 JDBC transaction interruption contract 구현 계획

> **For agentic workers:** `executing-plans`와 `test-driven-development` 절차로 checkbox를 순서대로 실행한다.

**Goal:** H2/PostgreSQL/MySQL의 실행 중 JDBC transaction에서 caller cancellation, worker
interrupt, `Statement.cancel()`을 구분하고 Leader cleanup/history 계약을 실제 driver로
검증한다.

**Architecture:** `leader-exposed-jdbc` test source에 DB별 query/probe/expected outcome을 가진
private harness를 만들고 네 parameterized test가 raw driver matrix와 Leader lifecycle을
검증한다. README 두 locale에는 pinned version 관찰값과 지원 경계를 기록한다.

## 파일

- Create `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/JdbcTransactionInterruptionContractTest.kt`
- Modify `leader-exposed-jdbc/README.md`
- Modify `leader-exposed-jdbc/README.ko.md`
- Create `docs/review/2026-09-06-issue-855-jdbc-interruption-contract-review.md`
- Create `docs/lessons/2026-09-06-issue-855-jdbc-interruption-contract.md`

## Task 1: Raw JDBC harness TDD

- [x] 실패 테스트에서 `RunningJdbcTransaction`과 세 driver matrix type을 참조한다.
- [x] compile RED로 missing harness를 확인한다.
- [x] 고유 probe table, transaction update, session id, marker query, observer polling을 구현한다.
- [x] 모든 wait에 5초 timeout, statement에 query timeout, terminal/close 경로에 cancel/rollback/close를 둔다.
- [x] H2/PostgreSQL/MySQL별 active query가 관찰된 뒤에만 다음 단계로 진행하는 GREEN을 확인한다.

## Task 2: 세 interruption 경로 분리

- [x] caller `CompletableFuture.cancel(false)` 뒤 caller future는 canceled지만 query가 active임을 확인한다.
- [x] worker `Thread.interrupt()` 뒤 query가 active이고 주입 직후 worker interrupt flag가 설정됨을 확인한다.
- [x] `Statement.cancel()` 뒤 task 종료, rollback, driver별 exception class/SQLState를 확인한다.
- [x] caller/worker test는 assertion 뒤 명시적 statement cancel로 bounded cleanup한다.
- [x] targeted matrix를 반복해 race와 hang이 없는지 확인한다.

## Task 3: Leader lifecycle integration

- [x] action이 harness completion future를 반환하는 `runAsyncIfLeader()` test를 작성한다.
- [x] query active 뒤 statement cancel을 주입하고 반환 future의 cause를 확인한다.
- [x] probe rollback, `FAILED` history 정확히 1개, action terminal 1회, 다음 lock 획득 성공을 확인한다.
- [x] #846의 일반 caller cancellation 테스트는 중복하지 않고 전체 module 회귀로 확인한다.

## Task 4: 문서와 지원 경계

- [x] README 두 locale에 H2 2.4.240, pgjdbc 42.7.13, Connector/J 9.7.0 matrix를 기록한다.
- [x] future cancel/thread interrupt가 statement cancel을 자동 보장하지 않음을 명시한다.
- [x] production query cancellation policy, credential, retry, timeout은 caller 책임으로 남긴다.
- [x] source와 두 locale의 exception class/SQLState/capability를 대조한다.
- [x] Korean terminology audit와 `git diff --check`를 실행한다.

## Task 5: 검증, 인라인 리뷰, lesson, PR

- [x] targeted class를 3회 반복하고 JUnit 건수/실패/skip을 기록한다.
- [x] `:bluetape4k-leader-exposed-jdbc:test --rerun-tasks`를 실행한다.
- [x] `detekt checkBinaryCompatibility`를 실행하고 production/public diff가 없음을 확인한다.
- [x] exact diff를 성능, 안정성, 보안, 운영, 개발자/API, 사용자/caller 관점으로 인라인 검토한다.
- [x] baseline finding과 처분, 검증 근거를 review와 lesson에 기록한다.
- [ ] Lore commit, push, `develop` base PR 생성 후 exact-head CI/threads/mergeability를 확인한다.
- [ ] 세 PR exact head를 묶은 fresh merge 승인 전에는 merge하지 않는다.

## TDD evidence 형식

각 RED/GREEN은 command, exit code, JUnit count, 실패 원인을 기록한다. 실패 결과를 environment
문제로 치부하지 않고 harness race, driver contract drift, production lifecycle regression으로
분류한다. mutation sensitivity는 active-query probe를 우회한 변형이 execution-before-cancel
assertion에 잡히는지 확인한다.

## Rollback

모든 변경은 test와 README/docs에 한정된다. driver별 관찰값이 deterministic하게 고정되지
않으면 해당 expectation을 공통 계약으로 승격하지 않고, raw observation artifact만 남긴 뒤
지원 경계를 명시한다. production cancellation bridge를 바꾸는 것은 별도 bug issue와
승인된 계획 없이는 수행하지 않는다.

## 수용 기준 → Task

| 수용 기준 | Task |
|---|---|
| driver/version, deterministic harness | 1, 4 |
| caller/worker/statement cancel 구분 | 2 |
| transaction/lock/history/exactly-once | 3 |
| exception/cause/flag/future 기록 | 2, 4 |
| 반복성과 지원 경계 | 2, 5 |
| module/detekt/ABI | 5 |

## 인라인 6관점 계획 리뷰

| 관점 | 판정 | 근거 |
|---|---|---|
| 성능 | P0=0, P1=0 | active probe 직후 cancel하고 단일 heavy Gradle lane만 순차 실행한다. |
| 안정성 | P0=0, P1=0 | system-view gate, timeout, finally cleanup, 반복 실행을 선행 task에 둔다. |
| 보안 | P0=0, P1=0 | 새 credential과 external service가 없고 기존 Testcontainers 범위만 사용한다. |
| 운영 | P0=0, P1=0 | driver version drift와 unsupported semantics를 README 및 rollback에 명시한다. |
| 개발자/API | P0=0, P1=0 | test-only private harness이고 dependency/public API 변경이 없다. |
| 사용자/caller | P0=0, P1=0 | 세 interruption 경로를 독립 test와 matrix로 추적한다. |

통합 판정은 `P0=0`, `P1=0`이다. 구현 뒤 exact diff를 다시 검토한다.

## Writer DoD

- SPW-01 PASS: 실행자는 repository context가 없는 JDBC test maintainer다.
- SPW-02 PASS: 파일, TDD 순서, command gate, rollback, PR stop condition을 포함했다.
- SPW-03 PASS: Korean naturalness KO-01~KO-06을 확인하고 identifier를 보존했다.
- SPW-04 PASS: Issue #855의 여섯 수용 기준을 Task에 일대일로 연결했다.
- SPW-05 PASS: 모든 단계에 완료 조건이 있고 unresolved placeholder가 없다.
