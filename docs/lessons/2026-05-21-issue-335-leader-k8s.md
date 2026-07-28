# 문제 335 leader-k8s 레슨

## 맥락

Issue #335에는 `bluetape4k-leader`에 대해 게시 가능한 Kubernetes Lease 백엔드가 추가되었습니다. 백엔드는 기본 `coordination.k8s.io/v1` 리스 개체를 사용하는 동안 단일 리더 API 블로킹, 비동기 및 일시 중지를 지원해야 했습니다.

## 결정

`LeaderElectionOptions.nodeId`를 `spec.holderIdentity`에 직접 저장하지 마십시오. 획득별 펜싱 토큰을 `holderIdentity`로 사용하고 bluetape4k 주석에 표시/감사 ID를 저장합니다. 이렇게 하면 두 개의 선출기가 동일한 `nodeId`가 있는 동일한 JVM 또는 Pod에서 실행될 때 중복 실행이 방지됩니다.

PR CI는 `:bluetape4k-leader-k8s:test`만 실행해야 합니다. `koverXmlReport`는 현재 사용자 정의 `Test` 작업을 그래프로 가져오므로 K3s 적용 범위 생성은 빠른 PR 레인이 아닌 `:k8sTest` 이후 Nightly 전체에 속합니다.

## 결과

Fabric8 Kubernetes 클라이언트, 소유자 조건부 생성, 업데이트, 릴리스, 상태 매핑, README/RBAC 지침, SVG+PNG README 다이어그램, BOM/설정 연결 및 CI/Nightly 작업이 포함된 `leader-k8s`가 추가되었습니다.

## 검증

- `./gradlew :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-k8s:test --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-k8s:koverXmlReport --no-daemon --console=plain`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- `xmllint --noout docs/images/readme-diagrams/leader-k8s-architecture-01.svg docs/images/readme-diagrams/leader-k8s-sequence-02.svg`
- `node /Users/debop/work/bluetape4k/.omx/scripts/audit-readme-diagrams.mjs .`
- `node /Users/debop/work/bluetape4k/.omx/scripts/audit-readme-diagram-quality.mjs .`

`:bluetape4k-leader-k8s`에는 모듈 수준 `detekt` 작업이 없습니다. 정적 분석이 필요한 경우 저장소 수준 `detekt` 레인을 사용하세요.

## 미래 노트

`@Tag("k8s")` 태그가 지정된 K3s 테스트를 유지하고 기본 `test` 작업에서 제외합니다. 나중에 사용자 정의 통합 테스트 작업을 제외하도록 Kover를 구성하면 적용 범위 업로드가 PR CI 작업으로 돌아갈 수 있습니다.
