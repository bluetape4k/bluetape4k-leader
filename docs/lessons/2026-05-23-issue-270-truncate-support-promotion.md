# 2026-05-23 270호 Truncate 지원 프로모션

## 맥락

`bluetape4k-projects 1.9.1`는 `String.truncateUtf8`를 `io.bluetape4k.support`로 승격했습니다. `bluetape4k-leader` Issue #270은 해당 업스트림 릴리스 이후 로컬 내부 잘림 도우미 제거를 추적합니다.

## 결정

`bluetape4k-core`에서 `io.bluetape4k.support.truncateUtf8`를 사용하고 로컬 `leader.internal.StringTruncateSupport` 복사본을 삭제합니다.

리더 빌드는 새로 게시된 `bluetape4k-projects` 릴리스 버전을 직접 참조해야 합니다. `catalogVersion`는 내부 `bluetape4k-*` 릴리스 순서를 대체하는 것이 아니라 bluetape4k 저장소 전반에 걸쳐 외부 라이브러리/플러그인 정렬을 위한 것입니다.

## 결과

`leader-core` 기록 삭제는 이제 공유 지원 기능에 따라 달라집니다. 로컬 중복 도우미가 제거되었습니다.

## 검증

```bash
./gradlew :bluetape4k-leader-core:test \
  --tests 'io.bluetape4k.leader.history.LeaderHistoryRecorderSupportTest' \
  -Pbluetape4kCatalogVersion=2026-05-23-00-SNAPSHOT \
  --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache
```

결과: `BUILD SUCCESSFUL`, 15개 테스트 통과.

## 퓨쳐 가드

bluetape4k 리포지토리를 공유 카탈로그로 마이그레이션할 때 내부 `bluetape4k-*` 릴리스 버전을 `catalogVersion` 뒤로 이동하지 마십시오. 로컬 BOM/버전 선언을 검증하고 Maven Central에서 업스트림 릴리스가 표시된 후에만 충돌하십시오.
