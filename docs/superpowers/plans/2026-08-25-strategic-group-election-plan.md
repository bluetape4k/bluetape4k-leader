# Strategic Group Election API 구현 계획

## 목표

`#463`의 승인된 설계를 기준으로 후보 레지스트리에서 결정론적으로 상위
N개를 선택하는 blocking/coroutine strategic group API를 추가한다. 기존
single strategic API, 일반 group API, `ElectionStrategy` ABI와 backend별
후보 레지스트리 semantics는 유지한다.

이 API의 `maxLeaders`는 호출이 관찰한 후보 snapshot에 대한 advisory top-N이다.
분산 snapshot이 다르면 전역 동시 실행 상한이 될 수 없으므로 전역 상한이 필요한
작업에는 기존 `LeaderGroupElector`를 사용한다.

## 변경 파일

### leader-core 공개 계약과 전략

- `leader-core/src/main/kotlin/io/bluetape4k/leader/StrategicLeaderGroupElector.kt`
  - 후보 등록/해제/조회/결과 갱신과 blocking `runIfLeader` 계약을 추가한다.
  - `LeaderGroupElectionOptions`의 `maxLeaders`를 strategy에 전달한다.
- `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/StrategicSuspendLeaderGroupElector.kt`
  - 위 계약의 `suspend` 대응 API를 추가한다.
- `leader-core/src/main/kotlin/io/bluetape4k/leader/strategy/GroupElectionStrategy.kt`
  - 기존 `ElectionStrategy`를 변경하지 않고 `(candidates, maxLeaders)` 계약을 추가한다.
- `leader-core/src/main/kotlin/io/bluetape4k/leader/strategy/StrategicGroupElectionResult.kt`
  - ordered `winners`, `eliminations`, `scores`, `EMPTY`를 제공한다.
- `leader-core/src/main/kotlin/io/bluetape4k/leader/strategy/StrategicGroupElectionResultValidation.kt`
  - strategy 결과의 nodeId 중복·교집합·누락·입력 외 후보·N 초과를 검증한다.
- `leader-core/src/main/kotlin/io/bluetape4k/leader/strategy/strategies/FifoGroupElectionStrategy.kt`
  - `registeredAt`와 `nodeId` tie-break로 상위 N개를 선택한다.
- `leader-core/src/main/kotlin/io/bluetape4k/leader/strategy/strategies/ScoredGroupElectionStrategy.kt`
  - 기존 `CandidateScorer`를 한 번씩 적용하고 score/등록 시각/node id 순으로 선택한다.

### Local adapter

- `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalStrategicLeaderGroupElector.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalStrategicSuspendLeaderGroupElector.kt`
  - 기존 Local strategic registry와 lock/mutex를 재사용한다.
  - 현재 elector의 `nodeId`가 관찰된 winner snapshot에 있을 때만 action을 실행한다.
  - backend snapshot 차이로 전역 실행 수가 `maxLeaders`를 넘을 수 있다는 경계를
    KDoc와 README에 명시한다.
  - 성공/실패 결과 갱신과 `CancellationException` 재전파를 기존 구현과 대칭으로 유지한다.

### Redis adapters

- `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicLeaderGroupElector.kt`
- `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicSuspendLeaderGroupElector.kt`
- `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/RedissonStrategicLeaderGroupElector.kt`
- `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/RedissonStrategicSuspendLeaderGroupElector.kt`
  - 기존 candidate registry의 TTL, 직렬화, read/update와 backend별 lock-name/coroutine
    예외 경계를 재사용하되 group 전용 key namespace를 사용한다.
  - 후보 조회 실패는 기존 strategic adapter와 같이 cancellation은 전파하고 그 밖의
    조회 오류는 경고 후 `null`로 skip한다.

### 테스트

- `leader-core/src/test/kotlin/io/bluetape4k/leader/strategy/StrategicGroupElectionStrategyTest.kt`
  - 빈 후보, 후보 수 부족, 정확한 N개, FIFO/scored 결정론, tie-break, score map,
    elimination 분할을 검증한다.
- `leader-core/src/test/kotlin/io/bluetape4k/leader/local/LocalStrategicLeaderGroupElectorTest.kt`
- `leader-core/src/test/kotlin/io/bluetape4k/leader/local/LocalStrategicSuspendLeaderGroupElectorTest.kt`
  - 선택/비선택 실행, null 반환, success/failure 결과 갱신, 예외와 cancellation을 검증한다.
- `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicLeaderGroupElectorTest.kt`
- `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicSuspendLeaderGroupElectorTest.kt`
- `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonStrategicLeaderGroupElectorTest.kt`
- `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonStrategicSuspendLeaderGroupElectorTest.kt`
  - Redis 후보 TTL과 blocking/coroutine adapter 대칭, 여러 winner의 독립 실행을
    Testcontainers 기반으로 검증한다.

### 문서

