# Issue #740 — Ktor diagnostics route의 timeout과 경로 충돌을 설치 시점에 검증

## 맥락

`LeaderElectionPlugin`은 connectivity probe를 활성화할 때 timeout이 양수이면서
유한한지 검증했지만, public `Application.leaderBackendDiagnosticsRoute` 직접 호출은
동일 계약을 강제하지 않았습니다. 또한 management route와 diagnostics route가
각자 선행 slash만 보정해, `internal/leader`와 `/internal/leader`가 같은 Ktor GET
selector로 등록될 수 있었습니다.

## 원인

- timeout 검증이 plugin 설정 경계에만 있어 public route 직접 호출 경계와
  비대칭이었습니다.
- management와 diagnostics route가 같은 정규화 규칙을 중복 구현했으며, plugin은
  두 경로를 실제 selector로 바꾸기 전에 충돌을 비교하지 않았습니다.

## 결정

- `normalizeLeaderRoutePath`를 두 route와 plugin collision 검사에서 공유합니다.
  기존 호환성을 유지하기 위해 현재 계약인 선행 slash 보정만 수행합니다.
- `validateBackendConnectivityCheckTimeout`을 public route와 plugin이 공유합니다.
  probe를 활성화한 경우에만 검증하고, plugin의 기존
  `backendConnectivityCheckTimeout` 오류 메시지 토큰을 보존합니다.
- management와 diagnostics route를 동시에 활성화하면 plugin 설치 초기에 두
  선행 slash를 보정한 path를 비교하고 같을 때 명확한 설정 오류를 냅니다.
- Kotlin `Duration`은 `NaN`을 표현하지 않으므로 `Double.NaN.seconds`가 route 호출
  전에 `IllegalArgumentException`을 던지는 type-level 경계도 회귀 테스트로
  기록합니다.

## 결과

public route 직접 호출과 plugin 설치가 동일한 positive/finite timeout 계약을
사용합니다. leading slash 표기만 다른 management/diagnostics 경로는 요청 처리
순서에 의존하지 않고 설치 단계에서 거부됩니다. 기본 경로, probe 비활성 경로,
기존 management JSON 응답은 변경하지 않았습니다.

## 검증

- RED: 기존 구현의 focused test는 `17 tests, 2 failed`로 direct invalid timeout과
  normalized route collision을 재현했습니다. NaN type-level test는 기존 Kotlin
  `Duration` 계약에 따라 통과했습니다.
- GREEN: 최종 `LeaderBackendDiagnosticsRouteTest`는 `17 tests`, failures/errors/skipped
  `0`으로 통과했습니다.
- `./gradlew :bluetape4k-leader-ktor:test --no-daemon --rerun-tasks`는 `123 tests`,
  failures/errors/skipped `0`으로 통과했습니다.
- Colima와 Docker context를 확인했고 Testcontainers 실행 환경은 healthy였습니다.
- `:bluetape4k-leader-ktor:detekt`, `checkBinaryCompatibility`
  (`artifacts=16`, `unknown=0`), `git diff --check`를 통과했습니다.
- 이번 변경이 추가한 한국어 prose 5개 파일은 terminology `findings=0`입니다.
  management route의 기존 KDoc `snapshot` 2건은 diff 밖의 선행 항목으로 확인해
  이번 bounded bugfix 범위에서는 변경하지 않았습니다.

## 재발 방지

- 실패한 판단: public helper와 plugin이 같은 이름의 설정을 받으면 검증 계약도
  자동으로 같을 것이라고 보았습니다.
- 발견 증거: direct route는 invalid timeout을 등록했고, leading slash가 다른 두
  경로는 동일 selector로 설치됐습니다.
- 수정 결정: 선행 slash 보정과 validation을 공유 함수로 올리고 plugin이 route
  등록 전에 충돌을 검사하도록 했습니다.
- 향후 예방 확인: framework adapter가 public direct API와 plugin/config API를 함께
  제공하면 기본값뿐 아니라 invalid value, 정규화 형식, 충돌 우선순위를 양쪽
  경계의 RED 테스트로 고정합니다.
