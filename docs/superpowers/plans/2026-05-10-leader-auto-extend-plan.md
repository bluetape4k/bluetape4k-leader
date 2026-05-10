# Leader autoExtend 구현 계획

## Task 1. Core option and tests

- complexity: medium
- files: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt`, `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderElectionOptionsTest.kt`
- work: `autoExtend: Boolean = false` 추가, KDoc/serialization 유지, 기본/커스텀 option test 갱신
- verification: `./gradlew :leader-core:test --tests "*LeaderElectionOptionsTest"`
- rollback point: option 추가 전으로 revert 가능

## Task 2. Annotation and aspect mapping

- complexity: medium
- files: `LeaderElection.kt`, `LeaderElectionAnnotationTest.kt`, `LeaderElectionAspect.kt`, `LeaderElectionAspectTest.kt`
- work: `@LeaderElection(autoExtend=false)` 추가, aspect metadata에서 `LeaderElectionOptions.autoExtend` 전달, mapping test 추가
- verification: `./gradlew :leader-core:test --tests "*LeaderElectionAnnotationTest" && ./gradlew :leader-spring-boot:test --tests "*LeaderElectionAspectTest"`
- docs impact: public annotation KDoc/README 갱신 필요

## Task 3. Common watchdog helper

- complexity: high
- files: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt`, focused unit test
- work: daemon scheduled executor 기반 `start(enabled, leaseTime, extend)` helper 추가. close 시 future cancel. 실패 시 반복 중단.
- verification: `./gradlew :leader-core:test --tests "*LeaderLeaseAutoExtenderTest"`
- risk: leaked scheduled task. 테스트에서 close 후 추가 호출이 없는지 검증한다.

## Task 4. Local backend support

- complexity: medium
- files: `AbstractLocalLeaderElector.kt`, `LocalSuspendLeaderElector.kt`, local tests
- work: action 실행 중 state leaseUntil을 갱신하도록 autoExtend helper 연결. 실제 mutual exclusion은 기존 JVM lock이 담당.
- verification: `./gradlew :leader-core:test --tests "*LocalLeaderElectionTest" --tests "*LocalSuspendLeaderElectorTest"`

## Task 5. Lettuce backend support

- complexity: high
- files: `LettuceLock.kt`, `LettuceSuspendLock.kt`, `LettuceLeaderElector.kt`, `LettuceSuspendLeaderElector.kt`, Lettuce tests
- work: token-conditional extend Lua 추가, sync/async/suspend elector watchdog lifecycle 연결
- verification: `./gradlew :leader-redis-lettuce:test --tests "*LettuceLeaderElectionTest" --tests "*LettuceSuspendLeaderElectorTest"`
- risk: Redis RTT와 짧은 TTL. 테스트는 200ms+ lease로 둔다.

## Task 6. Mongo backend support

- complexity: high
- files: `MongoLock.kt`, `MongoSuspendLock.kt`, `MongoLeaderElector.kt`, `MongoSuspendLeaderElector.kt`, Mongo tests
- work: token-conditional `expireAt` update extension 추가, sync/async/suspend elector watchdog lifecycle 연결
- verification: `./gradlew :leader-mongodb:test --tests "*MongoLeaderElectionTest" --tests "*MongoSuspendLeaderElectorTest"`
- risk: TTL monitor delete timing은 비결정적이므로 takeover가 아니라 contender skip으로 검증한다.

## Task 7. Redisson backend support

- complexity: high
- files: `RedissonLeaderElector.kt`, `RedissonSuspendLeaderElector.kt`, Redisson tests
- work: `autoExtend=true`이면 `tryLock(waitTime, TimeUnit)` / async overload로 Redisson watchdog 사용. `minLeaseTime > 0` fail-fast.
- verification: `./gradlew :leader-redis-redisson:test --tests "*RedissonLeaderElectionTest" --tests "*RedissonSuspendLeaderElectorTest"`
- risk: Redisson thread/lockId ownership. 기존 sync/async/suspend ownership path를 유지한다.

## Task 8. Documentation and final verification

- complexity: low
- files: `README.md`, `README.ko.md`, relevant module READMEs if compact update is needed
- work: single-leader `autoExtend` usage and backend support matrix 추가
- verification: targeted module tests, `./gradlew :leader-core:build :leader-spring-boot:test :leader-redis-lettuce:test :leader-mongodb:test :leader-redis-redisson:test`
- not in scope: Exposed/Hazelcast/ZooKeeper autoExtend behavior except compile fixes

## Review notes

- Spec review local perspectives:
  - developer: option propagation and backend ownership are aligned with current factory model.
  - security: extension must be token-conditional; no lock revival without ownership.
  - ops: watchdog failure is logged but cannot cancel running action with current API.
  - caller: default false preserves existing fixed TTL behavior.
- Plan review local perspectives:
  - implementer: tasks are ordered by shared API first, backend support second.
  - test engineer: each backend has a focused long-running contention test.
  - architect: no AOP-only timer; backend lifecycle owns renewal.
  - delivery: group autoExtend is explicitly excluded.
- Claude advisor: attempted with `claude-opus-4-7` at `.omx/artifacts/claude-leader-auto-extend-spec-plan-20260510-114935.md`; CLI produced no output for ~2 minutes and was terminated. Proceeding with local multi-perspective review to avoid blocking implementation.
