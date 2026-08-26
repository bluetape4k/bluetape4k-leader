---
title: "Ktor management action route"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor management action route

> Issue #532를 위한 unreleased 초안입니다. 이 route는 `sourceReleaseCommit`보다 새 API이므로 pinned `0.5.0` manual은 수정하지 않습니다. Route가 포함된 commit을 release manifest가 가리킨 뒤에만 이 초안을 승격하세요.

## Route 소유권

Ktor는 POST release action을 위한 `Route.leaderElectionManagementActionRoute`
extension을 제공합니다. Plugin은 이를 자동 설치하지 않습니다. 애플리케이션이
`authenticate("management")` block 안에서 extension을 설치하고, 애플리케이션 소유
`SuspendLeaderManagementActionRegistry`를 전달해야 합니다.

```kotlin
val actionRegistry = SuspendLeaderManagementActionRegistry()

install(LeaderElectionPlugin) {
    leaderElection = redissonElector
    managementActionRouteEnabled = true
    managementActionRegistry = actionRegistry
}

routing {
    authenticate("management") {
        leaderElectionManagementActionRoute(
            registry = actionRegistry,
            authorize = { principal<UserIdPrincipal>() != null },
        )
    }
}
```

`managementActionRouteEnabled` 기본값은 `false`입니다. Registry 없이 활성화하면
plugin 설치 단계에서 실패합니다. 활성화하지 않은 상태에서 extension만 설치하면
fail-closed 404를 반환합니다. Plugin은 인증, 권한, 외부 coroutine scope를 추가하지
않습니다.

## Canonical 및 custom path

Canonical path는 다음과 같습니다.

```http
POST /management/leaderElection/actions/{lockName}
```

`managementRoutePath`를 바꿀 때는 `/internal/leader-status/actions`처럼 대응하는
action path를 명시하세요. 기존 read-only GET route와 write route를 분리합니다.
설치 시 path를 한 번 정규화하며 빈 path는 허용하지 않습니다.

Selector는 공통 ASCII grammar
`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`를 사용하고 registry mutation 전에 검증합니다.
Literal 또는 encoded slash는 selector 경계를 넘지 못해 404가 됩니다. `%`, `*`, `..`,
control text, non-ASCII 값처럼 매칭된 hostile selector는 registry를 호출하지 않고
400 `INVALID_LOCK_NAME`을 반환합니다.

## 인증과 응답 경계

Unauthenticated 401과 principal 실패는 Ktor provider가 소유합니다. 다음 권한 판단은
애플리케이션 `authorize` callback이 담당합니다.

- `false`이면 403 `AUTHORIZATION_DENIED`를 반환합니다.
- 일반 callback 예외이면 500 `AUTHORIZATION_FAILED`를 반환합니다.
- Cancellation은 재전파하고 fatal `Error`는 response로 바꾸지 않습니다.

두 인증 실패에서는 registry를 호출하지 않으며 credential, actor, lock name, backend
payload, token, exception 원문을 response에 복사하지 않습니다. Action response는
framework-neutral core mapping을 사용하고 다음 세 key만 포함합니다.

```json
{"action":"RELEASE","outcome":"RELEASED","mutationAttempted":true}
```

모든 outcome의 `retryAllowed`는 `false`입니다. Worker가 terminalize되기 전 timeout을
재시도하지 말고 `RELEASE_UNCONFIRMED`와 `RELEASE_FAILED`를 성공으로 처리하지 마세요.

## Graceful shutdown

Shutdown 순서는 애플리케이션이 소유합니다. Engine을 다른 방식으로 멈추기 전에
`ApplicationEngine` helper를 호출하세요.

```kotlin
suspend fun shutdown(
    engine: ApplicationEngine,
    actionRegistry: SuspendLeaderManagementActionRegistry,
) {
    engine.stopLeaderManagementGracefully(
        actionRegistry,
        gracePeriodMillis = 1_000,
        timeoutMillis = 5_000,
    )
}
```

Helper는 먼저 `closeAndDrain()`을 호출한 뒤 `stopSuspend`를 실행합니다. Drain timeout은
sanitized warning을 남기고 `false`를 반환하지만 engine stop은 계속합니다. Application
scope를 취소하거나 임의의 lease를 해제하지 않습니다. Registry worker scope와
registration lifecycle은 bounded하며 애플리케이션이 소유합니다.

## 승격 조건

승격 전에 explicit-install 404, auth 401/403/500, selector 404/400, allow-list JSON,
cancellation 전파, shutdown ordering, exact release commit의 전체 Ktor test를 확인하세요.
이 검증과 release manifest 업데이트가 끝날 때까지 pinned manual에는 이 초안을 넣지
않습니다.
