# Lessons Learned — Issue #177 aspect identity shadowing

## 배경

`LeaderElectionAspect`와 `LeaderGroupElectionAspect`의 sync FAIL_OPEN 경로에서
reentrant short-circuit용 `LockIdentity`를 이미 계산한 뒤 같은 이름의 `identity`를
다시 선언했다. suspend/Mono FAIL_OPEN 경로도 contention/backend-error branch마다
`identity` 지역 변수를 반복 선언해 흐름을 읽기 어렵게 만들었다.

## 결정

각 advice branch 안에서 `resolvedIdentity` 캐시와 `resolveIdentity(...)` helper를 두고
FAIL_OPEN sentinel 생성 경로가 같은 `LockIdentity` 계산 결과를 재사용하게 했다.

sync branch는 reentrant 비교를 위해 먼저 계산한 `identity`를 contention FAIL_OPEN에도
그대로 사용한다. backend-error FAIL_OPEN은 lock name이 resolve되기 전에 실패할 수
있으므로, 캐시가 없을 때만 `effectiveName`으로 fallback 계산한다.

## 결과

- `meta.resolveLockIdentity(...)`를 직접 `val identity`로 반복 선언하는 패턴 제거.
- reentrant 비교와 FAIL_OPEN sentinel 생성의 identity 의미를 한 branch 안에서 통일.
- touched main source의 불필요한 `Continuation` cast도 제거해 `compileKotlin` 경고를 줄임.
- public API나 runtime behavior 변경 없이 readability issue만 해결.

## 검증

- `rg "val identity = meta\\.resolveLockIdentity"` 결과 0건.
- `rg "innerCont as Continuation<Any\\?>"` 결과 0건.
- `./gradlew :leader-spring-boot:compileKotlin --no-daemon` 성공.
- `./gradlew :leader-spring-boot:test --tests "*LeaderElectionAspect*Test" --tests "*LeaderGroupElectionAspect*Test" --no-daemon` 성공, 90 tests passing.

## 다음 작업자가 주의할 점

FAIL_OPEN 경로를 추가할 때 새 `val identity = meta.resolveLockIdentity(...)`를 branch
안에 다시 만들지 말고, branch-local `resolveIdentity(...)` helper를 재사용한다.
