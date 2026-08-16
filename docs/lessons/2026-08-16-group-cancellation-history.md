# 배운 교훈 - 그룹 선출 취소와 terminal history

## 맥락

Issue #692의 Exposed JDBC/R2DBC 그룹 선출기는 슬롯을 획득한 뒤 audit history를
`ACQUIRED`로 기록합니다. 이후 action이 취소되면 취소 예외는 호출자에게
재전파하고, 슬롯과 로컬 캐시는 반드시 정리해야 합니다. 이미 만들어진 history도
실행 중인 작업으로 오인되지 않도록 terminal 상태로 닫아야 합니다.

## 원인

두 그룹 선출 경로는 일반 예외일 때만 실패 상태 변수를 설정했습니다.
`CancellationException` 경로는 예외를 곧바로 재전파했기 때문에 cleanup 자체는
실행됐지만, `finally`의 history 분기에는 성공도 실패도 전달되지 않았습니다. 그
결과 unlock과 cache decrement가 끝난 뒤에도 audit row는 `ACQUIRED`이고
`finishedAt`과 `durationMs`는 비어 있었습니다.

## 결정

취소 예외를 일반 오류로 변환하거나 삼키지 않습니다. 대신 catch 지점에서 기존
실패 상태 변수에 취소 사실을 남긴 뒤 같은 예외를 재전파합니다. cleanup은 기존
순서대로 history를 `FAILED`로 마감하고, 이어서 슬롯을 해제하고 캐시를 줄입니다.

R2DBC는 이 전체 정리를 `NonCancellable` context에서 수행합니다. 따라서 action의
Job이 취소된 상태에서도 history와 unlock이 중단되지 않습니다. setup 단계에서
history ID를 만들기 전에 취소된 경우에는 `recordFailed(null, ...)`이 아무 작업도
하지 않는 기존 null-safe 계약을 유지합니다.

이 결정은 Exposed 그룹의 blocking/suspend action 경로에 한정합니다. single
elector와 `CompletableFuture` cancellation은 별도 계약이므로, 같은 상태 전이를
근거 없이 확장하지 않습니다.

## 결과

그룹 action 취소는 호출자에게 계속 전달됩니다. 동시에 JDBC/R2DBC history는
`FAILED`, non-null `finishedAt`, 0 이상 `durationMs`를 가지며, active slot은 0으로
복구되어 같은 lock을 다시 획득할 수 있습니다. 공개 API와 정상 contention의
skip/null 계약은 변경하지 않았습니다.

## 검증

- RED: JDBC/R2DBC H2에서 취소 후 history가 `ACQUIRED`로 남아 각각 실패
- GREEN: JDBC/R2DBC H2, PostgreSQL, MySQL targeted 테스트 각각 3개 통과
- JDBC 모듈 테스트 290개와 Detekt 통과
- R2DBC 모듈 테스트 312개와 Detekt 통과
- setup cancellation에서 history row 0개, active slot 0을 확인
- `git diff --check` 통과

## 향후 지침

취소를 재전파한다는 이유로 audit 종료 처리를 건너뛰지 마세요. 자원 획득 뒤 생성된
history ID가 있다면 취소된 context와 분리된 cleanup에서 terminal 상태와 종료
시각을 먼저 기록하고 자원을 반환해야 합니다. 회귀 테스트는 예외 전파만 보지 말고
history 상태, 종료 메타데이터, unlock/cache 정리, 후속 재획득을 함께 검증해야
합니다.
