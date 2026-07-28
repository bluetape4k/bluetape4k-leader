# 2026-06-04 이슈 472 야간 Gradle 캐시

## 맥락

bluetape4k 리포지토리 전체의 Nightly 빌드는 GitHub 실행기의 `group:artifact:.`와 같은 관리 종속성을 간헐적으로 해결했습니다.

## 결정

예약된 실행이 오래된 종속성 관리 상태를 재사용하지 않도록 야간 작업에 대해 `gradle/actions/setup-gradle` 캐시 복원/쓰기를 비활성화합니다.

## 결과

이제 모든 Nightly `setup-gradle` 블록은 명시적인 Gradle 종속성 새로 고침을 유지하면서 `cache-disabled: true`를 설정합니다.

## 검증

- 감사된 `.github/workflows/nightly-tests.yml`: setup-gradle 블록은 캐시 비활성화 블록과 일치합니다.
- 계획된 검증: `actionlint`, `git diff --check`.

## 미래의 규칙

Nightly 워크플로가 스냅샷 또는 BOM 관리형 bluetape4k 종속성을 사용하는 경우 새로운 CI 증명에서 캐시 복원이 오래된 메타데이터를 재생할 수 없다고 표시하지 않는 한 Gradle 작업 캐시를 비활성화된 상태로 유지하세요.
