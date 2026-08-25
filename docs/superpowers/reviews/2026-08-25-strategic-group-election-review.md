# #463 전략적 그룹 선출 API 리뷰

## DoD Status

- 상태: `APPROVE` — P0 0건, P1 0건
- 범위: #463의 core blocking/coroutine 계약, Local adapter, Lettuce/Redisson adapter, 기본 전략, TTL·namespace·ABI 회귀, README와 설계/실행 계획
- 기준 브랜치: `develop`
- 작업 브랜치: `design/epic-strategic-01-api`

## 리뷰 결과

### 계약과 API

- `StrategicLeaderGroupElector`와 `StrategicSuspendLeaderGroupElector`는 기존 `LeaderGroupElector`의 전역 slot claim과 분리된 후보 기준 top-N 계약을 제공한다.
- `maxLeaders`는 직접 `Int`로 받고, 관찰한 후보 기준 목록에서 선택할 최대 수이다. 전역 분산 동시 실행 상한은 기존 `LeaderGroupElector` 계약으로 명확히 분리했다.
- `GroupElectionStrategy.electValidated`를 core의 공통 검증 경계로 두어 winner/elimination partition, 후보 membership, 중복, score key, `maxLeaders` 불변식을 Local·Lettuce·Redisson이 공유한다.
- `ScoredGroupElectionStrategy`는 bluetape4k-core의 `Double.requireFinite`를 사용해 NaN/무한 점수를 fail-closed로 거부한다.

### 아키텍처와 backend 경계

- Architect 재검토: `APPROVE`, P0/P1 0건.
- Lettuce strategic group 후보 key는 `leader:strategy:group-candidates:lettuce:v1`, Redisson은 `leader:strategy:group-candidates:redisson:v1`로 backend/schema 충돌을 차단한다.
- 기존 single candidate prefix와 registry의 one-argument constructor descriptor는 유지해 ABI 영향을 제한했다.
- Lettuce index set에는 TTL을 적용하지 않고 candidate key에만 TTL을 적용한다. 따라서 유한 TTL 후보가 만료되어도 같은 lock의 영구 후보가 가려지지 않는다.
- Redisson 결과 갱신은 남은 TTL을 읽어 동일한 TTL로 다시 저장하므로 성공/실패 카운트 갱신이 후보 만료 정책을 바꾸지 않는다.

### 독립 검토

- 초기 spec review: `ACCEPT`, P0 0건/P1 0건. 지적된 P2 4건(공통 검증 경계, 옵션 KDoc/README, 테스트·ABI 명령, `snapshot` 용어)을 모두 반영했다.
- 독립 architecture review: 최초 P1 3건(옵션 의미, Redis namespace, validator 중복)을 수선한 뒤 `APPROVE`, P0/P1 0건.
- 독립 code review: `ACCEPT/APPROVE`, P0/P1 0건. advisory top-N, custom result validation, cancellation 재전파, mixed TTL, ABI/namespace 경계를 확인했다.

## 검증 증거

| 검증 | 결과 |
| --- | --- |
| Core strategic/local 회귀 | 49 passing, `BUILD SUCCESSFUL` |
| Lettuce strategic single/group blocking/coroutine | 51 passing, `BUILD SUCCESSFUL` |
| Redisson strategic single/group blocking/coroutine | 41 passing, `BUILD SUCCESSFUL` |
| 기존 Local group 회귀 | 23 passing, `BUILD SUCCESSFUL` |
| `detekt --no-configuration-cache --max-workers=1` | `BUILD SUCCESSFUL` |
| core/Lettuce/Redisson module build (`-x test`) | `BUILD SUCCESSFUL` |
| `checkBinaryCompatibility` (`ABI_BASE_VERSION=0.5.0`, `ABI_CURRENT_VERSION=1.0.0`) | `unknown=0`, unclassified incompatibility 없음, `BUILD SUCCESSFUL` |
| `git diff --check` | 통과 |
| `audit-korean-terms.mjs README.ko.md` | findings=0 |
| Colima/Testcontainers preflight | Colima healthy, Docker context/info 정상 |

Redis 검증은 macOS Colima의 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`와 현재 Docker socket을 사용했다. LSP diagnostics 도구는 제공되지 않아 Gradle compile/build, detekt, ABI 검증으로 대체했다.

## 비차단 WATCH

1. Local registry 등록과 선출 snapshot 사이에는 인스턴스 단위의 약한 일관성이 있다. 현재 API가 advisory top-N임을 문서화했으며, 강한 분산 claim은 기존 group elector의 책임으로 남겼다.
2. Redis 후보 갱신은 기존 read-modify-write 경계를 따른다. 동시 갱신의 atomic merge가 필요한 경우 별도 후속 이슈로 다룬다.
3. Redis coroutine adapter의 cancellation 및 SUCCESS/FAILURE 결과 갱신을 실제 backend에서 고정하는 회귀 테스트는 후속 보강 대상으로 남긴다.
4. `StrategicGroupElectionResult`는 새 public data class이므로 향후 필드 추가 시 생성자/copy ABI를 신중히 관리한다.

위 항목은 현재 DoD의 P0/P1 blocker가 아니며, 이번 PR의 merge-ready 판정을 막지 않는다.

## 최종 판정

`APPROVE` — #463 구현은 사용자 승인된 범위와 기존 leader 계약 경계를 만족하며 PR 생성 및 CI 게이트로 진행할 수 있다. 이 문서는 merge 승인 자체를 의미하지 않으며, merge 시점에는 정확한 head·CI·리뷰·thread를 다시 확인한다.
