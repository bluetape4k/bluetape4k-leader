# 강의: 0.2.0 릴리스 준비 종속성 조정

**날짜**: 2026-05-23

## 맥락

`0.2.0` 이정표가 완료되었으며 `develop`에는 이전 `0.1.1` 유지 관리 범위와 최신 `0.2.0` 기능 범위가 모두 포함되어 있습니다. 저장소를 스냅샷 메타데이터에서 최종 릴리스 메타데이터로 변환하려면 릴리스 준비가 필요합니다.

## 결정

`0.2.0`를 다음 공식 릴리스로 준비하고 릴리스 PR을 열기 전에 repo-local `bluetape4k-*` 참조를 정렬하세요. 이 주기에서 `bluetape4k-exposed`는 `1.8.0`에서 이미 게시된 `1.9.0` 릴리스 트레인으로 이동했습니다.

## 결과

- 이제 `gradle.properties`는 프로젝트 버전을 `0.2.0`로 검증합니다.
- 공개 종속성 조각은 이제 `0.2.0`를 사용합니다.
- `CHANGELOG.md`에는 날짜가 지정된 `0.2.0` 섹션이 있습니다.
- `WIP.md`는 `0.2.1`에 할당된 단일 남은 문제를 반영합니다.
- 이제 Workspace 릴리스 절차 문서에 업스트림 릴리스 준비가 repo-local `bluetape4k-*` 종속성 별칭을 업데이트해야 한다고 기록되어 있습니다.

## 검증

- `./gradlew properties --no-daemon | rg '^version:|^group:'`는 `group: io.github.bluetape4k.leader` 및 `version: 0.2.0`를 보고했습니다.
- 로컬 K3s Testcontainers 엔드포인트가 이미 리스 정리 중에 연결을 거부했기 때문에 `./gradlew build --no-daemon`는 `:bluetape4k-leader-k8s:k8sTest`가 실패할 때까지 다운스트림 모듈을 컴파일하고 실행했습니다.
- `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon`를 재시도하면 K3s 테스트 본문에 도달했지만 다른 12개의 K3s 테스트는 통과했지만 하나의 감시 장치 재획득 어설션은 실패했습니다.
- `./gradlew build -x :bluetape4k-leader-k8s:k8sTest --no-daemon`가 성공적으로 완료되어 릴리스 준비 표면의 나머지 부분을 덮었습니다.

## 향후 지침

- 릴리스 준비 PR은 저장소 자체 `baseVersion`뿐만 아니라 오래된 `bluetape4k-*` 별칭이 있는지 `gradle/libs.versions.toml`를 검증해야 합니다.
- 로컬 K3s 엔드포인트 거부를 환경에 민감한 검증 격차로 간주합니다. CI/Nightly는 Kubernetes 지원 테스트의 릴리스 게이트가 되어야 합니다.
