# 스냅샷 검증 라인

## 맥락

이전 릴리스 이후 스냅샷 유효성 검사에서는 내부 참조가 일치하는 스냅샷을 사용하는 동안 `snapshotVersion=`가 git에서 비어 있는 작업 공간 규칙을 따르기 위해 리포지토리가 필요했습니다.

## 결정

`baseVersion=0.2.2`를 유지하고, `snapshotVersion=`를 지우고, 기존 `bluetape4k-bom:1.9.2-SNAPSHOT`와 함께 `bluetape4k-exposed-bom:1.9.2-SNAPSHOT`를 소비합니다.

## 결과

저장소는 `gradle.properties`에 대한 스냅샷 접미사를 검증하지 않고 `publish-snapshot.yml`를 통해 `0.2.2-SNAPSHOT`를 게시할 수 있습니다.

## 검증

스냅샷 유효성 검사 과정에서 보류 중입니다.
