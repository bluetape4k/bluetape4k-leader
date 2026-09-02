# WIP - bluetape4k-leader

- 기준일: 2026-09-02 KST
- 최신 안정 버전: `1.0.0`
- 안정 tag commit: `e70146330302758f563a46b7286e3ce25f1bac49`
- 현재 개발선: `1.1.0-SNAPSHOT`
- 현재 milestone: `1.1.0`

## 현재 상태

`1.0.0` artifact, GitHub Release, stable manual 배포를 완료했다. `develop`은 `1.1.0` minor 개발선을 사용한다. 미해결 lifecycle·backend 검증 이슈는 기존 `1.1.0` milestone에서 계속 관리한다.

## 다음 개발선 규칙

- `gradle.properties`는 `baseVersion=1.1.0`, 빈 `snapshotVersion`을 유지한다.
- SNAPSHOT workflow가 실행할 때만 `-PsnapshotVersion=-SNAPSHOT`을 주입한다.
- 중앙 catalog SHA는 `bluetape4k-dependencies`의 다음 개발선이 병합된 뒤 한 번만 갱신한다.

## 추적

생태계 전체 후속 작업은 [bluetape4k-dependencies #235](https://github.com/bluetape4k/bluetape4k-dependencies/issues/235)에서 추적한다. 기존 #854-#859와 신규 변경은 `1.1.0` milestone에서 관리한다.
