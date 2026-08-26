# Issue #785 교훈 — 전략적 후보 조회 오류와 contention 분리

## 맥락

Redis Lettuce와 Redisson의 전략적 single/group elector가 후보 목록을 읽을 때
`runCatching` 또는 `catch(Throwable)`로 모든 실패를 `null`로 바꾸고 있었습니다.
후보가 실제로 없어 선출되지 않은 정상 contention과 Redis 연결·codec·backend 장애를
같은 결과로 처리하면 작업이 실행되지 않은 이유를 운영자가 구분할 수 없습니다.

## 결정

후보 조회 경계에서는 `CancellationException`을 먼저 재전파하고, 일반 `Exception`은
로그를 남긴 뒤 그대로 던집니다. `Error`는 잡지 않아 프로세스 수준 신호를
contention으로 축소하지 않습니다. 후보 목록이 정상적으로 비어 있거나 전략이
승자를 선택하지 못한 경우의 `null`, action 이후 결과 업데이트의 best-effort 정책,
후보 TTL과 key namespace는 그대로 유지했습니다. Lettuce registry의 손상된
`CandidateInfo` decode도 후보 하나를 조용히 제외하지 않고 조회 오류로 전파하도록
정렬했습니다.

## 결과

8개 경로(Lettuce/Redisson × blocking/suspend × single/group)가 동일한 예외 경계를
공유합니다. backend 오류는 action 전에 호출자에게 보이고, cancellation은 취소
상태를 보존하며, 정상 no-winner만 기존 `null` 계약을 유지합니다.

## 검증

- RED: 초기 회귀 5개 기준으로 Lettuce 4개, Redisson 5개가 기존 fallback에서
  기대한 예외 없이 실패했습니다(기존 suspend cancellation 테스트 1개는 이미 통과).
- GREEN: MockK 기반 unavailable/codec lookup fixture로 Lettuce 8개와 Redisson 8개가
  모두 통과했습니다. 일반 예외·cancellation·`Error`·손상 codec 데이터 전파와
  action 미실행을 함께 확인했습니다.
- rebase 후 전체 모듈 suite도 Lettuce 290개, Redisson 276개가 통과했고 두 모듈
  detekt와 `git diff --check`가 성공했습니다. hosted exact-head 검증은 PR 단계에서
  다시 수집합니다.

## 다음 변경자 지침

후보·락·lease 조회처럼 backend 장애와 정상 경쟁을 함께 반환할 수 있는 경계에
`runCatching { ... }.getOrElse { return null }` 또는 `catch(Throwable) -> null`을
추가하지 마세요. 결과 타입을 확장하지 않는 한 예외 분류를 코드 경계에서
명시하고, 각 blocking/suspend/backend 조합에 cancellation·`Error`·일반 예외
회귀를 고정하세요.
