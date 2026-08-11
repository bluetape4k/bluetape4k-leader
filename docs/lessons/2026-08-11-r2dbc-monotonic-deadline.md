# 배운 교훈 - R2DBC lock wait budget의 monotonic deadline

## 맥락

Issue #669에서 Exposed R2DBC 단일·그룹 lock의 retry deadline이
`System.currentTimeMillis()`에 의존하고 있었습니다. NTP 보정이나 운영자의
wall-clock 변경이 wait budget을 앞당기거나 늘릴 수 있어, 같은 acquisition
계약을 사용하는 JDBC와 suspend 경로의 시간 의미가 달라지는 결함이었습니다.

## 원인

두 `tryLock` 루프가 시작 deadline과 각 반복의 remaining budget을 모두
wall-clock에서 계산했습니다. DB lease의 `Clock`/`CURRENT_TIMESTAMP`와 retry
budget은 서로 다른 시간 경계인데, R2DBC 루프에는 JDBC의 monotonic helper가
없었습니다.

## 결정

R2DBC와 JDBC 모듈의 `internal.MonotonicDeadline`을 `System.nanoTime()` ticker
기반 elapsed 계산으로 통일하고 단일·그룹 retry loop가 각 모듈의 같은 helper를
공유하도록 했습니다. 음수/zero wait는 즉시 만료시키고, sub-millisecond
budget은 마지막 1ms sleep 창으로 보존하며, `start + timeout` 절대 deadline을
만들지 않아 임의 origin과 ticker wrap-around에서도 예산을 보존합니다. DB lease의
`Clock`과 server-time query 경로는 그대로 유지하고, helper는 각 모듈의
`internal` 패키지에 두어 ABI 필터 경계도 명확히 했습니다.

## 결과

wall-clock 전진/후퇴와 무관하게 monotonic ticker가 소비한 시간만 wait budget에
반영됩니다. 기존 skip-on-contention/null, cancellation 재전파, DB-time query
budget 계약은 변경하지 않았습니다.

## 검증

- RED: `ExposedR2dbcMonotonicDeadlineContractTest`가 기존 두 루프의
  `System.currentTimeMillis()` 사용을 발견해 0 passing/1 failing
- GREEN: R2DBC monotonic helper·경계·소스 계약 6개 테스트 통과
- R2DBC 모듈: 306개 테스트 통과, `BUILD SUCCESSFUL`
- JDBC 모듈: 287개 테스트 통과, `BUILD SUCCESSFUL`
- JDBC/R2DBC monotonic helper 경계 테스트 및 단일·그룹 lock/query-budget parity 통과
- `git diff --check` 통과
- Detekt는 저장소의 기존 JVM target 25 설정이 현재 Detekt 지원 범위
  (최대 22)를 벗어나 실행 전 실패했으며, 이는 Issue #677 범위로 남겼습니다.

## 향후 지침

모든 blocking/suspend retry/polling loop는 wall-clock deadline을 직접 계산하지
말고 elapsed subtraction을 사용하는 monotonic ticker helper를 사용하세요. DB
lease의 authoritative server time과 호출자의 wait budget은 별도 primitive으로
유지하고, helper 및 실제 lock 경합 테스트에는 zero/negative,
sub-millisecond, arbitrary-origin, wrap-around 경계를 함께 고정하세요.