- `README.md`
- `README.ko.md`
  - API 표에 strategic group blocking/coroutine 타입을 추가한다.
  - single/group/strategic single/strategic group 선택 기준과 후보 TTL/read
    consistency 경계를 설명한다.
  - Local/Lettuce/Redisson 지원 범위와 동일 registry key 혼용 금지를 명시한다.

## 구현 순서와 TDD 체크포인트

### 1. 전략 계약을 먼저 고정한다

1. `StrategicGroupElectionResultTest`를 먼저 작성해 RED를 확인한다.
2. `GroupElectionStrategy`, 결과 모델, FIFO/scored 전략을 구현한다.
3. core 전략 테스트를 실행해 GREEN을 확인한다.
4. `maxLeaders.requireGe(1, "maxLeaders")`와 기존 bluetape4k `require*` helper 사용을
   코드 리뷰로 확인한다.
5. scorer가 `NaN`/무한대를 반환하면 즉시 `IllegalArgumentException`을 반환하도록
   테스트한다.

### 2. public elector 계약을 추가한다

1. blocking/coroutine 인터페이스를 추가하고 기존 single API와 JVM descriptor가
   변하지 않는지 컴파일한다.
2. KDoc은 한국어 기술 문체로 작성하고 기존 `CandidateInfo`/`CandidateResult` 용어를
   그대로 사용한다.

### 3. Local 구현을 추가한다

1. 선택 node의 action 실행과 비선택 node의 null 반환 테스트를 RED로 추가한다.
2. blocking 구현을 기존 `ReentrantLock` 경계에 맞춰 추가한다.
3. coroutine 구현을 기존 `Mutex`와 cancellation 보존 helper에 맞춰 추가한다.
4. 잘못된 custom strategy 결과가 action을 실행하기 전에 거부되는지 검증한다.
5. 결과 갱신과 예외 테스트를 GREEN으로 만든다.

### 4. Lettuce와 Redisson 구현을 추가한다

1. 각 backend의 blocking 테스트를 먼저 추가해 RED를 확인한다.
2. 기존 `LettuceCandidateRegistry`/`LettuceSuspendCandidateRegistry`와
   `RedissonCandidateRegistry`의 TTL·직렬화 경로를 재사용하되 group namespace를
   전달한다.
3. coroutine adapter에서 `CancellationException`을 삼키지 않도록 테스트한다.
4. Redis Testcontainers targeted test를 순차 실행한다.

### 5. 문서와 회귀 검증을 완료한다

1. README 두 locale을 같은 API 표와 선택 규칙으로 갱신한다.
2. 기존 strategic single/group 테스트를 함께 실행한다.
3. `git diff --check`, `detekt`, core/Redis targeted test, 필요한 모듈 build를
   순서대로 실행한다.
4. 변경된 public symbol, ABI, backend 지원 matrix, 문서 link를 다시 읽는다.

## 검증 명령

아래 Gradle 명령은 저장소의 context-mode redirect 규칙에 따라 실행한다.

```bash
./gradlew :bluetape4k-leader-core:test \
  --tests 'io.bluetape4k.leader.strategy.StrategicGroupElectionStrategyTest' \
  --tests 'io.bluetape4k.leader.local.LocalStrategicLeaderGroupElectorTest' \
  --tests 'io.bluetape4k.leader.local.LocalStrategicSuspendLeaderGroupElectorTest' \
  --no-configuration-cache --max-workers=1
./gradlew :bluetape4k-leader-redis-lettuce:test \
  --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicLeaderGroupElectorTest' \
  --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicSuspendLeaderGroupElectorTest' \
  --no-configuration-cache --max-workers=1
./gradlew :bluetape4k-leader-redis-redisson:test \
  --tests 'io.bluetape4k.leader.redisson.RedissonStrategicLeaderGroupElectorTest' \
  --tests 'io.bluetape4k.leader.redisson.RedissonStrategicSuspendLeaderGroupElectorTest' \
  --no-configuration-cache --max-workers=1
./gradlew detekt --no-configuration-cache --max-workers=1
```

Docker/Testcontainers 검증이 호스트에서 실행되지 않으면 active Colima와
Docker context를 먼저 확인하고, 실패 원인과 검증 범위를 DoD에 기록한다.

## 위험과 대응

- `winners`의 순서는 선출 우선순위일 뿐 실행 순서가 아니다. 문서와 테스트에서
  이 구분을 유지한다.
- 각 backend는 후보 조회 후 action을 시작하므로 atomic claim/fencing을 제공하지
  않는다. 이 경계를 구현에 숨기지 않고 KDoc/README에 기록한다.
- Redis 조회 오류 처리에서 cancellation을 일반 오류로 바꾸지 않는다.
- 기존 `ElectionStrategy`와 public constructor를 수정하지 않아 ABI 위험을 줄인다.
- 새로운 의존성은 추가하지 않는다.
- strategic single/group Redis key namespace를 섞지 않는다.
