# JDBC interruption 테스트는 실행 중 query를 먼저 증명해야 한다

## 맥락

Issue [#855](https://github.com/bluetape4k/bluetape4k-leader/issues/855)는 H2, PostgreSQL, MySQL에서 실행 중인 JDBC transaction에 caller future cancel, worker interrupt, `Statement.cancel()`을 각각 주입하고 Leader cleanup 계약을 검증한다. 이 세 수단은 같은 cancellation 결과를 보장하지 않는다.

## 놓친 가정과 근거

테스트 worker가 시작됐다는 사실만으로 query도 실행 중이라고 가정하면 cancellation이 statement 실행 전에 도착할 수 있다. active-query barrier를 우회한 변이에서 H2 4개 중 3개가 실패했고, 한 경로는 실행 중 `Statement`가 없어 종료됐다. 따라서 thread 시작이나 임의 sleep은 주입 시점의 증거가 아니다.

driver 결과도 이름으로 추정할 수 없었다. 실제 실행에서 H2는 `JdbcSQLTimeoutException`/`57014`, pgjdbc는 `PSQLException`/`57014`, Connector/J는 `MySQLStatementCancelledException`/`null`을 반환했다. Connector/J worker interrupt flag는 terminal 시점에 `true`와 `false`가 모두 관찰됐다.

caller future가 이미 canceled이거나 `Statement.cancel()`이 예외 없이 반환될 수 있으므로 cancel 호출 자체를 rollback 결정으로 사용해서도 안 된다. rollback 의도를 먼저 기록하지 않으면 test transaction이 commit 경로에 도달할 수 있다.

## 결정

- 각 driver의 session id와 system view를 사용해 marker query가 active인 경우에만 interruption을 주입한다.
- caller future cancel과 worker interrupt는 실행 중 JDBC statement를 자동 중단하지 않는 경계로 검증한다.
- 실제 종료는 test adapter가 소유한 `Statement.cancel()`과 rollback으로 처리한다.
- cancel 전에 `rollbackOnly`를 설정하고, 종료가 지연되면 connection close를 bounded fallback으로 사용한다.
- exception class와 SQLState는 resolved driver 버전의 관찰값으로 고정하되 Connector/J terminal interrupt flag는 미보장으로 남긴다.
- Leader 통합 검증은 예외만 보지 않고 probe rollback, `FAILED` history 1개, terminal 1회, 동일 lock 재획득을 함께 확인한다.

## 결과와 검증

세 driver의 네 경로로 구성한 matrix 12개가 최종 코드에서 통과했다. 구현 중 같은 matrix를 세 번 반복해 12/12 결과를 유지했고, 전체 `leader-exposed-jdbc` 모듈은 367/367 통과했다. root detekt와 binary compatibility도 통과했으며 production/public API 변경은 없다.

## 재발 방지

JDBC cancellation 회귀 테스트는 다음 순서로 작성한다.

1. system view나 driver가 제공하는 positive signal로 대상 query가 active임을 확인한다.
2. caller future, worker thread, JDBC statement의 상태를 서로 다른 assertion으로 검증한다.
3. rollback 의도를 cancellation보다 먼저 기록한다.
4. bounded cancel, connection close fallback, executor termination을 모두 확인한다.
5. exception과 SQLState는 resolved driver에서 직접 측정하고 driver 갱신 때 다시 검증한다.
6. lifecycle 결과는 transaction 상태뿐 아니라 history terminal count와 resource 재획득으로 증명한다.

sleep 증가나 재시도 성공은 active execution과 cleanup 완료의 근거가 아니다. 안정적이지 않은 driver 관찰값은 공통 계약으로 승격하지 않고 문서에 미보장으로 남긴다.

## Writer DoD

- SPW-01 PASS: 다음 JDBC/backend test maintainer를 독자로 두고 issue, RED/GREEN, driver matrix를 근거로 사용했다.
- SPW-02 PASS: 맥락, 잘못된 가정, 증거, 결정, 결과, 재발 방지를 포함했다.
- SPW-03 PASS: KO-01~KO-06에 따라 interruption 수단과 책임 경계를 같은 용어로 유지했다.
- SPW-04 PASS: 각 지침을 실제 실패 또는 검증 결과와 연결했다.
- SPW-05 PASS: 수치와 기술 token을 다시 읽었고 filler나 unresolved placeholder가 없다.
