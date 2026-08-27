# 2026-08-27 Issue #805 ABI 기준선과 직렬화 경계

## 맥락

Issue #805에서 바이너리 호환성 게이트가 `0.4.0`/`0.5.0`을 스크립트
기본값으로 고정하고 있어 현재 `1.0.0` checkout에서 평범한 Gradle 실행이
존재하지 않는 `0.5.0` jar를 찾았다. 게시된 `0.4.0`과 `0.5.0`을 직접
비교하면 Redis Lettuce/Redisson 네 클래스의 public JVM bridge block 네 개가
미분류로 남았고, `CandidateInfo`와 `ExtendOutcome`에는 별도의 Java
serialization 계약 변경이 있었다.

## 결정

- 기준선은 명시적 `ABI_BASE_VERSION`이 없으면 현재 `gradle.properties`의
  버전보다 낮은 최신 Git release tag로 계산하고, 현재 버전은 Gradle 속성에서
  계산한다. release workflow의 명시적 환경 변수는 그대로 우선한다.
- Redis bridge와 compiler synthetic accessor는 문자열/flag 전체를 무시하지
  않고 클래스와 전체 japicmp descriptor를 함께 exact allowlist로 관리한다.
  allowlist 밖의 public 변경은 계속 `unknown`으로 남긴다.
- 0.4.0 `CandidateInfo` payload는 현재 명시 UID `1L`과 일치하지 않으므로
  `InvalidClassException`으로 거부한다. `ExtendOutcome`은 0.4.0에 없던
  serialization 표면을 새 계약으로 시작하며 concrete outcome의 UID는 `1L`로
  고정한다. 두 변경을 ABI 분류기에서 blanket ignore하지 않는다.

## 결과

게시된 `0.5.0` jar와 `0.4.0` 기준선 비교는 `artifacts=16,
ignored=23, unknown=0`으로 통과한다. 현재 `1.0.0` 기본 경로도
`artifacts=16, ignored=10, unknown=0`으로 통과한다. 이전 consumer가
erased `Object` descriptor로 bridge를 호출하는 linkage fixture와 0.4.0
`CandidateInfo` byte fixture가 각각 실제 실패/거부 경계를 고정한다.

## 검증

- RED: 새 Python 회귀가 version resolution, exact descriptor, consumer linkage
  계약의 미구현 상태에서 실패했다.
- GREEN: `python3 -m unittest -v scripts/compatibility/check_binary_api_test.py`
  — 15개 통과.
- Kotlin serialization fixture 3개, leader-core 940개, Lettuce 309개,
  Redisson 291개 테스트가 통과했다.
- `ruff check`, `py_compile`, `detekt --no-configuration-cache`,
  `git diff --check`가 통과했다. configuration-cache를 켠 `detekt`는
  `detektProductionSourceGuard`가 Gradle `Project`를 역직렬화하지 못하는
  기존 guard 오류로 실패했으며 코드 detekt 결과와 분리했다.

## 향후 지침

ABI release gate의 버전 기본값을 다시 하드코딩하지 않는다. 새 compiler
bridge/accessor는 실제 published descriptor와 소비자 linkage fixture를
확인한 뒤 클래스·descriptor 단위로만 등록한다. Java serialization 모델을
변경할 때는 이전 payload, 현재 UID, 지원/거부 마이그레이션 정책을 함께
테스트하고 migration 문서에 기록한다.
