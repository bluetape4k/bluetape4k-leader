# Issue #855 JDBC transaction interruption contract 설계

## 문제와 목표

`ExposedJdbcLeaderElector`는 반환 `CompletableFuture`의 취소와 action future의 terminal
cleanup을 연결하지만, 실행 중 JDBC transaction을 실제로 멈추는 의미는 JDBC driver마다
다르다. `Future.cancel(false)`, worker thread interrupt, `Statement.cancel()`을 같은 동작으로
간주하면 query가 계속 실행되는데 lock/history만 먼저 terminalize됐다고 오해할 수 있다.

이 변경은 H2, PostgreSQL, MySQL의 현재 지원 버전에서 세 중단 수단을 deterministic하게
구분하고, driver-level statement cancel로 action transaction이 끝난 뒤 Leader의 lock,
history, exactly-once terminalization 계약까지 검증한다. production 동작이나 공통
cancellation 계약은 넓히지 않는다.

## 고정 matrix

Gradle `testRuntimeClasspath`와 기존 `TestDB`가 선택한 대상은 다음과 같다.

| DB | JDBC driver | 장기 query | active-query probe | cancel SQLState |
|---|---|---|---|---|
| H2 | 2.4.240 | marker가 포함된 bounded CPU query | `INFORMATION_SCHEMA.SESSIONS.EXECUTING_STATEMENT` | `57014` |
| PostgreSQL | pgjdbc 42.7.13 | `pg_sleep(30)` | `pg_stat_activity` | `57014` |
| MySQL | Connector/J 9.7.0 | `SLEEP(30)` | `INFORMATION_SCHEMA.PROCESSLIST` | `70100` |

H2의 `JdbcStatement.cancel()`은 실행 command에 cancel flag를 설정하고 command가
`57014`를 던진다. pgjdbc는 별도 cancel request를 server connection에 보내며,
Connector/J는 별도 session으로 `KILL QUERY`를 실행한다. 이는 resolved source JAR의
`JdbcStatement`, `PgStatement`, `StatementImpl`에서 확인했다.

## Deterministic harness

test-only `RunningJdbcTransaction`이 raw JDBC connection을 전담한다.

1. 고유 probe table과 row를 만든다.
2. worker connection은 `autoCommit=false`에서 row를 갱신한 뒤 marker가 있는 장기 query를
   실행한다.
3. observer connection이 DB별 system view에서 같은 session의 marker를 확인해야만
   interruption을 주입한다.
4. 모든 test는 5초 bounded polling과 query timeout을 사용한다.
5. 종료 경로는 rollback, statement/connection close, executor shutdown을 `finally`에서
   수행하고 probe row가 초기값으로 돌아왔는지 확인한다.

`CountDownLatch` 직후의 임의 sleep만으로 실행 중 상태를 추측하지 않는다. system view가
marker를 확인하지 못하면 interruption을 주입하지 않고 명시적으로 실패한다.

## 세 중단 수단의 계약

### Caller future cancellation

`CompletableFuture.cancel(false)`는 caller-visible future만 취소한다. 이미 실행 중인 raw
JDBC task나 statement cancel을 암시하지 않는다. 테스트는 future가 canceled state가 된
뒤에도 marker query가 active임을 확인하고, 정리 단계에서 명시적으로 `Statement.cancel()`을
호출한다.

### Worker thread interruption

`Thread.interrupt()`는 worker interrupt flag를 설정하지만 JDBC cancellation API가 아니다.
현재 세 driver에서 query가 즉시 terminal state가 되지 않는 것을 확인하고, flag가 보존된
상태에서 명시적 `Statement.cancel()`로 bounded cleanup한다. 이 관찰값은 driver 버전이
바뀌면 재검토해야 하는 driver-specific contract이다.

### Driver-level statement cancellation

`Statement.cancel()`은 실행 중 query를 끝내고 `SQLException`을 노출해야 한다. 공통 계약은
transaction rollback과 connection 종료이며, 구체 exception class와 SQLState는 matrix에
driver별로 기록한다.

## Leader lifecycle integration

`ExposedJdbcLeaderElector.runAsyncIfLeader()` action이 harness completion future를 반환한다.
query가 active인 것을 확인한 뒤 `Statement.cancel()`을 호출하고 다음을 검증한다.

- action future와 반환 future가 exceptional terminal state가 된다.
- raw transaction은 rollback되어 probe row가 갱신되지 않는다.
- `LeaderLockHistoryTable`에는 같은 lock의 `FAILED` row가 정확히 하나 있다.
- action terminal callback은 정확히 한 번 관찰된다.
- 같은 lock으로 다음 `runIfLeader()`가 성공해 lease cleanup을 증명한다.

caller future cancellation만으로 실행 중 transaction이 멈춘다고 주장하지 않는다. 그
경로의 일반 lifecycle 회귀는 #846 테스트가 소유한다.

