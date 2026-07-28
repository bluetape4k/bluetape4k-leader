# 레슨 — Issue 412 K8s 그룹 슬롯 Nightly Coverage

날짜: 2026-05-29 문제: #412 분기: test/412-design-406-k8s-nightly-etcd-metadata

## 맥락

Issue #404에는 슬롯당 Kubernetes Lease 그룹 선택 및 K3s 테스트가 추가되었습니다. 후속 #412는 권한 있는 그룹 슬롯 런타임 범위가 로컬 증거에만 있는 것이 아니라 Nightly 전체 레인에 유지되도록 하기 위해 존재합니다.

## 결정

비K3s `:bluetape4k-leader-k8s:test` 슬라이스에서 풀 요청 CI를 유지합니다. Nightly 전체 `test-leader-k8s` 작업에서 실제 K3s 런타임 적용 범위를 유지하고 작업, Gradle 작업 설명 및 README 텍스트에서 슬롯당 임대 그룹 슬롯 적용 범위를 명시적으로 언급하도록 합니다.

## 결과

Nightly 전체 작업은 여전히 `:bluetape4k-leader-k8s:test :bluetape4k-leader-k8s:k8sTest`를 실행합니다. `k8sTest` 작업에는 #404에 의해 추가된 차단 및 일시 중지 그룹 K3s 테스트 클래스가 포함되어 있으므로 획득, 경합, 해제, 재획득, 만료 인수 및 취소/오류 정리 경로가 권한 있는 레인에서 다뤄집니다.

## 검증

- `./gradlew :bluetape4k-leader-k8s:test --no-daemon`가 통과되었습니다.
- `./gradlew :bluetape4k-leader-k8s:cleanK8sTest :bluetape4k-leader-k8s:k8sTest --no-daemon --max-workers=1 --no-build-cache`는 20개의 K3s 테스트를 통과했습니다.
- `actionlint .github/workflows/nightly-tests.yml`가 통과되었습니다.
- `git diff --check`가 통과되었습니다.

## 퓨쳐 가드

K3s 전용 테스트가 `leader-k8s`에 추가되면 `k8s` 태그를 유지하고 Nightly 전체 `test-leader-k8s` 작업이 여전히 `:bluetape4k-leader-k8s:k8sTest`를 실행하는지 검증합니다. 권한 있는 K3s 적용 범위를 빠른 풀 요청 CI 레인으로 이동하지 마십시오.
