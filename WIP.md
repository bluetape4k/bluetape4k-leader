# WIP - bluetape4k-leader

- 기준일: 2026-11-23 KST
- 최신 안정 버전: `1.1.0`
- 안정 tag commit: `88a0dd1642ab19a05c125f6b11b11f7ca42f5ace`
- 현재 개발선: `1.2.0-SNAPSHOT`
- 현재 milestone: 미지정

## 현재 상태

`1.1.0` artifact, GitHub Release, stable manual 배포를 완료했다. `develop`은 `1.2.0` minor 개발선을 사용한다. `1.1.0` milestone의 lifecycle·backend 검증 이슈 #854–#859는 모두 종료됐다.

## 다음 개발선 규칙

- `gradle.properties`는 `baseVersion=1.2.0`, 빈 `snapshotVersion`을 유지한다.
- SNAPSHOT workflow가 실행할 때만 `-PsnapshotVersion=-SNAPSHOT`을 주입한다.
- 이번 train의 library 저장소와 CI는 공통 immutable catalog ref `7a5995fd46031ce1faf16337fa08d954436f8f9a` (`catalog/2026-11-22-01`)를 사용한다.
- 게시 순서는 Projects → Exposed → AWS → Graph → Image → JaVers → Leader → Text → Dependencies BOM이다. 중앙 BOM은 모든 하위 라이브러리의 개발 버전 게시를 확인한 뒤 마지막에 게시한다.

## 추적

생태계 개발선 전환의 이전 기록은 [bluetape4k-dependencies #235](https://github.com/bluetape4k/bluetape4k-dependencies/issues/235)에서 확인할 수 있다. 현재 Leader `1.2.0` GitHub milestone은 지정되지 않았다.
