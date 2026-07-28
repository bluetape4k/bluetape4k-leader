# 야간 스냅샷 새로 고침

## 맥락

Nightly는 Gradle 캐시를 복원하고 변경 가능한 bluetape4k Central 스냅샷 아티팩트를 사용합니다. 오래된 스냅샷 메타데이터 또는 동시 중앙 스냅샷 메타데이터 요청으로 인해 테스트가 실행되기 전에 모듈 작업이 failure할 수 있습니다.

## 결정

`--refresh-dependencies`를 Nightly Gradle 호출에 전달하고 예약된 cron 분을 시차를 두고 모든 다운스트림 저장소를 동시에 시작하지 않고도 스냅샷 메타데이터를 다시 검증할 수 있습니다.

## 결과

Nightly는 빌드 상태에 대한 캐시 재사용을 유지하고, 변경 가능한 메타데이터를 새로 고치고, 예약된 저장소 간 중앙 스냅샷 경합을 줄입니다.

## 검증

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`