## 대안

### DB server를 강제 재시작

transaction은 끊기지만 connection failure와 statement cancellation을 구분하지 못하고
Testcontainers 재시작 비용과 flake가 크므로 채택하지 않는다.

### query timeout만 사용

bounded safety net은 되지만 외부 interruption 시점을 제어하지 못하고 caller/worker/driver
경계를 검증할 수 없어 채택하지 않는다.

### Mockito로 Statement를 모킹

호출 여부만 확인할 뿐 실제 driver exception, server query 종료, rollback을 증명하지 못해
채택하지 않는다.

## 책임과 범위

- test harness는 기존 H2/PostgreSQL/MySQL dependency와 `TestDB`만 사용한다.
- production source, dependency, module, schema migration, workflow는 변경하지 않는다.
- 지원 계약은 pinned driver 버전의 관찰값이며 모든 JDBC driver의 보편적 의미가 아니다.
- database credential, production query cancellation policy, timeout, retry는 caller가 소유한다.
- README는 공통 보장과 driver별 관찰값, 명시적 `Statement.cancel()` 필요성을 구분한다.

## 실패 모드와 방어

| 실패 모드 | 관찰 신호 | 방어 |
|---|---|---|
| 실행 전 cancel race | cancel이 no-op이고 장기 query가 계속됨 | system view marker 확인 뒤 interruption 주입 |
| 장기 query hang | test process가 끝나지 않음 | query timeout, 5초 latch/poll, finally cancel/close |
| caller cancel이 task를 끝냈다고 오판 | caller future만 canceled, query active | 별도 task-finished latch와 active probe 확인 |
| transaction rollback 누락 | probe row 값이 1 | 종료 뒤 observer connection에서 0 확인 |
| history 중복 terminalization | 같은 lock history row가 2개 이상 | 정확히 한 row와 `FAILED` status 확인 |
| driver upgrade drift | exception class/SQLState 또는 interrupt 관찰값 변경 | README matrix와 test expectation을 함께 갱신 |

## 수용 기준 추적

| Issue #855 수용 기준 | 설계 대응 |
|---|---|
| driver/version과 deterministic harness | 고정 matrix, session id, system-view active probe |
| caller/worker/statement cancel 구분 | 세 독립 parameterized test |
| transaction/lock/history/exactly-once | rollback probe와 Leader integration test |
| exception/cause/flag/future 기록 | driver outcome data class와 README matrix |
| 반복성과 지원 경계 | bounded polling, targeted 반복, 버전 고정 문서 |
| module/detekt/ABI | 최종 검증 gate |

## 인라인 6관점 설계 리뷰

독립 lane 없이 사용자가 지정한 inline fallback으로 exact spec을 여섯 관점에서 검토했다.
독립 reviewer나 별도 모델 provenance는 주장하지 않는다.

| 관점 | 판정 | 근거 |
|---|---|---|
| 성능 | P0=0, P1=0 | 장기 query는 active 확인 직후 cancel하며 timeout과 polling이 bounded다. |
| 안정성 | P0=0, P1=0 | 실행 전 race, hang, rollback, cleanup, duplicate history를 각각 검증한다. |
| 보안 | P0=0, P1=0 | 기존 TestDB credential만 사용하고 query marker는 random identifier다. |
| 운영 | P0=0, P1=0 | production cancellation policy를 바꾸지 않고 지원 버전 관찰값만 문서화한다. |
| 개발자/API | P0=0, P1=0 | test-only harness이며 production/public API 변경이 없다. |
| 사용자/caller | P0=0, P1=0 | future cancellation, thread interrupt, statement cancel의 보장 수준을 분리한다. |

통합 판정은 `P0=0`, `P1=0`이다. 최종 구현 diff는 다시 검토한다.

## DoD

- H2/PostgreSQL/MySQL에서 세 interruption 경로가 active transaction을 대상으로 실행된다.
- statement cancel의 rollback, SQLState, exception class가 driver별 기대와 일치한다.
- Leader integration이 FAILED history 1개와 다음 lock 획득 성공을 확인한다.
- targeted 반복, module test, detekt, ABI, 문서 검사가 통과한다.
- exact-head CI, threads, mergeability를 PR에서 다시 읽는다.

## Writer DoD

- SPW-01 PASS: 독자는 JDBC backend maintainer이며 Issue #855와 exact resolved source를 근거로 했다.
- SPW-02 PASS: matrix, harness, 세 계약, Leader integration, 대안, 위험, 수용 기준을 포함했다.
- SPW-03 PASS: Korean naturalness KO-01~KO-06을 확인하고 code token을 보존했다.
- SPW-04 PASS: driver 버전과 cancel 구현을 Gradle 및 source JAR에 대조했다.
- SPW-05 PASS: 공통 보장과 driver-specific 관찰값을 섞지 않고 최종 read-back했다.
