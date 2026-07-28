# 강의 — 이슈 404 슬롯당 Kubernetes Lease 그룹 선택

날짜: 2026-05-29 이슈: #404 브랜치: feat/404-k8s-group-lease-slots

## 맥락

`leader-k8s`는 이미 획득당 펜싱 토큰을 사용하여 안전한 단일 임대 선택을 수행했지만 슬롯당 임대 모델이 소유자 조건부 릴리스 및 확장을 유지할 수 있을 때까지 그룹 선택은 제외되었습니다.

## 결정

`<lockName>-slot-<slotIndex>`라는 그룹 슬롯당 하나의 Kubernetes Lease를 사용합니다. 각 슬롯에 대해 `KubernetesLeaseLock`를 재사용하여 그룹 선택이 기존 리소스 버전 비교 및 ​​설정, 홀더 펜싱 토큰, 릴리스, 상태 매핑 및 확장 위임 동작을 상속합니다.

`leader-core` 그룹 옵션을 변경하는 대신 `KubernetesLeaseGroupOptions`를 추가하세요. 그룹 자동 확장에는 별도의 핵심 수준 계약이 필요하므로 이 PR에 그룹 `autoExtend`를 추가하지 마세요. K3s 검증을 실행하는 동안 릴리스가 백엔드 정리 전에 진행 중인 확장을 기다리도록 기존 감시 닫기 경로를 수정합니다.

## 결과

구현에서는 차단/비동기 및 정지 그룹 선택기, `LeaderGroupState`에 대한 슬롯 상태 매핑, 그룹 획득 및 정리 동작에 대한 K3s 적용 범위, 영어와 한국어로 된 README 문서를 추가합니다.

## 검증

- `./gradlew :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin :bluetape4k-leader-k8s:test --no-daemon`가 통과되었습니다.
- 첫 번째 `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --max-workers=1` 실행은 새로운 그룹 테스트를 통과했지만 기존 일시 중지 감시 장치 경쟁이 노출되었습니다.
- `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.LeaderLeaseAutoExtenderTest" --tests "io.bluetape4k.leader.LeaderLeaseAutoExtenderDelegateTest" --no-daemon`가 통과되었습니다.
- Watchdog 닫기 수정 후 `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --max-workers=1`가 통과되었습니다.
- `./gradlew build -x test -x k8sTest --no-daemon`가 통과되었습니다.
- `git diff --check`가 통과되었습니다.

## 퓨쳐 가드

Kubernetes 그룹 선택의 경우 슬롯별 임대 소유권 검증에 대한 정확성을 유지합니다. `LeaderGroupState`는 관찰 가능성 스냅샷일 뿐이며 획득 게이트가 되어서는 안 됩니다.
