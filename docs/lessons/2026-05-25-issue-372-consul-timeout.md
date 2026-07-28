# 문제 372 Consul 요청 시간 초과 대기

## 맥락

Issue #372는 Consul 백엔드에서 하드 코딩된 `10s` 대기를 추적했습니다. HTTP 클라이언트는 이미 `ConsulEndpoint.requestTimeout`를 읽었지만 차단 호출자는 여전히 관련 없는 `CompletableFuture.get(10, TimeUnit.SECONDS)` 예산을 사용했습니다.

## 결정

내부 `ConsulLockClient` 경계를 통해 `requestTimeout`를 노출하고 `getWithinRequestTimeout`에서 차단 대기를 중앙 집중화합니다. 이렇게 하면 차단, 비동기 및 동기 상태 스냅샷 경로가 엔드포인트에 구성된 예산을 사용하게 되면서 공개 API는 변경되지 않은 상태로 유지됩니다.

## 결과

Consul 차단 대기 사이트는 더 이상 `10s`를 하드 코딩하지 않습니다. 위임 테스트는 단일/그룹 상태 스냅샷을 차단하고 일시 중단하기 위해 `CompletableFuture.get`에 전달된 시간 초과를 기록합니다.

## 검증

- `git diff --check`
- `./gradlew :bluetape4k-leader-consul:test --tests '*DelegationTest' --no-daemon` (22통과)
- `./gradlew :bluetape4k-leader-consul:test --tests '*Consul*' --no-daemon` (56통과)
- Claude 코드 검토 아티팩트: `.omx/artifacts/claude-issue-372-consul-timeout-20260525112738.md`(P0=0, P1=0)

## 퓨쳐 가드

백엔드에 HTTP/클라이언트 시간 초과와 향후 대기 차단이 모두 있는 경우 동일한 백엔드 구성에서 둘 다 파생시키거나 의도적으로 다른 안전 여유를 문서화하세요. 명명된 정책 없이 백엔드 정리, 상태 또는 확장 경로에 고정된 `10s` 대기를 두지 마십시오.
