# 문제 513 검토 - Redisson 소유자 원자 확장

## 범위

- 문제: #513, `P1: Redisson lock extension is not owner-atomic`
- 모듈: `leader-redis-redisson`
- 대상 동작: 검증 후 만료 갱신을 Redis 측 소유자 검증 및 TTL 업데이트로 대체합니다.

## 소스 증거

- 해결된 Redisson 런타임은 `org.redisson:redisson:4.4.0`입니다.
- 로컬 Redisson 4.4.0 아티팩트에 대한 `javap`는 다음을 검증합니다.
  - `org.redisson.RedissonBaseLock.getLockName(long)`가 존재하며 Redisson가 잠금 소유권에 사용하는 소유자 해시 필드 형태를 반환합니다.
  - `org.redisson.RedissonObject.getRawName()`가 존재하며 원시 Redis 키 이름을 제공합니다.
- 구현에서는 `redissonClient.getScript(StringCodec.INSTANCE)`를 사용하므로 스크립트 인수는 기본 개체 코덱 대신 Redisson 잠금 소유자 필드처럼 인코딩됩니다.

## 코드 패턴 감사

- MockK 테스트 더블은 `RedissonOwnerAtomicExtendDelegateTest`의 클래스 수준 필드입니다.
- `@BeforeEach`는 `clearMocks(scriptClient, keys, script, scriptResult)`를 사용하여 클래스 수준 모의를 재설정합니다.
- 새로운 테스트에는 메서드 로컬 `mockk(...)` 또는 `spyk(...)`가 남아 있지 않습니다.
- 일시 중단 회귀 테스트에서는 실제 Redisson/Testcontainers 지원 개체를 실행하므로 `runTest`가 아닌 `runSuspendIO`를 사용합니다.
- 어설션은 `bluetape4k-assertions`를 사용합니다.
- 임시 스레드, 실행자, 절전, 코루틴 스트레스 루프, `!!`, JUnit 어설션 API 또는 `kotlin.test` 어설션 API가 도입되지 않았습니다.

## 검증

- 패턴 파악:
  - 나머지 `mockk(...)` 일치 항목은 클래스 수준 필드뿐입니다.
  - 나머지 `runCatching` 일치 항목은 실행 가능한 코드가 아닌 기존 KDoc 경고입니다.
- `./gradlew :bluetape4k-leader-redis-redisson:test --tests '*RedissonOwnerAtomicExtendDelegateTest' --no-parallel`
  - `BUILD SUCCESSFUL in 13s`
- `./gradlew :bluetape4k-leader-redis-redisson:test --tests '*RedissonExtendDelegateReferenceTest' --tests '*RedissonLockExtenderContractTest' --tests '*RedissonSuspendLockExtenderContractTest' --no-parallel`
  - `BUILD SUCCESSFUL in 4s`
- `./gradlew :bluetape4k-leader-redis-redisson:test --no-parallel`
  - `BUILD SUCCESSFUL in 19s`
- `git diff --check`
  - 통과

## 메모

- 회귀 테스트는 스트레스 루프를 추가하는 대신 `RScript` 반환 값을 통해 경주를 결정론적으로 시뮬레이션합니다. 이는 하나의 Redis Lua 작업으로 경합이 제거되고 기존 계약 테스트가 모듈의 Redisson 테스트 인프라를 통해 확장기 동기화/일시 중단 동작을 다루기 때문에 문제에 적합합니다.
