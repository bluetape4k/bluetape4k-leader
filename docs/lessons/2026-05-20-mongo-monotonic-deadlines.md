# Mongo tryLock 단조로운 마감일

## 맥락

Issue #308에서는 Mongo 잠금 `tryLock` 획득 루프가 벽시계 시간을 사용하여 재시도 기한을 결정한다고 보고했습니다. 시스템 클럭 점프로 인해 로컬 대기 예산이 예기치 않게 단축되거나 확장될 수 있습니다.

## 결정

`tryLock` 재시도 예산을 차단하고 일시 중단하려면 작은 Mongo-local `MonotonicDeadline` 도우미를 사용하세요. `expireAt`는 MongoDB에 의해 클라이언트 간에 지속되고 비교되므로 MongoDB 임대 만료를 벽시계 `Date` 값으로 유지합니다.

무작위 재시도 지연을 나머지 단순 예산으로 고정하고 도우미 경계에서 양수가 아닌 최대 지연 값을 거부합니다.

## 결과

`MongoLock.tryLock` 및 `MongoSuspendLock.tryLock`는 이제 기존 임대 지속성 의미를 유지하면서 로컬 획득 시간 초과 계산에 `System.nanoTime()`를 사용합니다.

## 검증

- `./gradlew :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-mongodb:compileTestKotlin --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-mongodb:test --tests 'io.bluetape4k.leader.mongodb.internal.MonotonicDeadlineTest' --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-mongodb:test --no-build-cache --stacktrace`
- 클로드 리뷰: SHIP; P2 도우미 전제 조건 격차가 커밋 전에 수정되었습니다.

## 향후 지침

로컬 클라이언트 대기 예산에 대해서만 단조로운 JVM 시간을 사용하십시오. 스토리지 계약이 변경되지 않는 한 지속적인 MongoDB 임대 타임스탬프를 벽시계 기반으로 유지합니다.
