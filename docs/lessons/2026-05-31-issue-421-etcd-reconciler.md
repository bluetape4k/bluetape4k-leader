# Issue #421 etcd 조정자 예

## 맥락

`bluetape4k-leader-etcd`에는 백엔드 적용 범위가 있지만 예제 카탈로그에는 실행 가능한 etcd 예제가 없습니다.

## 결정

프레임워크 중립 제어 평면 시나리오로 `examples/etcd-reconciler`를 추가했습니다. 이 예에서는 Spring Boot 또는 Ktor 어댑터를 도입하는 대신 `EtcdLeaderElector`를 직접 사용합니다.

## 결과

이 예에서는 실제 etcd Testcontainers 인스턴스에 대해 활성 리더 작업, 경합 건너뛰기 동작 및 릴리스 후 다시 획득을 보여줍니다.

## 검증

- `./gradlew :examples:etcd-reconciler:test`

## 향후 지침

예제 모듈, 업데이트 설정, 루트 README 로케일 세트, CI 경로 필터/작업 및 주간 예제 워크플로를 동일한 변경에 추가할 때.
