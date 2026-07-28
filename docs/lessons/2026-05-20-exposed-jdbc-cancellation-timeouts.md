# Exposed JDBC 취소 및 시간 초과 예산

## 맥락

Issue #304, #305 및 #306은 동일한 Exposed JDBC 잠금/선택기 표면을 공유했습니다. 즉, `runCatching`를 통해 `CancellationException`를 삼킨 트랜잭션 정리 경로를 차단하고 잠금 획득 재시도 루프가 로컬 시간 초과에 벽시계 시간을 사용했습니다.

## 결정

트랜잭션 지원 최선의 경로 주위에 명시적 `try/catch`를 사용하면 `CancellationException`가 항상 일반 대체 로깅 전에 다시 발생됩니다. 벽시계 `Instant`에 데이터베이스 임대 타임스탬프를 유지하면서 `System.nanoTime`를 기반으로 하는 작은 `MonotonicDeadline` 도우미를 사용하여 로컬 `tryLock` 재시도 예산을 측정합니다.

## 결과

단일 잠금 및 그룹 잠금 재시도 루프는 이제 벽시계 점프를 방지하는 반면, DB 정리/상태 오류는 취소되지 않는 예외에 대한 이전 최선의 동작을 유지합니다.

## 검증

- `./gradlew :bluetape4k-leader-exposed-jdbc:test --tests 'io.bluetape4k.leader.exposed.jdbc.lock.MonotonicDeadlineTest' --no-build-cache --stacktrace`
  - 4 합격
- `./gradlew :bluetape4k-leader-exposed-jdbc:test --no-build-cache --stacktrace`
  - 231 통과

## 향후 지침

일시 중단 인식 또는 취소 감지 Kotlin 경로 주변에는 `runCatching`를 사용하지 마십시오. 분산 잠금의 경우 벽시계 타임스탬프를 사용하여 JVM 전체에서 지속 임대 만료를 비교 가능하게 유지하되 로컬 대기 예산은 단조롭게 유지하세요.
