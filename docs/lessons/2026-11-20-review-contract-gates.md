# 2026-11-20 Leader 계약 게이트 리뷰 교훈

## 맥락

7-Tier 리뷰에서 공개 Kotlin facade, JVM ABI, Detekt baseline, CI 계약이
서로 다른 검증 경로를 사용하고 있었다. 정확한 release 비교에서는
compiler-generated accessor와 companion superclass 변경도 공개 ABI 결과로
표시되므로, 소스 테스트만 통과한 상태를 release-ready로 볼 수 없었다.

## 원인

- Consul/R2DBC 공개 wrapper를 `inline`으로 바꾸면 JVM descriptor가 같아도
  Kotlin source consumer의 non-local return과 inline body 계약이 달라진다.
- `KLogging()`을 public companion 또는 public abstract class에 상속시키면
  소비자에게 보이는 companion superclass가 바뀐다.
- Kotlin private suspend inline helper는 compiler-generated state-machine
  descriptor를 바꿀 수 있다.
- Detekt baseline은 사람이 정렬한 XML이 아니라 owning Gradle task가 생성한
  suppression ID를 기준으로 검증해야 한다.
- japicmp synthetic accessor는 일반 `access$` 예외가 아니라 owner와
  descriptor를 함께 고정해야 실제 public member 제거를 숨기지 않는다.

## 결정

공개 facade는 ordinary `fun`/`suspend fun` delegation을 유지하고, 로그는
public inheritance가 아닌 file-private logger로 둔다. private suspend helper의
불필요한 `inline`을 제거하며, Detekt baseline은 `detektBaseline` task로
재생성한다. ABI checker에는 확인된 synthetic accessor만 exact owner와
descriptor로 allowlist하고, unrelated accessor는 계속 실패하게 둔다.

## 검증

- 영향 모듈 테스트 2,234건 통과
- `./gradlew detekt` 통과
- public facade, Detekt baseline, CI fan-out validator 및 self-test 통과
- `check_binary_api_test.py` 24건 통과
- `checkBinaryCompatibility`: `artifacts=16`, `ignored=41`, `unknown=0`
- `git diff --check` 통과

## 향후 지침

공개 Kotlin API를 변경할 때는 JVM descriptor만 보지 말고 Kotlin consumer
compile/source contract와 companion superclass를 함께 확인한다. 생성된
baseline과 ABI suppression은 owning generator/checker test를 먼저 실행하고,
새 예외는 exact owner·descriptor와 회귀 테스트를 함께 추가한다. exact-head
CI가 새 변경을 실제로 실행하기 전에는 로컬 테스트 성공만으로 merge-ready를
주장하지 않는다.
