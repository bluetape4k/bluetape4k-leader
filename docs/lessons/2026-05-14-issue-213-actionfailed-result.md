# 문제 213 ActionFailed 결과 계약

## 맥락

`runIfLeaderResult`는 간단한 `runIfLeader` 계약을 유지하면서 작업 실패에 대한 명확한 결과가 필요했습니다.

## 결정

리더십을 획득하고 사용자 작업이 시작된 후 오류에 대해 `LeaderRunResult.ActionFailed(cause)`를 사용합니다. `runIfLeader`를 변경하지 않고 유지: 경합이 `null`를 반환하고 작업 실패가 발생합니다.

결과적으로 취소를 나타내지 마세요. 블로킹 및 suspend 결과 API는 `CancellationException`를 다시 발생시켜야 합니다. 비동기 및 가상 스레드 결과 API는 `ActionFailed`를 반환하는 대신 예외적으로 완료되어야 합니다.

## 결과

KDoc는 이제 `Elected`, `Skipped` 및 `ActionFailed`를 문서화합니다. 코어 동기화, 일시 중단, 비동기, 가상 스레드, Lettuce 및 Redisson 결과 API는 리더십을 획득한 후 작업 예외에 대해 `ActionFailed`를 반환합니다. Spring AOP는 기존 조언 동작을 유지하기 위해 `ActionFailed.cause`를 다시 발생시킵니다.

Claude 검토에서는 PR 이전에 세 가지 P1 격차를 발견했습니다. Redis 백엔드 결과 테스트가 누락되었고, `InterruptedException`로 래핑된 API를 차단했으며, 비동기/가상 스레드 취소 문서에서 `CompletableFuture.handle`가 보장하지 않는 `isCancelled()` 의미 체계를 암시했습니다. 구현은 이제 인터럽트 플래그를 복원한 후 `InterruptedException`를 다시 발생시키고, 문서에서는 비동기/가상 취소에 대한 예외 완료를 설명하고, Lettuce와 Redisson 동기화/일시 중지 단일/그룹 테스트는 `ActionFailed` 및 `CancellationException`를 다룹니다.

## 검증

- `./gradlew :leader-core:test --no-daemon`는 Redis 검토 후속 조치 전에 624개의 테스트를 통과했습니다. 중단 회귀를 추가한 후 대상 후속 실행은 지나치게 엄격한 Lettuce suspend 예외 ID 어설션에서 실패하기 전에 626개의 코어 테스트를 성공적으로 실행했습니다.
- `./gradlew :leader-core:compileKotlin :leader-redis-lettuce:compileKotlin :leader-redis-redisson:compileKotlin :leader-spring-boot:compileKotlin --no-daemon`가 통과되었습니다. Spring AspectJ는 기존 `unresolvableMember`/`adviceDidNotMatch` 경고를 내보냈습니다.
- `./gradlew :leader-redis-lettuce:test :leader-redis-redisson:test --tests io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorTest --tests io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElectorTest --tests io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorTest --tests io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElectorTest --no-daemon` 통과: Lettuce 197 테스트, Redisson 22 테스트.
- `./gradlew :leader-core:test :leader-redis-lettuce:test :leader-redis-redisson:test --tests io.bluetape4k.leader.LeaderRunResultTest --tests io.bluetape4k.leader.lettuce.LettuceLeaderElectionTest --tests io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorTest --tests io.bluetape4k.leader.lettuce.LettuceLeaderGroupElectionTest --tests io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElectorTest --tests io.bluetape4k.leader.redisson.RedissonLeaderElectionTest --tests io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorTest --tests io.bluetape4k.leader.redisson.RedissonLeaderGroupElectionTest --tests io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElectorTest --no-daemon` 통과: Redisson 64 테스트; 핵심 및 Lettuce 작업은 사전 검증을 통해 최신 상태였습니다.
- IntelliJ 빌드 진단에서 오류가 보고되지 않았습니다.
- Repo 인벤토리에서 24개의 생산 결과 API 파일이 발견되었으며 `ActionFailed` 처리가 누락된 파일이 0개 있습니다.
- Repo 검색에서 오래된 대체 결과 이름을 찾지 못했습니다.
- Claude CLI 검토는 5분 재시도에 성공했고 P1 항목이 해결되었습니다. 아티팩트: `.omx/artifacts/claude-actionfailed-result-20260514183409.md`.

## 향후 지침

새 백엔드에 대한 결과 API를 추가할 때 선택, 건너뛰기, 작업 실패라는 세 가지 값 결과만 구별합니다. 별도의 백엔드 결과 계약이 명시적으로 설계되지 않는 한 백엔드/획득 실패를 예외로 유지합니다. 항상 작업 실패와 별도로 `CancellationException` 및 `InterruptedException`를 테스트하세요. 광범위한 `catch (Exception)` 블록은 취소/중단을 작업 실패로 자동 변환할 수 있습니다. `CompletableFuture` API의 경우 코드가 명시적으로 `cancel()`를 호출하지 않는 한 취소를 예외 완료로 문서화합니다.
