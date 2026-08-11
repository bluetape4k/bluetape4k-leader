# 배운 교훈 - 문제 668 게시 POM 라이선스 metadata(2026-08-11)

## 맥락

Issue #668에서 저장소와 `LICENSE`는 MIT를 선언했지만 공통 Maven
publication과 Leader BOM이 생성 POM에 Apache-2.0 metadata를 기록하고 있음을
확인했습니다. 소스 라이선스와 게시 metadata가 다르면 Maven 소비자의 SBOM과
라이선스 검사가 잘못된 결과를 냅니다.

## 결정

공통 publication과 `bluetape4k-leader-bom` publication의 license name, URL,
distribution을 MIT로 정렬하고, publication POM을 다시 생성한 뒤 전체 게시
대상을 검사하는 `verifyPublishedPomLicenses` Gradle gate를 추가했습니다.
Release와 snapshot aggregation task 및 publishable module의 repository upload
task가 업로드 전에 이 gate를 자동 실행하도록 연결해 수동 검사를 우회하지
못하게 했습니다.

## 결과

현재 gate는 게시 대상 POM 17개를 확인하고 각 POM에 `MIT License`,
`https://opensource.org/licenses/MIT`, `repo`를 요구합니다. `Apache-2.0` 또는
`Apache License` 문자열이 남아 있으면 실패합니다.

## 검증

- `./gradlew --no-daemon --console=plain --no-configuration-cache verifyPublishedPomLicenses`
- 결과: 17개 POM MIT metadata 검증 통과
- README EN/KO에 동일한 검증 명령과 gate 의미를 기록

## 향후 지침

Maven publication metadata를 변경할 때 대표 POM 하나만 확인하지 말고,
`verifyPublishedPomLicenses`로 전체 게시 대상과 BOM을 함께 재생성·검사하세요.
aggregation task와 module upload task는 이 검사를 자동으로 수행하므로 release 또는
snapshot 업로드가 검증 없이 진행되지 않는지 task graph도 함께 확인해야 합니다.
새 publishable module이 추가되면 기대 POM 수와 publication task inventory도
같은 변경에서 갱신해야 합니다.
