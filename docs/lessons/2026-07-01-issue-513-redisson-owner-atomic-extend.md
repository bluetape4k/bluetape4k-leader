# 문제 513 - Redisson 소유자 원자 확장

## 맥락

Issue #513은 Redisson 단일 잠금 확장의 경합을 노출시켰습니다. 이전 구현에서는 소유자 상태를 로컬로 검증하고 별도의 Redis 명령에서 키 TTL을 갱신했습니다.

## 결정

하나의 Redis Lua 스크립트에서 소유자 검증 및 TTL 갱신을 수행하는 공유 내부 도우미를 사용하세요. 도우미는 해결된 Redisson 4.4.0 아티팩트의 Redisson 자체 `getLockName(long)` 소유자 필드와 `getRawName()` 원시 Redis 키 이름을 반영합니다.

## 테스트 가드

MockK를 클래스 수준 필드로 두 배로 유지하고 `clearMocks(...)`를 사용하여 `@BeforeEach`에서 재설정합니다. 검토 증거가 개체가 일반 고정 장치 또는 실제 테스트 개체가 될 수 없는 이유를 명시적으로 정당화하지 않는 한 새로운 bluetape4k 테스트에 메서드 로컬 `mockk(...)` 또는 `spyk(...)`를 도입하지 마십시오.

## 결과

이제 동기화 및 일시중단 대리자가 동일한 소유자 원자 확장 경로를 공유합니다. 회귀 분석에서는 오래된 소유자 결과가 `WrongThread` 또는 `NotHeld`에 매핑되고 전체 `leader-redis-redisson` 테스트가 순차적으로 통과되는지 검증합니다.

## 검증

- `RedissonOwnerAtomicExtendDelegateTest`: `BUILD SUCCESSFUL in 13s`
- Redisson 확장 계약 테스트: `BUILD SUCCESSFUL in 4s`
- `:bluetape4k-leader-redis-redisson:test --no-parallel`: `BUILD SUCCESSFUL in 19s`
- `git diff --check`: 통과
