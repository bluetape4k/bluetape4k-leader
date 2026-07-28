# Issue #476 중앙 스냅샷 재시도

## 맥락
GitHub 실행자가 중앙 포털 스냅샷 메타데이터에서 임시 HTTP 403 응답을 받으면 다운스트림 CI 및 Nightly 실행이 failure할 수 있습니다.

## 결정
Gradle 명령 의미를 변경하지 않고 제한된 3회 시도 재시도 루프에서 최상위 Gradle 빌드 및 Nightly 감지 게이트를 래핑합니다.

## 검증
- `git diff --check`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`

## 다음 시간
중앙 메타데이터 403으로 인해 bluetape4k SNAPSHOT 종속성이 failure하는 경우 먼저 업스트림 게시 상태를 검증한 다음 종속성 또는 카탈로그 이탈보다 제한된 워크플로 재시도를 선호합니다.
