# 교훈 — 야간 전체 일정 조건(2026-06-04)

**관련 문제**: #470

## 맥락

중앙 스냅샷 메타데이터 경합을 줄이기 위해 Nightly cron 시간에 시차를 두었습니다. 전체 범위 예약 작업은 여전히 ​​`github.event.schedule`를 이전 일요일 크론 문자열과 비교하므로 주간 전체 작업을 건너뛸 수 있습니다.

후속 PR에서는 Gradle 명령이 `--refresh-dependencies`를 사용하지 않을 때 CI가 Nightly와 동일한 스냅샷 메타데이터 오류 경로에 도달할 수 있음을 보여주었습니다.

## 결정

시차를 둔 크론을 유지하고 전체 범위 작업 조건을 업데이트하여 저장소의 현재 일요일 일정과 비교하세요.

동일한 스냅샷 새로 고침 및 GitHub 실행기 구성 캐시 회피를 CI Gradle 호출에 적용하여 PR 검사가 Nightly에서 예상되는 동일한 종속성 해결 정책에 따라 분기를 검증하도록 합니다.

## 검증

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`
- 일정 조건 감사: 이전 `0 19 * * 0` 전체 작업 조건이 남아 있지 않습니다.
- CI/야간 Gradle 감사: 모든 `./gradlew` 호출에는 `--refresh-dependencies`가 포함됩니다.

## 미래의 규칙

예약된 cron 문자열을 변경할 때 동일한 작업 흐름에서 모든 `github.event.schedule` 비교를 업데이트하세요. 스냅샷 종속성 정책을 변경할 때 `.github/workflows/ci.yml`와 `.github/workflows/nightly-tests.yml`를 모두 감사하세요.
