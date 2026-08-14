# 릴리스 preflight에서 JDK 25 virtual-thread snapshot을 차단하기

## 배경

Issue #689는 `bluetape4k-leader`의 0.6.0 릴리스가
`bluetape4k-virtualthread-api`와 `bluetape4k-virtualthread-jdk25`의
변경 가능한 `1.13.0-SNAPSHOT` 해석에 의존하던 문제를 다룬다. 기존 release
workflow는 `baseVersion`과 `snapshotVersion`만 검사했기 때문에 provider
version property가 snapshot이어도 publish 단계까지 진행될 수 있었다.

## 원인

Gradle의 `central-snapshots` 저장소는 `1.13.0-SNAPSHOT`을 현재 timestamp
build로 해석하지만, release preflight에는 그 resolved dependency가 snapshot인지
확인하는 단계가 없었다. 따라서 태그와 프로젝트 버전이 일치해도 공급망 입력이
재현 가능하다고 보장할 수 없었다.

## 결정

- release train의 기본 좌표를 `1.13.0-20260813.192107-9` immutable timestamp
  coordinate로 고정한다.
- 개발선에서 snapshot을 시험해야 하는 경우에만
  `-Pbluetape4kVirtualThreadJdk25Version=1.13.0-SNAPSHOT`으로 명시적 override를
  사용한다.
- release workflow는 `-SNAPSHOT` 좌표를 즉시 거부하고, `leader-core`의
  `compileClasspath` dependency graph에서 API/provider가 동일한 immutable
  좌표로 해석되는지 별도 validator로 확인한다.

## 검증

- `scripts/ci/validate_release_preflight_test.py`가 snapshot 거부, 동일 좌표 API/provider
  해석, snapshot resolution 거부를 검증한다.
- release workflow는 실패 시 `::error::`를 출력하고 publish 단계 전에 종료한다.
- 실제 Gradle dependency graph는 release 실행 시 `$RUNNER_TEMP/virtualthread-dependencies.txt`
  로 보존되고 `tee`로 job log에도 남으며, validator가 선택된 API/provider 좌표를
  다시 출력한다.

## 향후 지침

새 JDK 25 provider build를 채택할 때는 Maven Central Snapshots의 timestamp와
API/provider POM 존재를 먼저 확인하고, `gradle.properties`의 immutable 좌표와
release preflight 테스트를 함께 갱신한다. 개발 snapshot override를 release
branch에 남기지 않는다.
