# 배운 교훈 - 그룹 선출의 취소·가용성·해제 결과 경계

## 맥락

Issue #721은 Exposed JDBC 그룹 선출과 R2DBC 그룹 lock의 오류·취소 경계를
같은 계약으로 맞추는 작업이다. 가용성 callback은 DB-time fail-closed 상태
전이의 일부이고, 해제 결과는 작업 이력의 성공 여부와 별개의 신호다.

## 발견

- R2DBC `markUnavailable`/`markAvailable`이 `runCatching`으로 callback 예외와
  `CancellationException`을 삼켜 호출자가 DB-time 상태 전이를 관측할 수 없었다.
- JDBC 그룹 비동기 경로는 action `CompletableFuture` 취소를 terminal `FAILED`
  이력으로 남기지 않아 동기·suspend 경로와 감사 결과가 달랐다.
- JDBC 그룹 비동기 경로가 반환한 외부 `CompletableFuture`의 취소는 내부 action에
  전파되지 않아 slot과 audit이 terminal 상태로 수렴하지 않았다.
- 외부 future를 slot 획득 대기 중 취소해도 acquisition retry loop는 기존 wait
  budget이 끝날 때까지 executor를 점유했다.
- JDBC 그룹 비동기 경로가 cleanup stage가 아닌 원래 action future를 반환해,
  호출자가 history 기록과 slot 반납보다 먼저 결과 완료를 관측할 수 있었다.
- JDBC 해제의 `Unit` 계약만으로는 `RELEASED`, `NOT_HELD`, DB 오류를 로그에서
  구분할 수 없었다.

## 결정

R2DBC availability callback은 예외를 허용 범위에서 삼키지 않고 그대로 전파해
fail-closed 신호를 보존한다. JDBC 그룹 비동기 취소도 다른 action 실패와 같이
`FAILED`와 종료 시각을 기록한다. 외부 future 취소는 내부 action future에
전파하고, action 종료·watchdog 종료·history 기록·slot 반납을 단일 terminal
cleanup으로 묶고 public result는 그 cleanup stage 완료 뒤에만 종료한다. JDBC
async slot 획득은 public cancellation predicate를 retry loop에 전달하되 기존
2-인자 `tryLock` ABI는 보존한다. JDBC lock은 기존 `unlock(): Unit` API를 유지하면서 내부
결과를 `RELEASED`/`NOT_HELD`/`FAILED`로 분류하고, elector는 작업 history와
해제 결과를 별도로 로깅한다. R2DBC callback 실패는 원래 DB 오류를 suppressed로
보존하고, 획득 뒤 보상 해제는 5초 budget과 명시적 실패 outcome으로 제한한다.

## 결과

callback·DB 오류·내부/외부 취소가 호출자와 history recorder에 도달하고, 해제
실패가 정상 반납으로 표시되지 않는다. DB-time 연산이 실패한 뒤에는 기존
fail-closed availability 상태를 유지하며 callback 오류와 DB 원인을 함께 남긴다.

## 검증

- R2DBC callback 예외·취소 회귀 테스트와 `ExposedR2dbcGroupLockTest` 전체:
  347 tests, failures/errors/skipped 0
- JDBC 비동기 취소 이력·해제 결과 테스트와 `ExposedJdbcGroupLockTest`,
  `ExposedJdbcLeaderGroupElectionTest` 전체:
  319 tests, failures/errors/skipped 0
- 변경 모듈 Kotlin compile/test compile, `detekt`, `checkBinaryCompatibility`,
  `git diff --check` 통과

## 향후 지침

분산 lock의 callback은 상태 전이의 관측 경계이므로 일반 예외와 취소를
무조건 `runCatching`으로 감싸지 않는다. 모든 실행 모델은 action 취소의
terminal audit와 unlock 결과 분류를 같은 표로 검토하고, 작업 history의
성공 상태와 리소스 해제 상태를 하나의 결과로 합치지 않는다.

