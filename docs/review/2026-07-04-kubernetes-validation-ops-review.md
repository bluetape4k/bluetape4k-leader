# Kubernetes 검증 및 Ops 7 계층 검토

날짜: 2026-07-04 범위: Issue #575, 마일스톤 0.5.0

## 검토된 모듈

- `leader-k8s`: 네임스페이스 및 이름 유효성 검사 계약을 리스합니다.
- `examples/k8s-lease`: Fabric8 리스 호출 전 클라이언트 측 검증.
- `examples/k8s-operator`: RBAC, 배포, README 및 매니페스트 회귀 검증.

## 7계층 결과

1. 정확성: 통과
   - Kubernetes 네임스페이스 값은 선거인이 사용하기 전에 DNS-1123 레이블로 검증됩니다.
   - 예 이제 Fabric8이 호출을 생성, 읽기, 업데이트 또는 삭제하기 전에 리스 이름이 빠르게 실패합니다.

2. API 및 계약 호환성: 통과
   - 공개 선거법 서명이 변경되지 않았습니다.
   - 검증은 기존 옵션/예제 구성 및 호출 경계 내에서 유지됩니다.

3. 동시성 및 취소: PASS
   - 획득, 릴리스, 감시, 비동기 또는 코루틴 정리 경로가 변경되지 않았습니다.
   - 기존 K3s 획득/출시 테스트는 여전히 통과합니다.

4. 백엔드 소유권 안전성: 통과
   - 잘못된 네임스페이스 및 리스 이름은 Kubernetes로 전달되는 대신 클라이언트 측에서 거부됩니다.
   - 운영자 런타임 역할은 더 이상 리스 `delete`를 부여하지 않습니다.

5. 테스트: 합격
   - 잘못된 네임스페이스 및 리스 이름에 대한 부정적인 테스트를 추가했습니다.
   - 최소 권한 RBAC, 안정적인 이미지 태그, 시작/활성/준비 프로브에 대한 매니페스트 테스트가 추가되었습니다.
   - 영향을 받은 장치 및 K3s 지원 검사를 실행했습니다.

6. 보안 및 관찰 가능성: 통과
   - 런타임 운영자 권한은 `get`, `create`, `update` 및 `patch`로 축소됩니다.
   - 배포에서는 더 이상 변경 가능한 `latest` 태그를 사용하지 않습니다.
   - 프로브 동작은 영어와 한국어 README 파일에 문서화되어 있습니다.

7. 유지보수성: 합격
   - 네임스페이스 유효성 검사는 기존 Kubernetes Lease 이름 유효성 검사 옆에 있습니다.
   - 매니페스트 기대치는 소규모 전용 테스트 클래스에 잠겨 있습니다.

## 검증 증거

- `./gradlew :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin :examples:k8s-lease:compileKotlin :examples:k8s-lease:compileTestKotlin :examples:k8s-operator:compileKotlin :examples:k8s-operator:compileTestKotlin --warning-mode all`
- `./gradlew :bluetape4k-leader-k8s:test --tests 'io.bluetape4k.leader.k8s.internal.KubernetesLeaseSupportTest' :examples:k8s-lease:test --tests 'io.bluetape4k.leader.examples.k8slease.K8sLeaseValidationTest' :examples:k8s-operator:test --tests 'io.bluetape4k.leader.examples.k8soperator.OperatorManifestTest' --tests 'io.bluetape4k.leader.examples.k8soperator.OperatorControllerTest' --warning-mode all`
- `./gradlew :bluetape4k-leader-k8s:k8sTest :examples:k8s-lease:k8sTest :examples:k8s-operator:k8sTest --warning-mode all`
- `./gradlew :examples:k8s-lease:k8sTest --tests 'io.bluetape4k.leader.examples.k8slease.K8sLeaseLeaderElectionExampleTest' --warning-mode all`
- `rg -n "namespace\\.requireNotBlank\\(\"namespace\"\\)|leaseName\\.requireNotBlank\\(\"leaseName\"\\)|:latest|\"delete\"|delete" leader-k8s/src/main examples/k8s-lease/src/main examples/k8s-operator/k8s examples/k8s-operator/README.md examples/k8s-operator/README.ko.md -g '*.kt' -g '*.yaml' -g '*.md'`
- `git diff --check`

## Deferred 검증

전체 저장소 테스트는 요청된 워크플로우에 따라 전체 스택 이슈 트레인이 구현될 때까지 의도적으로 연기됩니다.