## 재발 방지 규칙

- 실패한 가정/판단: availability callback은 부가 알림이므로 `runCatching`으로
  감싸도 안전하다. → 발견 증거 또는 교정: baseline에 callback 회귀 테스트만
  적용하자 69건 중 21건이 예외 미전파로 실패했다. → 수정 결정: DB 연산 경계와
  callback 경계를 분리하고, 획득 뒤 callback 실패는 `NonCancellable` 보상 해제
  후 원래 예외를 전파한다. → 향후 예방 확인: 가용성 callback 변경 시 일반 예외,
  `CancellationException`, 획득 row 정리를 RED/GREEN으로 함께 검증한다.
- 실패한 가정/판단: `CompletableFuture` 취소는 별도 상태이므로 terminal audit을
  생략해도 된다. → 발견 증거 또는 교정: JDBC async baseline은 취소 뒤 history를
  `FAILED`가 아니라 `ACQUIRED`로 남겼고, 반환 future 취소 테스트도 3개 provider
  모두 실패했다. → 수정 결정: sync/async/suspend 모두 action 취소를 terminal
  `FAILED`로 기록하고 외부 취소를 내부 action과 단일 cleanup으로 연결한다. → 향후
  예방 확인: 실행 모델별 취소 matrix에서 내부/외부 취소, status, `finishedAt`,
  `durationMs`, 재선출 가능성을 같은 표로 확인한다.
- 실패한 가정/판단: `unlock(): Unit`과 예외 로그만으로 해제 결과를 충분히
  구분할 수 있다. → 발견 증거 또는 교정: DB 오류를 내부에서 흡수한 뒤 elector가
  정상 반납 로그를 남길 수 있었다. → 수정 결정: 공개 `Unit` 계약은 유지하면서
  내부 결과를 `RELEASED`/`NOT_HELD`/`FAILED`로 분리한다. → 향후 예방 확인:
  history 결과와 resource release 결과를 별도 assertion과 로그 경로로 검증한다.
- 실패한 가정/판단: callback 예외만 전파하면 DB 오류의 관측 계약도 충분히
  보존된다. → 발견 증거 또는 교정: 독립 리뷰에서 callback이 원래 DB 예외와
  무제한 `NonCancellable` 보상 결과를 가릴 수 있음을 확인했다. → 수정 결정: DB
  오류를 먼저 로깅하고 callback 예외의 suppressed 원인으로 보존하며, 보상 해제에
  5초 budget과 `FAILED` marker를 둔다. → 향후 예방 확인: callback failure 테스트는
  전파 예외뿐 아니라 suppressed DB 원인과 보상 실패 outcome도 함께 검증한다.
- 실패한 가정/판단: action future에 `whenComplete` cleanup을 등록하면 public
  result도 cleanup 뒤에 완료된다. → 발견 증거 또는 교정: 독립 아키텍처 검토와
  세 dialect 회귀 테스트에서 cleanup이 멈춘 동안 public result가 이미 완료됐다.
  → 수정 결정: 취소 참조는 원래 action future에 유지하고 pipeline은 cleanup을
  포함한 terminal future를 반환한다. → 향후 예방 확인: 비동기 terminal 테스트는
  cleanup을 latch로 지연해 result, history, unlock의 완료 순서를 검증한다.
- 실패한 가정/판단: public future 취소를 action future에만 연결해도 비동기 작업이
  즉시 멈춘다. → 발견 증거 또는 교정: 점유된 slot을 기다리는 세 dialect 테스트에서
  취소 뒤 single-thread executor의 후속 작업이 3초 안에 실행되지 않았다. → 수정
  결정: async group 경로가 retry loop에 cancellation predicate를 전달하고, 기존
  2-인자 `tryLock`은 그대로 위임해 ABI를 보존한다. → 향후 예방 확인: 취소 matrix에
  action 전 acquisition 대기와 executor 반환 시간을 포함한다.
